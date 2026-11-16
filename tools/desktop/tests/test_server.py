# -*- coding: utf-8 -*-
"""HTTP server tests: auth, Host/Origin, CORS, traversal, limits, cleanup. GPLv3."""

from __future__ import annotations

import base64
import http.client
import json
import os
import shutil
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from pathlib import Path

DESKTOP = Path(__file__).resolve().parents[1]
if str(DESKTOP) not in sys.path:
    sys.path.insert(0, str(DESKTOP))

import server  # noqa: E402

# 2x2 red PNG and JPEG, generated once with zlib + macOS sips.
PNG_2X2 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGM4EaABRAwQCgAljgUBqTNxyAAAAABJRU5ErkJggg=="
)
JPEG_2X2 = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAAqADAAQAAAABAAAAAgAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAAgACAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A87ooor+Xz+/D/9k="
)

CATALOG = {
    "version": 1,
    "presets": [{"id": "demo", "name": "演示", "nameEn": "Demo"}],
    "stocks": [],
    "films": [],
    "prints": [],
    "groups": [],
    "presetGroups": [],
    "counts": {"presets": 1, "stocks": 0, "films": 0, "prints": 0},
}


class FakeWorker:
    """Small in-process stand-in for the native worker used by HTTP tests."""

    def __init__(self, worker_path, assets_dir, session_dir, *, timeout=180.0):
        self.session_dir = Path(session_dir)
        self.timeout = timeout
        self.started = False
        self.stopped = False
        self.slow_started = threading.Event()

    def start(self):
        self.started = True
        return self

    def stop(self):
        self.stopped = True

    def open_image(self, input_name, preview_name="source.png"):
        (self.session_dir / preview_name).write_bytes(PNG_2X2)
        return {
            "ok": True,
            "width": 2,
            "height": 2,
            "previewWidth": 2,
            "previewHeight": 2,
            "orientation": 1,
        }

    def preview(self, recipe, generation, out_name=None):
        preset = recipe.get("presetId")
        if preset == "slow":
            self.slow_started.set()
            time.sleep(0.5)
        name = out_name or f"preview-{generation}.png"
        (self.session_dir / name).write_bytes(PNG_2X2)
        return {"ok": True, "width": 2, "height": 2, "ms": 4, "out": name}

    def export(self, recipe, out_name, *, quality=92):
        path = self.session_dir / out_name
        path.write_bytes(JPEG_2X2 if out_name.endswith(".jpg") else PNG_2X2)
        return {
            "ok": True,
            "path": out_name,
            "format": "jpeg" if out_name.endswith(".jpg") else "png",
            "width": 2,
            "height": 2,
            "bytes": path.stat().st_size,
            "ms": 9,
        }


class ServerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.srv = server.LocalServer(
            port=0,
            token="test-token-0123456789",
            catalog_data=CATALOG,
            worker_factory=FakeWorker,
        )
        self.srv.start()
        self.base = f"http://127.0.0.1:{self.srv.port}"
        self.generation = 0

    def tearDown(self) -> None:
        if getattr(self, "srv", None) is not None:
            self.srv.stop()

    # ------------------------------------------------------------------ helpers
    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        *,
        token: str | None = "auto",
        timeout: float = 10.0,
    ) -> tuple[int, dict[str, str], bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", self.srv.port, timeout=timeout)
        request_headers = dict(headers or {})
        if token == "auto":
            request_headers["X-Spektra-Token"] = self.srv.token
        elif token:
            request_headers["X-Spektra-Token"] = token
        conn.request(method, path, body=body, headers=request_headers)
        response = conn.getresponse()
        data = response.read()
        response_headers = {k.lower(): v for k, v in response.getheaders()}
        status = response.status
        conn.close()
        return status, response_headers, data

    def raw(self, payload: bytes) -> tuple[int, dict[str, str], bytes]:
        sock = socket.create_connection(("127.0.0.1", self.srv.port), timeout=10)
        try:
            sock.sendall(payload)
            chunks = []
            while True:
                piece = sock.recv(65536)
                if not piece:
                    break
                chunks.append(piece)
        finally:
            sock.close()
        data = b"".join(chunks)
        head, _, body = data.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split()[1])
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.strip().lower()] = value.strip()
        return status, headers, body

    def open_png(self) -> dict:
        status, _, body = self.request(
            "POST",
            "/api/open",
            PNG_2X2,
            {
                "Content-Type": "application/octet-stream",
                "X-Spektra-Format": "png",
            },
        )
        self.assertEqual(200, status, body)
        payload = json.loads(body)
        self.generation = payload["generation"]
        return payload

    def json_request(self, method: str, path: str, payload: dict) -> tuple[int, dict, bytes]:
        return self.request(
            method,
            path,
            json.dumps(payload).encode("utf-8"),
            {"Content-Type": "application/json"},
        )

    def assert_chinese_json(self, body: bytes) -> dict:
        payload = json.loads(body)
        self.assertFalse(payload.get("ok"))
        self.assertTrue(payload.get("error"))
        # A Chinese error string, not an English traceback or empty body.
        self.assertTrue(any("\u4e00" <= ch <= "\u9fff" for ch in payload["error"]))
        return payload


