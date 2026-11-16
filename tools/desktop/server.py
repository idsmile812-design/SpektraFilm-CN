#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Loopback-only HTTP bridge for the Spektrafilm desktop editor. GPLv3.

This is the transport for the native editor worker: it also serves the four
fixed browser assets in ``tools/desktop/web`` (no other static file, no
directory listing, no external resource).  It binds exactly one IPv4 loopback
socket on a random port, mints a random bearer token per launch, and exposes the
small fixed API the editor client uses.

Security invariants enforced here:

* bind ``127.0.0.1`` only (never ``0.0.0.0``), random port by default;
* ``Host`` must name this loopback server (DNS-rebinding defence);
* API ``Origin``, when present, must be exactly this server's origin;
* every ``/api/*`` request needs ``X-Spektra-Token``;
* no ``Access-Control-Allow-*`` header is ever emitted;
* every response carries ``Content-Security-Policy: default-src 'self'``;
* request bodies are either raw image bytes (``/api/open``) or JSON, with
  explicit size ceilings and no chunked transfer encoding;
* media is served only from the session's registered file table, never by
  joining a user-supplied path;
* browser assets are served only from a fixed four-entry whitelist inside
  ``tools/desktop/web``; every other path is a 404;
* all errors are Chinese JSON with a meaningful status;
* access logging is off, so photo names and tokens are not written to a log.

Standard library only.
"""

from __future__ import annotations

import hmac
import json
import os
import re
import secrets
import shutil
import socket
import tempfile
import threading
import time
import urllib.parse
import uuid
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

try:  # support both "python server.py" and "import tools.desktop.server"
    from .worker import (
        StaleGenerationError,
        WorkerClient,
        WorkerError,
        WorkerProtocolError,
        WorkerRequestError,
        WorkerTimeoutError,
    )
except ImportError:  # pragma: no cover - exercised by the script entry point
    from worker import (
        StaleGenerationError,
        WorkerClient,
        WorkerError,
        WorkerProtocolError,
        WorkerRequestError,
        WorkerTimeoutError,
    )

__all__ = [
    "LocalServer",
    "SessionState",
    "MediaEntry",
    "sniff_image_format",
    "image_dimensions",
    "sanitize_download_filename",
    "MAX_UPLOAD_BYTES",
    "MAX_JSON_BYTES",
    "MAX_PIXELS",
    "MAX_FILENAME_CHARS",
]

MAX_UPLOAD_BYTES = 100 * 1024 * 1024       # 100 MiB
MAX_JSON_BYTES = 1024 * 1024               # 1 MiB
MAX_PIXELS = 50_000_000                    # ~50 MP, native may be stricter
MAX_FILENAME_CHARS = 160
READ_CHUNK = 65536

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_JPEG_SOI = b"\xff\xd8\xff"

# The only browser assets this server will ever return.  The mapping is a fixed
# whitelist: a request path is looked up, never joined into a filesystem path.
_STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
}

_RECIPE_KEYS = {
    "presetId",
    "strength",
    "exposureEv",
    "grainScale",
    "halationScale",
    "filmProfile",
    "printProfile",
}

_STATUS_CHINESE = {
    400: "请求格式错误",
    401: "缺少访问令牌",
    403: "拒绝访问",
    404: "资源不存在",
    405: "方法不允许",
    408: "请求超时",
    411: "必须提供 Content-Length",
    413: "请求或图片过大",
    414: "请求地址过长",
    415: "不支持的内容类型",
    422: "无法处理该图片或配方",
    431: "请求头过大",
    500: "服务器内部错误",
    501: "请求方法未实现",
    503: "服务暂不可用",
}


class ApiError(Exception):
    """An error that should be rendered as Chinese JSON with ``status``."""

    def __init__(
        self,
        status: int,
        message: str,
        *,
        code: str = "bad_request",
        headers: Optional[Dict[str, str]] = None,
        extra: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.message = message
        self.code = code
        self.headers = headers or {}
        self.extra = extra or {}


@dataclass
class MediaEntry:
    """One session file that may be served over ``/media/<name>``."""

    name: str
    path: Path
    mime: str
    generation: int = 0


@dataclass
class SessionState:
    """Mutable server-side state guarded by ``lock``."""

    directory: Path
    token: str
    generation: int = 0
    epoch: int = 0
    preview_token: Optional[object] = None
    has_image: bool = False
    media: Dict[str, MediaEntry] = field(default_factory=dict)
    lock: threading.RLock = field(default_factory=threading.RLock)


# --------------------------------------------------------------------------- images
def sniff_image_format(data: bytes) -> Optional[str]:
    """Return ``"png"``/``"jpeg"`` from magic bytes, never from a file name."""
    if len(data) >= 8 and data.startswith(_PNG_SIGNATURE):
        return "png"
    if len(data) >= 3 and data.startswith(_JPEG_SOI):
        return "jpeg"
    return None


def _png_dimensions(data: bytes) -> Optional[Tuple[int, int]]:
    if len(data) < 33 or not data.startswith(_PNG_SIGNATURE):
        return None
    if data[12:16] != b"IHDR":
        return None
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    if width <= 0 or height <= 0:
        return None
    return width, height


def _jpeg_dimensions(data: bytes) -> Optional[Tuple[int, int]]:
    if len(data) < 4 or not data.startswith(b"\xff\xd8"):
        return None
    index = 2
    length = len(data)
    soff = {
        0xC0,
        0xC1,
        0xC2,
        0xC3,
        0xC5,
        0xC6,
        0xC7,
        0xC9,
        0xCA,
        0xCB,
        0xCD,
        0xCE,
        0xCF,
    }
    while index + 3 < length:
        if data[index] != 0xFF:
            index += 1
            continue
        while index < length and data[index] == 0xFF:
            index += 1
        if index >= length:
            return None
        marker = data[index]
        index += 1
        if marker == 0x00 or marker == 0x01 or 0xD0 <= marker <= 0xD9:
            continue
        if index + 1 >= length:
            return None
        segment_length = int.from_bytes(data[index : index + 2], "big")
        if segment_length < 2 or index + segment_length > length:
            return None
        if marker in soff:
            if segment_length < 7 or index + 7 > length:
                return None
            height = int.from_bytes(data[index + 3 : index + 5], "big")
            width = int.from_bytes(data[index + 5 : index + 7], "big")
            if width <= 0 or height <= 0:
                return None
            return width, height
        index += segment_length
    return None


def image_dimensions(data: bytes, image_format: Optional[str] = None) -> Optional[Tuple[int, int]]:
    """Read the encoded pixel dimensions without decoding or trusting a name."""
    image_format = image_format or sniff_image_format(data)
    if image_format == "png":
        return _png_dimensions(data)
    if image_format == "jpeg":
        return _jpeg_dimensions(data)
    return None


# --------------------------------------------------------------------------- names
def sanitize_download_filename(name: Any, image_format: str, *, now: Optional[float] = None) -> str:
    """Keep Unicode letters/digits/space/underscore/hyphen; append the real suffix.

    A ``.`` is intentionally removed from the user part: the extension is chosen
    by the requested export format, not by the name.  Empty/invalid names become
    ``Spektrafilm_<timestamp>``.  The result can never contain a path separator,
    control character or quote.
    """
    cleaned = ""
    if isinstance(name, str):
        for char in name:
            if char.isalnum() or char in (" ", "_", "-"):
                cleaned += char
    cleaned = " ".join(cleaned.split())
    cleaned = cleaned.strip(" _-")
    if len(cleaned) > MAX_FILENAME_CHARS:
        cleaned = cleaned[:MAX_FILENAME_CHARS].strip(" _-")
    if not cleaned:
        stamp = time.strftime("%Y%m%d_%H%M%S", time.localtime(now))
        cleaned = f"Spektrafilm_{stamp}"
    suffix = ".png" if image_format == "png" else ".jpg"
    return cleaned + suffix


def _content_disposition(filename: str, fallback: str) -> str:
    quoted = urllib.parse.quote(filename, safe="")
    ascii_fallback = fallback
    return f"attachment; filename=\"{ascii_fallback}\"; filename*=UTF-8''{quoted}"


# --------------------------------------------------------------------------- server
class _HTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    allow_reuse_address = True

    def __init__(self, address: Tuple[str, int], app: "LocalServer") -> None:
        self.app = app
        super().__init__(address, _Handler)

    def handle_error(self, request: Any, client_address: Any) -> None:
        # The default prints a traceback, which can include session paths.
        # Keep the local service silent; the request already got a JSON 500.
        return


class LocalServer:
    """The loopback HTTP service.  Owns the session directory and worker."""

    def __init__(
        self,
        *,
        worker_path: Optional[Path] = None,
        assets_dir: Optional[Path] = None,
        zh_strings_path: Optional[Path] = None,
        port: int = 0,
        token: Optional[str] = None,
        worker_timeout: float = 180.0,
        catalog_data: Optional[Dict[str, Any]] = None,
        worker_factory: Optional[Callable[..., Any]] = None,
        session_root: Optional[Path] = None,
        web_dir: Optional[Path] = None,
    ) -> None:
        # Imported lazily so tests can inject a fully built catalog and so a
        # missing asset fails with catalog.CatalogError's precise message.
        if catalog_data is None:
            try:
                from .catalog import load_catalog
            except ImportError:  # pragma: no cover - script entry point
                from catalog import load_catalog

            catalog_data = load_catalog(
                assets_dir=assets_dir, zh_strings_path=zh_strings_path
            )
        self.catalog = catalog_data

        self._repo_root = Path(__file__).resolve().parents[2]
        self.worker_path = Path(worker_path) if worker_path else (
            self._repo_root / "tools" / "desktop" / ".build" / "spektra_worker"
        )
        self.assets_dir = Path(assets_dir) if assets_dir else (
            self._repo_root
            / "engine"
            / "spektra-core"
            / "src"
            / "main"
            / "assets"
            / "spektra"
        )
        self.web_dir = Path(web_dir) if web_dir else (
            Path(__file__).resolve().parent / "web"
        )
        self.token = token or secrets.token_urlsafe(32)
        self.worker_timeout = float(worker_timeout)
        self._port_request = int(port)
        self._worker_factory = worker_factory or WorkerClient

        self._session_parent = Path(session_root) if session_root else None
        self.session_dir = Path(
            tempfile.mkdtemp(
                prefix="spektrafilm-session-",
                dir=str(self._session_parent) if self._session_parent else None,
            )
        )
        try:
            os.chmod(self.session_dir, 0o700)
        except OSError as exc:
            shutil.rmtree(self.session_dir, ignore_errors=True)
            raise RuntimeError(f"无法设置会话目录权限：{exc}") from exc

        self.state = SessionState(directory=self.session_dir, token=self.token)
        self.httpd: Optional[_HTTPServer] = None
        self.worker: Any = None
        self._open_lock = threading.Lock()
        self._stop_lock = threading.Lock()
        self._serving = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._stopped = False

        try:
            self.worker = self._worker_factory(
                self.worker_path,
                self.assets_dir,
                self.session_dir,
                timeout=self.worker_timeout,
            )
            if hasattr(self.worker, "start"):
                self.worker.start()
            self.httpd = _HTTPServer(("127.0.0.1", self._port_request), self)
        except BaseException:
            if self.worker is not None:
                try:
                    self.worker.stop()
                except Exception:
                    pass
            shutil.rmtree(self.session_dir, ignore_errors=True)
            raise

    # ------------------------------------------------------------------ lifecycle
    @property
    def port(self) -> int:
        assert self.httpd is not None
        return int(self.httpd.server_address[1])

    @property
    def origin(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    @property
    def url(self) -> str:
        return f"{self.origin}/?token={urllib.parse.quote(self.token, safe='')}"

    def serve_forever(self) -> None:
        assert self.httpd is not None
        self._serving.set()
        try:
            self.httpd.serve_forever(poll_interval=0.1)
        finally:
            self._serving.clear()

    def start(self) -> str:
        """Start the HTTP loop in a daemon thread; returns the origin URL."""
        if self._thread is not None:
            return self.origin
        assert self.httpd is not None
        self._thread = threading.Thread(
            target=self.serve_forever, name="spektrafilm-http", daemon=True
        )
        self._thread.start()
        deadline = time.monotonic() + 5.0
        while not self._serving.is_set() and time.monotonic() < deadline:
            time.sleep(0.005)
        return self.origin

    def stop(self) -> None:
        """Stop HTTP, close the native worker, and remove the 0700 session."""
        with self._stop_lock:
            if self._stopped:
                return
            self._stopped = True
        if self.httpd is not None:
            if self._serving.is_set():
                try:
                    self.httpd.shutdown()
                except Exception:
                    pass
            try:
                self.httpd.server_close()
            except Exception:
                pass
            self.httpd = None
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None
        if self.worker is not None:
            try:
                self.worker.stop()
            except Exception:
                pass
            self.worker = None
        shutil.rmtree(self.session_dir, ignore_errors=True)

    def __enter__(self) -> "LocalServer":
        self.start()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.stop()

    # ------------------------------------------------------------------ helpers
    def valid_host(self, host: Optional[str]) -> bool:
        if not host:
            return False
        port = self.port
        allowed = {
            f"127.0.0.1:{port}",
            f"localhost:{port}",
        }
        return host.strip().lower() in allowed

    def valid_origin(self, origin: Optional[str]) -> bool:
        if origin is None:
            return True
        port = self.port
        allowed = {
            f"http://127.0.0.1:{port}",
            f"http://localhost:{port}",
        }
        return origin.strip().lower() in allowed

    def check_token(self, supplied: Optional[str]) -> bool:
        if not supplied:
            return False
        try:
            return hmac.compare_digest(supplied, self.token)
        except (TypeError, ValueError):
            return False

    # ------------------------------------------------------------------ API pieces
    def media_url(self, name: str) -> str:
        return f"/media/{urllib.parse.quote(name, safe='')}?token={urllib.parse.quote(self.token, safe='')}"

    def register_media(self, name: str, mime: str, generation: int = 0) -> str:
        with self.state.lock:
            self.state.media[name] = MediaEntry(
                name=name,
                path=self.session_dir / name,
                mime=mime,
                generation=generation,
            )
        return self.media_url(name)

    def _clear_media(self, *, keep_source: bool = False) -> None:
        with self.state.lock:
            keep: Dict[str, MediaEntry] = {}
            for name, entry in list(self.state.media.items()):
                if keep_source and name == "source.png":
                    keep[name] = entry
                    continue
                try:
                    entry.path.unlink(missing_ok=True)
                except OSError:
                    pass
            self.state.media = keep

    def _begin_preview(self, generation: int) -> object:
        token = object()
        with self.state.lock:
            current = self.state.generation
            if generation < current:
                raise ApiError(
                    409,
                    f"预览代次 {generation} 已过期（当前代次 {current}）",
                    code="stale_generation",
                    extra={"generation": generation, "currentGeneration": current},
                )
            if generation == current and self.state.preview_token is not None:
                raise ApiError(
                    409,
                    f"预览代次 {generation} 正在渲染，更新的请求将取代它",
                    code="stale_generation",
                    extra={"generation": generation, "currentGeneration": current},
                )
            if generation > current:
                self.state.generation = generation
            self.state.preview_token = token
            return token

    def _finish_preview(self, token: object, epoch: int) -> bool:
        """Clear the in-flight marker; return True only if this request still owns it."""
        with self.state.lock:
            owned = self.state.preview_token is token
            if owned:
                self.state.preview_token = None
            return owned and self.state.epoch == epoch

    def _commit_preview(
        self, token: object, epoch: int, out_name: str, generation: int
    ) -> bool:
        """Atomically validate ownership and publish one preview into the media table.

        Doing the epoch check and the media registration under the same lock is
        what prevents a preview that finished just as a new open began from
        registering an old image in the new session.
        """
        with self.state.lock:
            owned = self.state.preview_token is token
            if owned:
                self.state.preview_token = None
            if not owned or self.state.epoch != epoch:
                return False
            for name, entry in list(self.state.media.items()):
                if name.startswith("preview-"):
                    try:
                        entry.path.unlink(missing_ok=True)
                    except OSError:
                        pass
                    self.state.media.pop(name, None)
            self.state.media[out_name] = MediaEntry(
                name=out_name,
                path=self.session_dir / out_name,
                mime="image/png",
                generation=generation,
            )
            return True

    # ------------------------------------------------------------------ construction helpers
    def _new_session_file(self, prefix: str, suffix: str) -> str:
        return f"{prefix}-{uuid.uuid4().hex}{suffix}"

    def _write_upload(self, data: bytes, suffix: str) -> str:
        name = self._new_session_file("input", suffix)
        path = self.session_dir / name
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(path, flags, 0o600)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
        except BaseException:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
            raise
        return name


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SpektrafilmLocal"
    sys_version = ""

    # No access log, no error log: both can contain photo names/tokens.
    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return

    def log_error(self, format: str, *args: Any) -> None:  # noqa: A002
        return

    @property
    def app(self) -> LocalServer:
        return self.server.app  # type: ignore[attr-defined]

    # ------------------------------------------------------------------ dispatch
    def do_GET(self) -> None:
        self._guarded("GET")

    def do_POST(self) -> None:
        self._guarded("POST")

    def do_OPTIONS(self) -> None:
        self._guarded("OPTIONS")

    def do_HEAD(self) -> None:
        self._guarded("HEAD")

    def do_PUT(self) -> None:
        self._guarded("PUT")

    def do_DELETE(self) -> None:
        self._guarded("DELETE")

    def _guarded(self, method: str) -> None:
        try:
            self._dispatch(method)
        except ApiError as exc:
            self._send_api_error(exc)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            # Never echo an exception: it can contain a path.  Logging is off.
            self._send_api_error(
                ApiError(500, "服务器内部错误", code="internal_error")
            )

    def _dispatch(self, method: str) -> None:
        # Slow clients must not hold a thread forever.
        try:
            self.connection.settimeout(30.0)
        except OSError:
            pass

        if not self.app.valid_host(self.headers.get("Host")):
            raise ApiError(403, "Host 不是当前 loopback 服务", code="bad_host")

        raw_target = self.path or ""
        if not raw_target.startswith("/"):
            raise ApiError(400, "请求地址必须是绝对路径", code="bad_target")
        split = urllib.parse.urlsplit(raw_target)
        if split.scheme or split.netloc:
            raise ApiError(400, "请求地址不得包含协议或主机", code="bad_target")
        path = split.path

        if path.startswith("/api/"):
            if not self.app.valid_origin(self.headers.get("Origin")):
                raise ApiError(403, "Origin 与本地服务不同源", code="bad_origin")
            token = self.headers.get("X-Spektra-Token")
            if not token:
                raise ApiError(
                    401,
                    "缺少 X-Spektra-Token 请求头",
                    code="missing_token",
                )
            if not self.app.check_token(token):
                raise ApiError(403, "X-Spektra-Token 无效", code="bad_token")
            self._dispatch_api(method, path, split.query)
            return

        if path.startswith("/media/"):
            self._serve_media(method, path, split.query)
            return

        if method not in ("GET", "HEAD"):
            raise ApiError(405, "不支持的请求方法", code="method_not_allowed")
        self._serve_static(method, path)

    # ------------------------------------------------------------------ static UI
    def _serve_static(self, method: str, path: str) -> None:
        """Serve one of the fixed browser assets from ``tools/desktop/web``.

        The path is a dictionary key, never part of a filesystem path.  Even a
        symlinked asset is rejected unless its resolved parent is the web root.
        Other paths still fall through to the Chinese JSON 404.
        """
        entry = _STATIC_FILES.get(path)
        if entry is None:
            raise ApiError(404, "没有该资源", code="not_found")
        name, content_type = entry
        try:
            root = self.app.web_dir.resolve(strict=True)
        except (OSError, RuntimeError):
            raise ApiError(404, "没有该资源", code="not_found")
        if not root.is_dir():
            raise ApiError(404, "没有该资源", code="not_found")
        try:
            resolved = (root / name).resolve(strict=True)
        except (OSError, RuntimeError):
            raise ApiError(404, "没有该资源", code="not_found")
        if resolved.parent != root or not resolved.is_file():
            raise ApiError(404, "没有该资源", code="not_found")
        try:
            raw = resolved.read_bytes()
        except OSError:
            raise ApiError(404, "没有该资源", code="not_found")
        self._send_bytes(
            HTTPStatus.OK,
            b"" if method == "HEAD" else raw,
            content_type,
            {"Content-Disposition": "inline"},
            content_length=len(raw),
        )

    def _dispatch_api(self, method: str, path: str, query: str) -> None:
        if query:
            raise ApiError(400, "API 不接受查询参数", code="unexpected_query")
        if path == "/api/health":
            self._require_method(method, {"GET"})
            self._send_json(
                HTTPStatus.OK,
                {
                    "ok": True,
                    "status": "ok",
                    "port": self.app.port,
                },
            )
            return
        if path == "/api/catalog":
            self._require_method(method, {"GET"})
            payload: Dict[str, Any] = dict(self.app.catalog)
            payload["ok"] = True
            self._send_json(HTTPStatus.OK, payload)
            return
        if path == "/api/open":
            self._require_method(method, {"POST"})
            self._handle_open()
            return
        if path == "/api/preview":
            self._require_method(method, {"POST"})
            self._handle_preview()
            return
        if path == "/api/export":
            self._require_method(method, {"POST"})
            self._handle_export()
            return
        raise ApiError(404, "没有该 API", code="not_found")

    def _require_method(self, method: str, allowed: set[str]) -> None:
        if method not in allowed:
            raise ApiError(
                405,
                "不支持的请求方法",
                code="method_not_allowed",
                headers={"Allow": ", ".join(sorted(allowed))},
            )

    # ------------------------------------------------------------------ request bodies
    def _require_content_type(self, expected: str) -> None:
        raw = self.headers.get("Content-Type", "")
        if not raw:
            raise ApiError(415, "缺少 Content-Type", code="bad_content_type")
        base = raw.split(";", 1)[0].strip().lower()
        if base != expected:
            raise ApiError(
                415,
                f"Content-Type 必须是 {expected}",
                code="bad_content_type",
            )

    def _read_body(self, limit: int) -> bytes:
        transfer_encoding = self.headers.get("Transfer-Encoding")
        if transfer_encoding and transfer_encoding.strip().lower() != "identity":
            raise ApiError(
                411,
                "拒绝分块或其他 Transfer-Encoding；必须提供 Content-Length",
                code="chunked_not_allowed",
            )
        values = self.headers.get_all("Content-Length") or []
        if len(values) != 1:
            raise ApiError(
                411,
                "必须提供唯一的 Content-Length",
                code="length_required",
            )
        raw_length = values[0].strip()
        if not raw_length.isdigit():
            raise ApiError(400, "Content-Length 无效", code="bad_length")
        length = int(raw_length)
        if length > limit:
            raise ApiError(
                413,
                f"请求体超过上限（{limit} 字节）",
                code="body_too_large",
            )
        if length < 0:
            raise ApiError(400, "Content-Length 无效", code="bad_length")
        data = self.rfile.read(length)
        if len(data) != length:
            raise ApiError(400, "请求体被截断", code="truncated_body")
        return data

    def _read_json(self) -> Dict[str, Any]:
        self._require_content_type("application/json")
        data = self._read_body(MAX_JSON_BYTES)
        if not data:
            raise ApiError(400, "JSON 请求体为空", code="empty_json")
        try:
            value = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(400, f"非法 JSON：{exc}", code="bad_json") from exc
        if not isinstance(value, dict):
            raise ApiError(400, "JSON 顶层必须是对象", code="bad_json")
        return value

    # ------------------------------------------------------------------ /api/open
    def _validate_recipe(self, recipe: Any) -> Dict[str, Any]:
        if not isinstance(recipe, dict):
            raise ApiError(400, "recipe 必须是对象", code="bad_recipe")
        unknown = set(recipe) - _RECIPE_KEYS
        if unknown:
            raise ApiError(400, "recipe 包含未知字段", code="bad_recipe")
        preset = recipe.get("presetId")
        if not isinstance(preset, str) or not preset.strip():
            raise ApiError(400, "recipe.presetId 必须是非空字符串", code="bad_recipe")
        for key in ("filmProfile", "printProfile"):
            if key in recipe and (
                not isinstance(recipe[key], str) or not recipe[key].strip()
            ):
                raise ApiError(400, f"recipe.{key} 必须是非空字符串", code="bad_recipe")
        ranges = {
            "strength": (0.0, 1.0),
            "exposureEv": (-12.0, 12.0),
            "grainScale": (0.0, 4.0),
            "halationScale": (0.0, 4.0),
        }
        for key, (low, high) in ranges.items():
            if key not in recipe:
                continue
            value = recipe[key]
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ApiError(400, f"recipe.{key} 必须是数字", code="bad_recipe")
            if not (low <= float(value) <= high):
                raise ApiError(
                    400,
                    f"recipe.{key} 必须在 [{low:g},{high:g}] 内",
                    code="bad_recipe",
                )
        return dict(recipe)

    def _handle_open(self) -> None:
        self._require_content_type("application/octet-stream")
        header_format = self.headers.get("X-Spektra-Format", "").strip().lower()
        if header_format not in ("jpeg", "png"):
            raise ApiError(
                415,
                "X-Spektra-Format 必须是 jpeg 或 png",
                code="bad_image_format",
            )
        data = self._read_body(MAX_UPLOAD_BYTES)
        if not data:
            raise ApiError(400, "图片内容为空", code="empty_image")
        actual_format = sniff_image_format(data)
        if actual_format is None:
            raise ApiError(
                415,
                "内容不是可识别的 JPEG 或 PNG（不信任扩展名）",
                code="bad_image_format",
            )
        if actual_format != header_format:
            raise ApiError(
                415,
                f"X-Spektra-Format 为 {header_format}，但内容是 {actual_format}",
                code="bad_image_format",
            )
        dimensions = image_dimensions(data, actual_format)
        if dimensions is None:
            raise ApiError(415, "无法从图片头读取尺寸", code="bad_image_header")
        width, height = dimensions
        if width * height > MAX_PIXELS:
            raise ApiError(
                413,
                f"图片约 {width * height / 1_000_000:.1f} MP，超过 50 MP 上限",
                code="too_many_pixels",
            )

        suffix = ".png" if actual_format == "png" else ".jpg"
        with self.app._open_lock:
            # A new source invalidates every older preview before the native
            # worker even sees the new image.
            with self.app.state.lock:
                self.app.state.epoch += 1
                self.app.state.generation += 1
                self.app.state.preview_token = None
                self.app.state.has_image = False
                self.app._clear_media()
                epoch = self.app.state.epoch
                generation = self.app.state.generation

            input_name = self.app._write_upload(data, suffix)
            try:
                response = self.app.worker.open_image(
                    input_name, preview_name="source.png"
                )
            except WorkerError as exc:
                try:
                    (self.app.session_dir / input_name).unlink(missing_ok=True)
                except OSError:
                    pass
                self._raise_worker_error(exc)
                return
            except Exception:
                try:
                    (self.app.session_dir / input_name).unlink(missing_ok=True)
                except OSError:
                    pass
                raise ApiError(500, "打开图片失败", code="open_failed")

            with self.app.state.lock:
                stale = self.app.state.epoch != epoch
            if stale:
                try:
                    (self.app.session_dir / input_name).unlink(missing_ok=True)
                    (self.app.session_dir / "source.png").unlink(missing_ok=True)
                except OSError:
                    pass
                raise ApiError(409, "会话已更换，打开结果已丢弃", code="stale_session")

            self.app.register_media("source.png", "image/png", generation)
            with self.app.state.lock:
                self.app.state.has_image = True
            payload = {
                "ok": True,
                "width": int(response.get("width", 0)),
                "height": int(response.get("height", 0)),
                "previewWidth": int(response.get("previewWidth", 0)),
                "previewHeight": int(response.get("previewHeight", 0)),
                "orientation": int(response.get("orientation", 1)),
                "previewUrl": self.app.media_url("source.png"),
                "generation": generation,
                "format": actual_format,
                "bytes": len(data),
            }
            self._send_json(HTTPStatus.OK, payload)

    # ------------------------------------------------------------------ /api/preview
    def _handle_preview(self) -> None:
        request = self._read_json()
        unknown = set(request) - {"generation", "recipe"}
        if unknown:
            raise ApiError(400, "预览请求包含未知字段", code="bad_request")
        generation = request.get("generation")
        if isinstance(generation, bool) or not isinstance(generation, int):
            raise ApiError(400, "generation 必须是整数", code="bad_generation")
        if generation < 1 or generation > 2**31 - 1:
            raise ApiError(400, "generation 必须从 1 开始", code="bad_generation")
        recipe = self._validate_recipe(request.get("recipe"))
        with self.app.state.lock:
            has_image = self.app.state.has_image
        if not has_image:
            raise ApiError(409, "尚未打开图片", code="no_open_image")

        token = self.app._begin_preview(generation)
        with self.app.state.lock:
            epoch = self.app.state.epoch
        try:
            response = self.app.worker.preview(recipe, generation)
        except StaleGenerationError as exc:
            self.app._finish_preview(token, epoch)
            latest = getattr(exc, "latest", None)
            current = latest if isinstance(latest, int) else self.app.state.generation
            raise ApiError(
                409,
                str(exc),
                code="stale_generation",
                extra={"generation": generation, "currentGeneration": current},
            ) from exc
        except WorkerError as exc:
            self.app._finish_preview(token, epoch)
            self._raise_worker_error(exc)
            return
        except Exception:
            self.app._finish_preview(token, epoch)
            raise ApiError(500, "预览渲染失败", code="preview_failed")

        out_name = str(response.get("out") or "")
        if not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", out_name):
            self.app._finish_preview(token, epoch)
            raise ApiError(500, "native worker 返回了非法预览路径", code="preview_failed")
        path = self.app.session_dir / out_name
        try:
            exists = path.is_file()
        except OSError:
            exists = False
        if not exists:
            self.app._finish_preview(token, epoch)
            raise ApiError(500, "native worker 未生成预览文件", code="preview_failed")

        if not self.app._commit_preview(token, epoch, out_name, generation):
            # A new open superseded this preview while the native render was
            # finishing.  Never publish the old source's image.
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
            raise ApiError(
                409,
                "会话已更换，预览结果已丢弃",
                code="stale_generation",
                extra={"generation": generation},
            )
        preview_url = self.app.media_url(out_name)
        self._send_json(
            HTTPStatus.OK,
            {
                "ok": True,
                "generation": generation,
                "previewUrl": preview_url,
                "width": int(response.get("width", 0)),
                "height": int(response.get("height", 0)),
                "ms": int(response.get("ms", 0)),
            },
        )

    # ------------------------------------------------------------------ /api/export
    def _handle_export(self) -> None:
        request = self._read_json()
        unknown = set(request) - {"recipe", "format", "quality", "filename"}
        if unknown:
            raise ApiError(400, "导出请求包含未知字段", code="bad_request")
        recipe = self._validate_recipe(request.get("recipe"))
        with self.app.state.lock:
            has_image = self.app.state.has_image
        if not has_image:
            raise ApiError(409, "尚未打开图片", code="no_open_image")

        image_format = request.get("format")
        if not isinstance(image_format, str) or image_format not in ("jpeg", "png"):
            raise ApiError(400, "format 必须是 jpeg 或 png", code="bad_format")

        quality = request.get("quality", 92)
        if isinstance(quality, bool) or not isinstance(quality, int):
            raise ApiError(400, "quality 必须是 1..100 的整数", code="bad_quality")
        if not 1 <= quality <= 100:
            raise ApiError(400, "quality 必须是 1..100 的整数", code="bad_quality")

        requested_name = request.get("filename", "")
        if requested_name is None:
            requested_name = ""
        if not isinstance(requested_name, str):
            raise ApiError(400, "filename 必须是字符串", code="bad_filename")
        if len(requested_name) > 4096:
            raise ApiError(400, "filename 过长", code="bad_filename")

        download_name = sanitize_download_filename(requested_name, image_format)
        suffix = ".png" if image_format == "png" else ".jpg"
        out_name = self.app._new_session_file("export", suffix)
        try:
            response = self.app.worker.export(
                recipe, out_name, quality=quality
            )
        except WorkerError as exc:
            try:
                (self.app.session_dir / out_name).unlink(missing_ok=True)
            except OSError:
                pass
            self._raise_worker_error(exc)
            return
        except Exception:
            try:
                (self.app.session_dir / out_name).unlink(missing_ok=True)
            except OSError:
                pass
            raise ApiError(500, "导出失败", code="export_failed")

        path = self.app.session_dir / out_name
        try:
            raw = path.read_bytes()
        except OSError:
            raise ApiError(500, "导出文件读取失败", code="export_failed")
        finally:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass

        actual_format = sniff_image_format(raw)
        expected = "png" if image_format == "png" else "jpeg"
        if actual_format != expected:
            raise ApiError(500, "导出文件格式与请求不符", code="export_failed")
        dimensions = image_dimensions(raw, actual_format)
        if dimensions is None:
            raise ApiError(500, "导出文件尺寸不可读", code="export_failed")

        width = int(response.get("width", dimensions[0]))
        height = int(response.get("height", dimensions[1]))
        elapsed_ms = int(response.get("ms", 0))
        if (width, height) != dimensions:
            # The bytes are authoritative; native metadata disagreement is a
            # server-side defect, not a user error.
            width, height = dimensions

        stamp = time.strftime("%Y%m%d_%H%M%S", time.localtime())
        fallback = f"Spektrafilm_{stamp}{suffix}"
        headers = {
            "Content-Disposition": _content_disposition(download_name, fallback),
            "X-Spektra-Width": str(width),
            "X-Spektra-Height": str(height),
            "X-Spektra-Ms": str(elapsed_ms),
        }
        mime = "image/png" if expected == "png" else "image/jpeg"
        self._send_bytes(HTTPStatus.OK, raw, mime, headers)

    # ------------------------------------------------------------------ /media
    def _serve_media(self, method: str, path: str, query: str) -> None:
        if method not in ("GET", "HEAD"):
            raise ApiError(405, "不支持的请求方法", code="method_not_allowed",
                           headers={"Allow": "GET, HEAD"})
        params = urllib.parse.parse_qs(query, keep_blank_values=True)
        tokens = params.get("token", [])
        if len(tokens) != 1:
            raise ApiError(401, "缺少媒体访问令牌", code="missing_token")
        if not self.app.check_token(tokens[0]):
            raise ApiError(403, "媒体访问令牌无效", code="bad_token")

        raw_name = path[len("/media/") :]
        try:
            name = urllib.parse.unquote(raw_name, errors="strict")
        except UnicodeDecodeError:
            raise ApiError(404, "资源不存在", code="not_found")
        if (
            not name
            or name != os.path.basename(name)
            or "/" in name
            or "\\" in name
            or "\x00" in name
            or name in (".", "..")
        ):
            raise ApiError(404, "资源不存在", code="not_found")

        with self.app.state.lock:
            entry = self.app.state.media.get(name)
        if entry is None:
            raise ApiError(404, "资源不存在", code="not_found")
        root = self.app.session_dir.resolve()
        try:
            resolved = entry.path.resolve(strict=True)
        except (OSError, RuntimeError):
            raise ApiError(404, "资源不存在", code="not_found")
        if resolved.parent != root or not resolved.is_file():
            raise ApiError(404, "资源不存在", code="not_found")
        try:
            raw = resolved.read_bytes()
        except OSError:
            raise ApiError(404, "资源不存在", code="not_found")
        actual_format = sniff_image_format(raw)
        expected = "png" if entry.mime == "image/png" else "jpeg"
        if actual_format != expected:
            raise ApiError(415, "会话文件类型不符", code="bad_media_type")
        body = b"" if method == "HEAD" else raw
        self._send_bytes(
            HTTPStatus.OK,
            body,
            entry.mime,
            {"Content-Disposition": "inline"},
            content_length=len(raw),
        )

    # ------------------------------------------------------------------ errors
    def _raise_worker_error(self, exc: WorkerError) -> None:
        status = 500
        code = getattr(exc, "code", "worker_error")
        if isinstance(exc, WorkerTimeoutError):
            status = 504
            message = "native 渲染超时，工作进程已清理"
        elif isinstance(exc, WorkerProtocolError):
            status = 500
            message = "native worker 协议错误"
        elif isinstance(exc, WorkerRequestError):
            status = 422
            message = f"native 拒绝了请求：{exc}"
        elif isinstance(exc, StaleGenerationError):
            status = 409
            message = str(exc)
        else:
            message = "native worker 不可用，请重新启动桌面编辑器"
        raise ApiError(
            status,
            message,
            code=code,
            extra=(
                {"generation": exc.generation, "currentGeneration": exc.latest}
                if isinstance(exc, StaleGenerationError)
                else None
            ),
        )

    def _send_api_error(self, exc: ApiError) -> None:
        payload = {
            "ok": False,
            "error": exc.message,
            "code": exc.code,
        }
        payload.update(exc.extra)
        try:
            self._send_json(exc.status, payload, extra_headers=exc.headers)
        except (BrokenPipeError, ConnectionResetError, OSError):
            self.close_connection = True

    def send_error(self, code: int, message: Optional[str] = None, explain: Optional[str] = None) -> None:
        # BaseHTTPRequestHandler calls this for malformed request lines/headers.
        # Keep the response Chinese JSON and security headers.  A malformed
        # request line leaves request_version at HTTP/0.9, which would make
        # send_response suppress the status line and headers entirely.
        if getattr(self, "request_version", "HTTP/0.9") == "HTTP/0.9":
            self.request_version = "HTTP/1.1"
        status = int(code) if isinstance(code, int) else 400
        text = _STATUS_CHINESE.get(status, "请求失败")
        self._send_api_error(ApiError(status, text, code=f"http_{status}"))

    # ------------------------------------------------------------------ response writers
    def _security_headers(self) -> Dict[str, str]:
        return {
            "Content-Security-Policy": "default-src 'self'",
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "X-Frame-Options": "DENY",
            "Cache-Control": "no-store",
            "Connection": "close",
        }

    def _start_response(self, status: int, content_type: str, content_length: int,
                        extra_headers: Optional[Dict[str, str]] = None) -> None:
        self.close_connection = True
        self.send_response(int(status))
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(int(content_length)))
        for key, value in self._security_headers().items():
            self.send_header(key, value)
        for key, value in (extra_headers or {}).items():
            if key.lower().startswith("access-control-"):
                # Defensive: never emit a CORS permission, even if a caller
                # passes one by mistake.
                continue
            self.send_header(key, value)
        self.end_headers()

    def _send_json(self, status: int, payload: Dict[str, Any],
                   extra_headers: Optional[Dict[str, str]] = None) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        self._start_response(
            status,
            "application/json; charset=utf-8",
            len(body),
            extra_headers,
        )
        try:
            if self.command != "HEAD":
                self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_bytes(self, status: int, body: bytes, content_type: str,
                    extra_headers: Optional[Dict[str, str]] = None,
                    *, content_length: Optional[int] = None) -> None:
        length = len(body) if content_length is None else int(content_length)
        self._start_response(status, content_type, length, extra_headers)
        try:
            if self.command != "HEAD":
                self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
