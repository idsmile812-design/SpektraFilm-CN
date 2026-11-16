#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Resident spektra_worker process manager. GPLv3.

The native worker (``tools/desktop/native``) is a long-lived NDJSON process: it
keeps one decoded source image and one engine instance, and answers one request
per line on stdin/stdout.  This module owns that process.

Safety properties enforced here:

* All native requests are serialised.  ``open``/``preview``/``export``/``reset``
  can never overlap inside the native engine, because the engine mutates shared
  state (the decoded source image in particular).
* Every read has a deadline.  A timeout kills the child instead of leaving a
  half-written protocol line for the next request to misread.
* stderr is drained continuously but discarded.  Native diagnostics can contain
  input paths; nothing from stderr is ever put in an exception, log line or API
  response.  Only a byte count is retained for tests/diagnostics.
* Preview requests carry a monotonic generation.  Registering a newer preview
  makes every older in-flight preview stale: the older call raises
  :class:`StaleGenerationError` and its output file is removed, so a late image
  can never be published as the current result.
* ``close``/``stop`` clean up the child (close op, EOF, TERM, KILL) and wait.

Standard library only.
"""

from __future__ import annotations

import atexit
import json
import os
import re
import select
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, Optional

__all__ = [
    "WorkerClient",
    "WorkerError",
    "WorkerUnavailableError",
    "WorkerTimeoutError",
    "WorkerCrashedError",
    "WorkerProtocolError",
    "WorkerRequestError",
    "StaleGenerationError",
    "MAX_REQUEST_BYTES",
]

MAX_REQUEST_BYTES = 1024 * 1024  # matches the native line ceiling

_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


class WorkerError(RuntimeError):
    """Base class for worker failures.  Messages are safe to show to a user."""

    code = "worker_error"


class WorkerUnavailableError(WorkerError):
    code = "worker_unavailable"


class WorkerTimeoutError(WorkerError):
    code = "worker_timeout"


class WorkerCrashedError(WorkerError):
    code = "worker_crashed"


class WorkerProtocolError(WorkerError):
    code = "worker_protocol"


class WorkerRequestError(WorkerError):
    """The native worker answered ``{"ok": false}`` for a valid request."""

    code = "worker_request"


class StaleGenerationError(WorkerError):
    code = "stale_generation"

    def __init__(self, generation: int, latest: int) -> None:
        self.generation = generation
        self.latest = latest
        super().__init__(
            f"预览代次 {generation} 已过期（当前代次 {latest}）；"
            "更新的预览请求已取代它。"
        )


class WorkerClient:
    """One resident native worker process tied to one session directory."""

    def __init__(
        self,
        worker_path: Path,
        assets_dir: Path,
        session_dir: Path,
        *,
        timeout: float = 180.0,
        startup_timeout: float = 10.0,
    ) -> None:
        self.worker_path = Path(worker_path)
        self.assets_dir = Path(assets_dir)
        self.session_dir = Path(session_dir)
        self.timeout = float(timeout)
        self.startup_timeout = float(startup_timeout)

        self._process: Optional[subprocess.Popen[bytes]] = None
        self._stderr_thread: Optional[threading.Thread] = None
        self._stderr_bytes = 0
        self._stderr_lock = threading.Lock()
        self._read_buffer = b""

        self._io_lock = threading.Lock()          # one request/response at a time
        self._state_lock = threading.Lock()       # one native operation at a time
        self._generation_lock = threading.Lock()
        self._latest_generation = 0
        self._preview_token: Optional[object] = None
        self._epoch = 0
        self._closed = False
        self._atexit_registered = False

    # ------------------------------------------------------------------ lifecycle
    def start(self) -> "WorkerClient":
        if self._closed:
            raise WorkerUnavailableError("worker 已关闭，不能重新启动")
        if self._process is not None and self._process.poll() is None:
            return self
        if self._process is not None and self._process.poll() is not None:
            self._process = None

        if not self.worker_path.is_file():
            raise WorkerUnavailableError(f"缺少 native worker：{self.worker_path}")
        if not os.access(self.worker_path, os.X_OK):
            raise WorkerUnavailableError(f"native worker 不可执行：{self.worker_path}")
        if not self.assets_dir.is_dir():
            raise WorkerUnavailableError(f"缺少引擎资源目录：{self.assets_dir}")
        if not self.session_dir.is_dir():
            raise WorkerUnavailableError(f"缺少会话目录：{self.session_dir}")

        try:
            self._process = subprocess.Popen(
                [str(self.worker_path), str(self.assets_dir), str(self.session_dir)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0,
                close_fds=True,
                start_new_session=True,
            )
        except OSError as exc:
            raise WorkerUnavailableError(f"无法启动 native worker：{exc}") from exc

        assert self._process.stderr is not None
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr,
            args=(self._process.stderr,),
            name="spektra-worker-stderr",
            daemon=True,
        )
        self._stderr_thread.start()
        if not self._atexit_registered:
            atexit.register(self.stop)
            self._atexit_registered = True

        # A worker that dies immediately (bad asset set, missing dylib) should
        # fail at startup rather than on the first user request.
        deadline = time.monotonic() + min(self.startup_timeout, 0.25)
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                code = self._process.returncode
                self._shutdown_process()
                raise WorkerUnavailableError(
                    f"native worker 启动后立即退出（退出码 {code}）"
                )
            time.sleep(0.01)
        return self

    def _drain_stderr(self, pipe: Any) -> None:
        """Read and discard native stderr.

        Never store the bytes: the native side may print a path derived from a
        photo name when it rejects an input, and an exception/log carrying that
        text would leak the file name.  A count is enough for diagnostics.
        """
        try:
            while True:
                chunk = pipe.read(8192)
                if not chunk:
                    break
                with self._stderr_lock:
                    self._stderr_bytes += len(chunk)
                    if self._stderr_bytes > 64 * 1024 * 1024:
                        self._stderr_bytes = 64 * 1024 * 1024
        except (OSError, ValueError):
            pass

    @property
    def stderr_bytes(self) -> int:
        with self._stderr_lock:
            return self._stderr_bytes

    @property
    def pid(self) -> Optional[int]:
        return self._process.pid if self._process is not None else None

    @property
    def running(self) -> bool:
        return self._process is not None and self._process.poll() is None

    # ------------------------------------------------------------------ protocol
    def _check_process(self) -> subprocess.Popen[bytes]:
        if self._closed:
            raise WorkerUnavailableError("worker 已关闭")
        if self._process is None:
            raise WorkerUnavailableError("worker 尚未启动")
        code = self._process.poll()
        if code is not None:
            self._shutdown_process()
            raise WorkerCrashedError(f"native worker 已退出（退出码 {code}）")
        return self._process

    def _write_all(self, fd: int, data: bytes) -> None:
        view = memoryview(data)
        while view:
            try:
                written = os.write(fd, view)
            except (BrokenPipeError, OSError) as exc:
                raise WorkerCrashedError("native worker 管道已关闭") from exc
            if written <= 0:
                raise WorkerCrashedError("native worker 未接受请求")
            view = view[written:]

    def _read_line(self, fd: int, deadline: float) -> bytes:
        while b"\n" not in self._read_buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WorkerTimeoutError("native worker 响应超时")
            try:
                ready, _, _ = select.select([fd], [], [], remaining)
            except (OSError, ValueError) as exc:
                raise WorkerCrashedError("native worker 管道读取失败") from exc
            if not ready:
                raise WorkerTimeoutError("native worker 响应超时")
            try:
                chunk = os.read(fd, 65536)
            except OSError as exc:
                raise WorkerCrashedError("native worker 管道读取失败") from exc
            if not chunk:
                raise WorkerCrashedError("native worker 意外关闭输出")
            self._read_buffer += chunk
            if len(self._read_buffer) > MAX_REQUEST_BYTES * 2:
                raise WorkerProtocolError("native worker 响应超过协议上限")
        line, self._read_buffer = self._read_buffer.split(b"\n", 1)
        return line.rstrip(b"\r")

    def _sanitize(self, text: str) -> str:
        if not text:
            return "native worker 请求失败"
        text = str(text).replace("\n", " ").replace("\r", " ")
        text = text.replace(str(self.session_dir), "<会话目录>")
        text = text.replace(str(self.assets_dir), "<资源目录>")
        if len(text) > 400:
            text = text[:400] + "…"
        return text

    def _decode_response(self, line: bytes) -> Dict[str, Any]:
        if not line:
            raise WorkerProtocolError("native worker 返回了空响应")
        try:
            response = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise WorkerProtocolError("native worker 返回了非法 JSON") from exc
        if not isinstance(response, dict):
            raise WorkerProtocolError("native worker 响应不是 JSON 对象")
        if response.get("ok") is not True:
            message = response.get("error")
            raise WorkerRequestError(
                self._sanitize(message if isinstance(message, str) else "")
            )
        return response

    def _request(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Write one NDJSON request and read exactly one response.

        The caller must hold ``_state_lock`` (native engine state is serialised)
        and must not hold ``_io_lock``.
        """
        process = self._check_process()
        assert process.stdin is not None and process.stdout is not None
        try:
            encoded = json.dumps(
                payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise WorkerProtocolError("请求无法编码为 JSON") from exc
        if len(encoded) > MAX_REQUEST_BYTES:
            raise WorkerProtocolError("请求超过 1 MiB 协议上限")

        with self._io_lock:
            self._check_process()
            deadline = time.monotonic() + self.timeout
            self._write_all(process.stdin.fileno(), encoded + b"\n")
            try:
                line = self._read_line(process.stdout.fileno(), deadline)
            except (WorkerTimeoutError, WorkerCrashedError, WorkerProtocolError):
                # The stream alignment is no longer trustworthy.  Kill the child;
                # the next request will get a clear "worker not running" error.
                self._shutdown_process()
                raise
            return self._decode_response(line)

    # ------------------------------------------------------------------ paths
    def _safe_name(self, name: str, *, label: str) -> str:
        if not isinstance(name, str) or not _SAFE_NAME.match(name):
            raise WorkerProtocolError(f"{label}不是安全的会话文件名")
        return name

    def _remove_session_file(self, name: str) -> None:
        if not _SAFE_NAME.match(name):
            return
        path = self.session_dir / name
        try:
            root = self.session_dir.resolve()
            if path.resolve().parent != root:
                return
            path.unlink(missing_ok=True)
        except OSError:
            pass

    # ------------------------------------------------------------------ operations
    def open_image(
        self,
        input_name: str,
        preview_name: str = "source.png",
        *,
        probe: Optional[tuple[int, int]] = None,
    ) -> Dict[str, Any]:
        """Open a source already written into the session directory."""
        input_name = self._safe_name(input_name, label="输入")
        preview_name = self._safe_name(preview_name, label="预览输出")
        request: Dict[str, Any] = {
            "op": "open",
            "path": input_name,
            "preview": preview_name,
        }
        if probe is not None:
            request["probe"] = [int(probe[0]), int(probe[1])]
        with self._state_lock:
            # Invalidate previews that were registered before this open.  They
            # must not publish an image produced from the previous source.
            with self._generation_lock:
                self._epoch += 1
                self._preview_token = None
            return self._request(request)

    def preview(
        self,
        recipe: Dict[str, Any],
        generation: int,
        out_name: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Render an interactive preview for ``generation``.

        The newest registered generation wins.  An older request either never
        starts or, if it already reached the native engine, raises
        :class:`StaleGenerationError` and its output file is deleted.
        """
        if isinstance(generation, bool) or not isinstance(generation, int):
            raise WorkerProtocolError("generation 必须是整数")
        if generation < 1:
            raise WorkerProtocolError("generation 必须从 1 开始")
        out_name = out_name or f"preview-{generation}-{uuid.uuid4().hex}.png"
        out_name = self._safe_name(out_name, label="预览输出")

        token = object()
        with self._generation_lock:
            if generation < self._latest_generation:
                raise StaleGenerationError(generation, self._latest_generation)
            if generation == self._latest_generation and self._preview_token is not None:
                raise StaleGenerationError(generation, self._latest_generation)
            self._latest_generation = generation
            self._preview_token = token
            epoch = self._epoch

        try:
            with self._state_lock:
                with self._generation_lock:
                    if self._preview_token is not token or self._epoch != epoch:
                        raise StaleGenerationError(generation, self._latest_generation)
                try:
                    response = self._request(
                        {"op": "preview", "recipe": recipe, "out": out_name}
                    )
                except StaleGenerationError:
                    raise
                except WorkerError:
                    # Native errors are real failures; only a superseded request
                    # is stale.
                    raise
                with self._generation_lock:
                    stale = self._preview_token is not token or self._epoch != epoch
                    latest = self._latest_generation
                if stale:
                    self._remove_session_file(out_name)
                    raise StaleGenerationError(generation, latest)
                response["generation"] = generation
                response["out"] = out_name
                return response
        finally:
            with self._generation_lock:
                if self._preview_token is token:
                    self._preview_token = None

    def export(
        self,
        recipe: Dict[str, Any],
        out_name: str,
        *,
        quality: int = 92,
    ) -> Dict[str, Any]:
        """Render the full-resolution image.  Serialised with open/preview."""
        out_name = self._safe_name(out_name, label="导出输出")
        if isinstance(quality, bool) or not isinstance(quality, int):
            raise WorkerProtocolError("quality 必须是整数")
        if not 1 <= quality <= 100:
            raise WorkerProtocolError("quality 必须在 1..100")
        with self._state_lock:
            return self._request(
                {
                    "op": "export",
                    "recipe": recipe,
                    "out": out_name,
                    "quality": quality,
                }
            )

    def reset(self) -> Dict[str, Any]:
        with self._state_lock:
            with self._generation_lock:
                self._epoch += 1
                self._preview_token = None
            return self._request({"op": "reset"})

    def close(self) -> None:
        """Ask the native process to release its engine, then stop it."""
        if self._process is None:
            self._closed = True
            return
        try:
            if self.running:
                with self._state_lock:
                    if self.running:
                        # Short dedicated timeout: close must not hang shutdown.
                        previous = self.timeout
                        self.timeout = min(previous, 5.0)
                        try:
                            self._request({"op": "close"})
                        finally:
                            self.timeout = previous
        except WorkerError:
            pass
        finally:
            self._shutdown_process()

    def stop(self) -> None:
        """Idempotent cleanup used by the server and ``atexit``.

        Wait at most a second for an in-flight native operation (an export can
        legitimately run for minutes).  If it is still busy, kill the child:
        shutdown must never hang indefinitely behind a render.
        """
        if self._process is not None:
            acquired = False
            try:
                acquired = self._state_lock.acquire(timeout=1.0)
            except (OSError, ValueError):  # pragma: no cover - defensive
                acquired = False
            if acquired:
                try:
                    if self.running:
                        previous = self.timeout
                        self.timeout = min(previous, 5.0)
                        try:
                            self._request({"op": "close"})
                        except Exception:
                            pass
                        finally:
                            self.timeout = previous
                finally:
                    self._state_lock.release()
            self._shutdown_process()
        self._closed = True

    def _shutdown_process(self) -> None:
        process = self._process
        self._process = None
        if process is None:
            return
        # Closing stdin gives the native loop a graceful EOF first.
        try:
            if process.stdin is not None and not process.stdin.closed:
                process.stdin.close()
        except OSError:
            pass
        try:
            process.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            try:
                process.terminate()
            except OSError:
                pass
            try:
                process.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                try:
                    process.kill()
                except OSError:
                    pass
                try:
                    process.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
        for stream in (process.stdout, process.stderr):
            try:
                if stream is not None and not stream.closed:
                    stream.close()
            except OSError:
                pass

    def __enter__(self) -> "WorkerClient":
        return self.start()

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.stop()

    def __del__(self) -> None:  # pragma: no cover - best-effort safety net
        try:
            self._shutdown_process()
        except Exception:
            pass