class CatalogAndSecurityTests(ServerTestCase):
    def test_catalog_requires_token_and_returns_json(self) -> None:
        status, headers, body = self.request("GET", "/api/catalog", token="")
        self.assertEqual(401, status)
        self.assert_chinese_json(body)
        status, headers, body = self.request("GET", "/api/catalog", token="wrong")
        self.assertEqual(403, status)
        self.assert_chinese_json(body)
        status, headers, body = self.request("GET", "/api/catalog")
        self.assertEqual(200, status)
        payload = json.loads(body)
        self.assertTrue(payload["ok"])
        self.assertEqual("演示", payload["presets"][0]["name"])
        self.assertEqual(CATALOG["counts"], payload["counts"])

    def test_health_and_unknown_api(self) -> None:
        status, _, body = self.request("GET", "/api/health")
        self.assertEqual(200, status)
        self.assertEqual("ok", json.loads(body)["status"])
        status, _, body = self.request("GET", "/api/nope")
        self.assertEqual(404, status)
        self.assert_chinese_json(body)

    def test_host_header_must_be_loopback(self) -> None:
        status, _, body = self.raw(
            f"GET /api/catalog HTTP/1.1\r\nHost: evil.example\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\nConnection: close\r\n\r\n".encode()
        )
        self.assertEqual(403, status)
        self.assert_chinese_json(body)
        status, _, _ = self.raw(
            f"GET /api/catalog HTTP/1.1\r\nX-Spektra-Token: {self.srv.token}\r\n"
            "Connection: close\r\n\r\n".encode()
        )
        self.assertEqual(403, status)
        # The real Host is accepted.
        status, _, _ = self.raw(
            f"GET /api/catalog HTTP/1.1\r\nHost: 127.0.0.1:{self.srv.port}\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\nConnection: close\r\n\r\n".encode()
        )
        self.assertEqual(200, status)

    def test_origin_only_same_origin(self) -> None:
        for origin in (
            f"http://127.0.0.1:{self.srv.port}",
            f"http://localhost:{self.srv.port}",
        ):
            status, _, _ = self.request(
                "GET", "/api/catalog", headers={"Origin": origin}
            )
            self.assertEqual(200, status, origin)
        for origin in ("null", "http://evil.example", "https://127.0.0.1:1"):
            status, _, body = self.request(
                "GET", "/api/catalog", headers={"Origin": origin}
            )
            self.assertEqual(403, status, origin)
            self.assert_chinese_json(body)

    def test_no_cors_and_secure_csp(self) -> None:
        status, headers, _ = self.request("GET", "/api/catalog")
        self.assertEqual(200, status)
        self.assertFalse(
            [key for key in headers if key.startswith("access-control-")],
            headers,
        )
        self.assertEqual("default-src 'self'", headers["content-security-policy"])
        self.assertEqual("nosniff", headers["x-content-type-options"])
        self.assertNotIn("http://", headers["content-security-policy"])

    def test_preflight_is_not_allowed_and_has_no_cors(self) -> None:
        status, headers, body = self.request(
            "OPTIONS",
            "/api/open",
            headers={
                "Origin": f"http://127.0.0.1:{self.srv.port}",
                "Access-Control-Request-Method": "POST",
            },
        )
        self.assertEqual(405, status)
        self.assertFalse([k for k in headers if k.startswith("access-control-")])
        self.assert_chinese_json(body)

    def test_query_parameters_are_rejected_on_api(self) -> None:
        status, _, body = self.request("GET", "/api/catalog?token=x")
        self.assertEqual(400, status)
        self.assert_chinese_json(body)

    def test_unknown_non_api_path_is_still_404(self) -> None:
        status, headers, body = self.request("GET", "/server.py")
        self.assertEqual(404, status)
        self.assertEqual("default-src 'self'", headers["content-security-policy"])
        self.assert_chinese_json(body)


class StaticWebTests(ServerTestCase):
    """The four fixed browser assets; every other path stays a 404."""

    def test_root_serves_the_chinese_editor_without_a_token(self) -> None:
        status, headers, body = self.request("GET", "/", token=None)
        self.assertEqual(200, status)
        self.assertTrue(headers["content-type"].startswith("text/html"))
        self.assertIn("charset=utf-8", headers["content-type"].lower())
        self.assertEqual("default-src 'self'", headers["content-security-policy"])
        self.assertEqual("nosniff", headers["x-content-type-options"])
        self.assertEqual("no-store", headers["cache-control"])
        self.assertFalse([key for key in headers if key.startswith("access-control-")])
        html = body.decode("utf-8")
        self.assertIn("光谱胶片", html)
        self.assertIn("此 Mac 本地处理", html)
        self.assertIn('href="style.css"', html)
        self.assertIn('src="app.js"', html)
        self.assertIn("Film modeling powered by spektrafilm", html)
        # No external/CDN/font/script dependency can slip into the page.
        self.assertNotIn("http://", html)
        self.assertNotIn("https://", html)
        self.assertNotIn("//cdn", html)

    def test_index_alias_is_the_same_document(self) -> None:
        root_status, root_headers, root_body = self.request("GET", "/", token=None)
        index_status, index_headers, index_body = self.request(
            "GET", "/index.html?token=ignored", token=None
        )
        self.assertEqual(200, root_status)
        self.assertEqual(200, index_status)
        self.assertEqual(root_body, index_body)
        self.assertEqual(root_headers["content-type"], index_headers["content-type"])

    def test_css_and_js_have_correct_mime_and_no_cache(self) -> None:
        expected = {
            "/style.css": "text/css",
            "/app.js": "text/javascript",
        }
        for path, mime in expected.items():
            with self.subTest(path=path):
                status, headers, body = self.request("GET", path, token=None)
                self.assertEqual(200, status)
                self.assertTrue(headers["content-type"].startswith(mime))
                self.assertIn("charset=utf-8", headers["content-type"].lower())
                self.assertEqual("no-store", headers["cache-control"])
                self.assertEqual("default-src 'self'", headers["content-security-policy"])
                self.assertGreater(len(body), 0)
        status, _, js = self.request("GET", "/app.js", token=None)
        self.assertEqual(200, status)
        self.assertNotIn(b".innerHTML", js)
        self.assertNotIn(b"eval(", js)

    def test_head_static_has_no_body_but_real_content_length(self) -> None:
        for path in ("/", "/index.html", "/style.css", "/app.js"):
            with self.subTest(path=path):
                get_status, get_headers, get_body = self.request(
                    "GET", path, token=None
                )
                head_status, head_headers, head_body = self.request(
                    "HEAD", path, token=None
                )
                self.assertEqual(200, get_status)
                self.assertEqual(200, head_status)
                self.assertEqual(b"", head_body)
                self.assertEqual(
                    int(get_headers["content-length"]),
                    int(head_headers["content-length"]),
                )
                self.assertEqual(len(get_body), int(head_headers["content-length"]))

    def test_other_paths_and_traversal_are_404(self) -> None:
        paths = (
            "/server.py",
            "/catalog.py",
            "/run.py",
            "/worker.py",
            "/web/",
            "/web/app.js",
            "/web/index.html",
            "/../server.py",
            "/%2e%2e/server.py",
            "/index.html/",
            "/style.css/",
            "/app.js/",
            "/index.html/../app.js",
            "/.git/config",
        )
        for path in paths:
            with self.subTest(path=path):
                status, headers, body = self.request("GET", path, token=None)
                self.assertEqual(404, status)
                self.assertEqual("default-src 'self'", headers["content-security-policy"])
                payload = self.assert_chinese_json(body)
                self.assertEqual("not_found", payload["code"])

    def test_static_assets_are_get_or_head_only(self) -> None:
        status, headers, body = self.request("POST", "/app.js", token=None)
        self.assertEqual(405, status)
        self.assertFalse([key for key in headers if key.startswith("access-control-")])
        self.assert_chinese_json(body)

    def test_static_host_header_still_must_be_loopback(self) -> None:
        status, _, body = self.raw(
            b"GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"
        )
        self.assertEqual(403, status)
        self.assert_chinese_json(body)

    def test_static_does_not_leak_files_outside_the_web_root(self) -> None:
        # A web root with only the expected files must not resolve siblings or
        # symlinks.  The whitelist lookup is the primary defence; this checks
        # the resolved-parent guard too.
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "web"
            root.mkdir()
            for name in ("index.html", "style.css", "app.js"):
                (root / name).write_text(f"/* {name} */", encoding="utf-8")
            outside = Path(tmp) / "secret.txt"
            outside.write_text("secret", encoding="utf-8")
            try:
                (root / "app.js").unlink()
                (root / "app.js").symlink_to(outside)
            except OSError:
                self.skipTest("symlinks are unavailable on this filesystem")
            srv = server.LocalServer(
                port=0,
                token="static-token",
                catalog_data=CATALOG,
                worker_factory=FakeWorker,
                web_dir=root,
            )
            srv.start()
            try:
                conn = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=10)
                conn.request("GET", "/app.js")
                response = conn.getresponse()
                body = response.read()
                status = response.status
                conn.close()
                self.assertEqual(404, status)
                self.assertNotIn(b"secret", body)
            finally:
                srv.stop()


class OpenEndpointTests(ServerTestCase):
    def test_open_png_returns_dimensions_and_media_url(self) -> None:
        payload = self.open_png()
        self.assertTrue(payload["ok"])
        self.assertEqual(2, payload["width"])
        self.assertEqual(2, payload["height"])
        self.assertEqual(1, payload["orientation"])
        self.assertEqual("png", payload["format"])
        self.assertGreaterEqual(payload["generation"], 1)
        self.assertTrue(payload["previewUrl"].startswith("/media/source.png?token="))

    def test_open_rejects_wrong_content_type(self) -> None:
        status, _, body = self.request(
            "POST",
            "/api/open",
            PNG_2X2,
            {"Content-Type": "text/plain", "X-Spektra-Format": "png"},
        )
        self.assertEqual(415, status)
        self.assert_chinese_json(body)

    def test_open_rejects_bad_or_mismatched_format_header(self) -> None:
        status, _, body = self.request(
            "POST",
            "/api/open",
            PNG_2X2,
            {"Content-Type": "application/octet-stream", "X-Spektra-Format": "gif"},
        )
        self.assertEqual(415, status)
        self.assert_chinese_json(body)
        status, _, body = self.request(
            "POST",
            "/api/open",
            PNG_2X2,
            {"Content-Type": "application/octet-stream", "X-Spektra-Format": "jpeg"},
        )
        self.assertEqual(415, status)
        self.assert_chinese_json(body)

    def test_open_rejects_non_image_bytes(self) -> None:
        status, _, body = self.request(
            "POST",
            "/api/open",
            b"GIF89a not an accepted image",
            {"Content-Type": "application/octet-stream", "X-Spektra-Format": "png"},
        )
        self.assertEqual(415, status)
        self.assert_chinese_json(body)

    def test_open_rejects_over_50_megapixels(self) -> None:
        huge = (
            b"\x89PNG\r\n\x1a\n"
            + b"\x00\x00\x00\r"
            + b"IHDR"
            + struct.pack(">II", 10000, 10000)
            + b"\x08\x02\x00\x00\x00"
            + b"\x00\x00\x00\x00"
        )
        status, _, body = self.request(
            "POST",
            "/api/open",
            huge,
            {"Content-Type": "application/octet-stream", "X-Spektra-Format": "png"},
        )
        self.assertEqual(413, status)
        self.assertIn("MP", self.assert_chinese_json(body)["error"])

    def test_open_rejects_oversized_content_length(self) -> None:
        request = (
            f"POST /api/open HTTP/1.1\r\nHost: 127.0.0.1:{self.srv.port}\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\n"
            "Content-Type: application/octet-stream\r\n"
            "X-Spektra-Format: png\r\n"
            f"Content-Length: {server.MAX_UPLOAD_BYTES + 1}\r\n"
            "Connection: close\r\n\r\n"
        ).encode()
        status, _, body = self.raw(request)
        self.assertEqual(413, status)
        self.assert_chinese_json(body)

    def test_open_rejects_chunked_transfer(self) -> None:
        request = (
            f"POST /api/open HTTP/1.1\r\nHost: 127.0.0.1:{self.srv.port}\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\n"
            "Content-Type: application/octet-stream\r\n"
            "X-Spektra-Format: png\r\n"
            "Transfer-Encoding: chunked\r\n"
            "Connection: close\r\n\r\n0\r\n\r\n"
        ).encode()
        status, _, body = self.raw(request)
        self.assertEqual(411, status)
        self.assert_chinese_json(body)

    def test_open_rejects_missing_content_length(self) -> None:
        request = (
            f"POST /api/open HTTP/1.1\r\nHost: 127.0.0.1:{self.srv.port}\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\n"
            "Content-Type: application/octet-stream\r\n"
            "X-Spektra-Format: png\r\n"
            "Connection: close\r\n\r\n"
        ).encode()
        status, _, body = self.raw(request)
        self.assertEqual(411, status)
        self.assert_chinese_json(body)


class PreviewExportTests(ServerTestCase):
    def test_preview_and_old_generation_conflict(self) -> None:
        opened = self.open_png()
        recipe = {"presetId": "demo", "strength": 0.7}
        status, _, body = self.json_request(
            "POST",
            "/api/preview",
            {"generation": opened["generation"], "recipe": recipe},
        )
        self.assertEqual(200, status, body)
        payload = json.loads(body)
        self.assertEqual(2, payload["width"])
        self.assertEqual(2, payload["height"])
        self.assertGreaterEqual(payload["ms"], 0)
        self.assertTrue(payload["previewUrl"].startswith("/media/preview-"))

        status, _, body = self.json_request(
            "POST",
            "/api/preview",
            {"generation": opened["generation"] + 1, "recipe": recipe},
        )
        self.assertEqual(200, status, body)
        status, _, body = self.json_request(
            "POST",
            "/api/preview",
            {"generation": opened["generation"], "recipe": recipe},
        )
        self.assertEqual(409, status)
        stale = json.loads(body)
        self.assertEqual("stale_generation", stale["code"])
        self.assert_chinese_json(body)

    def test_concurrent_newer_preview_wins_and_old_is_409(self) -> None:
        opened = self.open_png()
        results: dict[str, tuple[int, dict[str, str], bytes]] = {}

        def old_preview() -> None:
            results["old"] = self.json_request(
                "POST",
                "/api/preview",
                {
                    "generation": opened["generation"],
                    "recipe": {"presetId": "slow"},
                },
            )

        thread = threading.Thread(target=old_preview)
        thread.start()
        self.assertTrue(self.srv.worker.slow_started.wait(2.0))
        status, _, body = self.json_request(
            "POST",
            "/api/preview",
            {
                "generation": opened["generation"] + 1,
                "recipe": {"presetId": "demo"},
            },
        )
        self.assertEqual(200, status, body)
        thread.join(timeout=5.0)
        self.assertEqual(409, results["old"][0])
        self.assertEqual("stale_generation", json.loads(results["old"][2])["code"])
        # The superseded preview file was deleted, not published under a new name.
        self.assertEqual([], list(self.srv.session_dir.glob("preview-1*.png")))

    def test_preview_validation_and_missing_open(self) -> None:
        status, _, body = self.json_request(
            "POST", "/api/preview", {"generation": 1, "recipe": {"presetId": "demo"}}
        )
        self.assertEqual(409, status)
        self.assertEqual("no_open_image", json.loads(body)["code"])

        self.open_png()
        status, _, body = self.json_request(
            "POST", "/api/preview", {"generation": 0, "recipe": {"presetId": "demo"}}
        )
        self.assertEqual(400, status)
        self.assert_chinese_json(body)
        status, _, body = self.json_request(
            "POST",
            "/api/preview",
            {"generation": 1, "recipe": {"presetId": "demo", "bogus": 1}},
        )
        self.assertEqual(400, status)
        self.assert_chinese_json(body)
        status, _, body = self.request(
            "POST",
            "/api/preview",
            b"{not json",
            {"Content-Type": "application/json"},
        )
        self.assertEqual(400, status)
        self.assert_chinese_json(body)

    def test_export_jpeg_binary_and_unicode_filename(self) -> None:
        self.open_png()
        status, headers, body = self.json_request(
            "POST",
            "/api/export",
            {
                "recipe": {"presetId": "demo"},
                "format": "jpeg",
                "quality": 88,
                "filename": "../中文 照片<>.jpg",
            },
        )
        self.assertEqual(200, status, body)
        self.assertEqual("image/jpeg", headers["content-type"])
        self.assertTrue(body.startswith(b"\xff\xd8\xff"))
        self.assertEqual("2", headers["x-spektra-width"])
        self.assertEqual("2", headers["x-spektra-height"])
        self.assertEqual("9", headers["x-spektra-ms"])
        disposition = headers["content-disposition"]
        self.assertIn("filename*=UTF-8''", disposition)
        self.assertIn("filename=\"Spektrafilm_", disposition)
        encoded = disposition.split("filename*=UTF-8''", 1)[1]
        decoded = urllib.parse.unquote(encoded)
        self.assertEqual("中文 照片jpg.jpg", decoded)
        self.assertNotIn("/", decoded)
        self.assertFalse([k for k in headers if k.startswith("access-control-")])

    def test_export_png_binary_and_does_not_overwrite_input(self) -> None:
        self.open_png()
        inputs = {
            path.name: path.read_bytes()
            for path in self.srv.session_dir.iterdir()
            if path.name.startswith("input-")
        }
        self.assertTrue(inputs)
        status, headers, body = self.json_request(
            "POST",
            "/api/export",
            {"recipe": {"presetId": "demo"}, "format": "png", "filename": "photo.png"},
        )
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["content-type"])
        self.assertTrue(body.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(urllib.parse.unquote(
            headers["content-disposition"].split("filename*=UTF-8''", 1)[1]
        ).endswith(".png"))
        for name, before in inputs.items():
            self.assertEqual(before, (self.srv.session_dir / name).read_bytes())

    def test_export_all_invalid_filename_uses_fallback(self) -> None:
        self.open_png()
        status, headers, body = self.json_request(
            "POST",
            "/api/export",
            {
                "recipe": {"presetId": "demo"},
                "format": "jpeg",
                "filename": "<>?/\\:*|..",
            },
        )
        self.assertEqual(200, status, body)
        disposition = headers["content-disposition"]
        decoded = urllib.parse.unquote(
            disposition.split("filename*=UTF-8''", 1)[1]
        )
        self.assertRegex(decoded, r"^Spektrafilm_\d{8}_\d{6}\.jpg$")
        self.assertIn("filename=\"Spektrafilm_", disposition)

    def test_export_invalid_fields_and_missing_open(self) -> None:
        status, _, body = self.json_request(
            "POST",
            "/api/export",
            {"recipe": {"presetId": "demo"}, "format": "jpeg"},
        )
        self.assertEqual(409, status)
        self.assertEqual("no_open_image", json.loads(body)["code"])
        self.open_png()
        for payload in (
            {"recipe": {"presetId": "demo"}, "format": "gif"},
            {"recipe": {"presetId": "demo"}, "format": "png", "quality": 0},
            {"recipe": {"presetId": "demo"}, "format": "png", "quality": 101},
            {"recipe": {"presetId": "demo"}, "format": "png", "filename": 5},
            {"recipe": {"presetId": ""}, "format": "png"},
        ):
            status, _, body = self.json_request("POST", "/api/export", payload)
            self.assertEqual(400, status, payload)
            self.assert_chinese_json(body)


class MediaTests(ServerTestCase):
    def test_media_requires_token_and_registered_file(self) -> None:
        self.open_png()
        url = self.request("POST", "/api/open", PNG_2X2, {
            "Content-Type": "application/octet-stream",
            "X-Spektra-Format": "png",
        })[2]
        preview_url = json.loads(url)["previewUrl"]

        status, headers, body = self.request("GET", preview_url)
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["content-type"])
        self.assertEqual(PNG_2X2, body)

        without = preview_url.split("?")[0]
        status, _, body = self.request("GET", without, token="")
        self.assertEqual(401, status)
        self.assert_chinese_json(body)
        status, _, body = self.request("GET", without + "?token=wrong", token="")
        self.assertEqual(403, status)
        self.assert_chinese_json(body)
        status, _, body = self.request("GET", "/media/nope.png?token=" + self.srv.token, token="")
        self.assertEqual(404, status)
        self.assert_chinese_json(body)

    def test_media_path_traversal_is_rejected(self) -> None:
        self.open_png()
        for name in (
            "../server.py",
            "..%2Fserver.py",
            "%2e%2e%2fserver.py",
            "..%252Fserver.py",
            ".%2e/server.py",
            "sub/dir.png",
        ):
            status, _, body = self.request(
                "GET", f"/media/{name}?token={self.srv.token}", token=""
            )
            self.assertEqual(404, status, name)
            self.assert_chinese_json(body)

    def test_media_type_is_checked_not_the_extension(self) -> None:
        bad = self.srv.session_dir / "bad.png"
        bad.write_bytes(b"plain text pretending to be a png")
        with self.srv.state.lock:
            self.srv.state.media["bad.png"] = server.MediaEntry(
                name="bad.png", path=bad, mime="image/png"
            )
        status, _, body = self.request(
            "GET", f"/media/bad.png?token={self.srv.token}", token=""
        )
        self.assertEqual(415, status)
        self.assert_chinese_json(body)


class LimitsAndCleanupTests(ServerTestCase):
    def test_json_body_limit(self) -> None:
        request = (
            f"POST /api/preview HTTP/1.1\r\nHost: 127.0.0.1:{self.srv.port}\r\n"
            f"X-Spektra-Token: {self.srv.token}\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {server.MAX_JSON_BYTES + 1}\r\n"
            "Connection: close\r\n\r\n"
        ).encode()
        status, _, body = self.raw(request)
        self.assertEqual(413, status)
        self.assert_chinese_json(body)

    def test_stop_removes_session_and_kills_worker(self) -> None:
        self.open_png()
        session = self.srv.session_dir
        worker = self.srv.worker
        port = self.srv.port
        self.assertTrue(session.is_dir())
        self.srv.stop()
        self.assertFalse(session.exists())
        self.assertTrue(worker.stopped)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", port), timeout=0.5)
        # Idempotent.
        self.srv.stop()

    def test_malformed_request_line_is_chinese_json(self) -> None:
        status, headers, body = self.raw(b"GARBAGE\r\n\r\n")
        self.assertIn(status, (400, 501))
        self.assertEqual("default-src 'self'", headers.get("content-security-policy"))


if __name__ == "__main__":
    unittest.main()
