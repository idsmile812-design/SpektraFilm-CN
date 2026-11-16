# -*- coding: utf-8 -*-
"""Real end-to-end test: build (only if missing), native renders, HTTP, sips. GPLv3.

No mock replaces the native renderer here.  The fixtures are a standard-library
PNG and a JPEG produced from it with macOS ``sips``; every returned file is
verified independently by file signature and by ``sips`` for format/dimensions.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse
import zlib
from pathlib import Path

DESKTOP = Path(__file__).resolve().parents[1]
REPO_ROOT = DESKTOP.parents[1]
if str(DESKTOP) not in sys.path:
    sys.path.insert(0, str(DESKTOP))

import server  # noqa: E402

ASSETS = REPO_ROOT / "engine" / "spektra-core" / "src" / "main" / "assets" / "spektra"
REAL_WORKER = DESKTOP / ".build" / "spektra_worker"
BUILD_SCRIPT = DESKTOP / "build.sh"


def make_png(width: int, height: int) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return (
            struct.pack(">I", len(payload))
            + body
            + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        )

    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            r = (x * 255) // max(1, width - 1)
            g = (y * 255) // max(1, height - 1)
            b = (x * 3 + y * 5) % 256
            rows.extend((r, g, b))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(rows), 6))
        + chunk(b"IEND", b"")
    )


def sips_info(path: Path) -> dict[str, object]:
    result = subprocess.run(
        [
            "sips",
            "-g",
            "pixelWidth",
            "-g",
            "pixelHeight",
            "-g",
            "format",
            str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    info: dict[str, object] = {}
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(pixelWidth|pixelHeight|format):\s*(.+)", line)
        if match:
            key, value = match.group(1), match.group(2).strip()
            info[key] = int(value) if key != "format" else value
    return info


def png_dimensions(data: bytes) -> tuple[int, int]:
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
        raise AssertionError("not a PNG")
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")


def request(port: int, token: str, method: str, path: str,
            body: bytes | None = None, headers: dict[str, str] | None = None,
            *, timeout: float = 120.0):
    import http.client

    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    request_headers = dict(headers or {})
    if token:
        request_headers["X-Spektra-Token"] = token
    conn.request(method, path, body=body, headers=request_headers)
    response = conn.getresponse()
    payload = response.read()
    response_headers = {k.lower(): v for k, v in response.getheaders()}
    status = response.status
    conn.close()
    return status, response_headers, payload


class EndToEndTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if shutil.which("sips") is None:
            raise unittest.SkipTest("macOS sips is required for the e2e oracle")
        if not REAL_WORKER.is_file():
            # Only build when the binary is absent; never disturb a running
            # native verification by rebuilding an existing worker.
            result = subprocess.run(
                ["bash", str(BUILD_SCRIPT)],
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
                timeout=600,
            )
            if result.returncode != 0:
                raise AssertionError(
                    "build.sh failed: " + (result.stderr or result.stdout)[-4000:]
                )
        if not REAL_WORKER.is_file():
            raise AssertionError("worker binary still missing after build.sh")

        cls.tmp = Path(tempfile.mkdtemp(prefix="spk-e2e-"))
        os.chmod(cls.tmp, 0o700)
        cls.width, cls.height = 320, 240
        cls.png = make_png(cls.width, cls.height)
        cls.png_path = cls.tmp / "source.png"
        cls.png_path.write_bytes(cls.png)
        cls.jpg_path = cls.tmp / "source.jpg"
        converted = subprocess.run(
            ["sips", "-s", "format", "jpeg", str(cls.png_path), "--out", str(cls.jpg_path)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if converted.returncode != 0 or not cls.jpg_path.is_file():
            raise AssertionError("sips could not create the JPEG fixture")
        cls.jpg = cls.jpg_path.read_bytes()
        assert cls.jpg.startswith(b"\xff\xd8\xff")

        cls.server = server.LocalServer(port=0)
        cls.server.start()
        cls.port = cls.server.port
        cls.token = cls.server.token
        cls.worker_pid = getattr(cls.server.worker, "pid", None)
        cls.session_dir = cls.server.session_dir

    @classmethod
    def tearDownClass(cls) -> None:
        if getattr(cls, "server", None) is not None:
            cls.server.stop()
        if getattr(cls, "tmp", None) is not None:
            shutil.rmtree(cls.tmp, ignore_errors=True)

    # ------------------------------------------------------------------ helpers
    def open_image(self, data: bytes, image_format: str) -> dict:
        status, headers, body = request(
            self.port,
            self.token,
            "POST",
            "/api/open",
            data,
            {
                "Content-Type": "application/octet-stream",
                "X-Spektra-Format": image_format,
            },
        )
        self.assertEqual(200, status, body[:1000])
        return json.loads(body)

    def preview(self, generation: int, preset: str) -> dict:
        status, _, body = request(
            self.port,
            self.token,
            "POST",
            "/api/preview",
            json.dumps(
                {
                    "generation": generation,
                    "recipe": {"presetId": preset, "strength": 1.0},
                }
            ).encode(),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(200, status, body[:1000])
        return json.loads(body)

    def export(self, preset: str, image_format: str, filename: str, quality: int = 90):
        status, headers, body = request(
            self.port,
            self.token,
            "POST",
            "/api/export",
            json.dumps(
                {
                    "recipe": {"presetId": preset, "strength": 1.0},
                    "format": image_format,
                    "quality": quality,
                    "filename": filename,
                }
            ).encode(),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(200, status, body[:1000])
        return headers, body

    # ------------------------------------------------------------------ the flow
    def test_01_catalog_is_real(self) -> None:
        status, headers, body = request(self.port, self.token, "GET", "/api/catalog")
        self.assertEqual(200, status)
        catalog = json.loads(body)
        self.assertEqual(27, len(catalog["presets"]))
        self.assertEqual(28, len(catalog["stocks"]))
        self.assertEqual(20, len(catalog["films"]))
        self.assertEqual(8, len(catalog["prints"]))
        self.assertEqual("default-src 'self'", headers["content-security-policy"])
        self.assertFalse([k for k in headers if k.startswith("access-control-")])

    def test_02_open_png_then_multiple_previews_then_stale(self) -> None:
        opened = self.open_image(self.png, "png")
        self.assertEqual(self.width, opened["width"])
        self.assertEqual(self.height, opened["height"])
        self.assertEqual("png", opened["format"])
        self.assertGreaterEqual(opened["generation"], 1)

        status, headers, source = request(self.port, self.token, "GET", opened["previewUrl"])
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["content-type"])
        self.assertTrue(source.startswith(b"\x89PNG\r\n\x1a\n"))
        source_width, source_height = png_dimensions(source)
        self.assertLessEqual(max(source_width, source_height), 1024)
        self.assertEqual(
            (opened["previewWidth"], opened["previewHeight"]),
            (source_width, source_height),
        )

        presets = [
            "portra400_endura_wedding",
            "velvia100_chrome_landscape",
            "vision3_250d_2383_daycinema",
            "neutral_adobe_like",
        ]
        first_generation = None
        for index, preset in enumerate(presets, start=1):
            generation = opened["generation"] + index
            result = self.preview(generation, preset)
            self.assertEqual(generation, result["generation"])
            self.assertGreater(result["width"], 0, preset)
            self.assertGreater(result["height"], 0, preset)
            self.assertLessEqual(max(result["width"], result["height"]), 1024)
            self.assertGreaterEqual(result["ms"], 0)
            status, headers, image = request(
                self.port, self.token, "GET", result["previewUrl"]
            )
            self.assertEqual(200, status, preset)
            self.assertEqual("image/png", headers["content-type"])
            self.assertTrue(image.startswith(b"\x89PNG\r\n\x1a\n"), preset)
            self.assertEqual(
                (result["width"], result["height"]),
                png_dimensions(image),
                preset,
            )
            if first_generation is None:
                first_generation = generation

        # A generation below the current one must produce an explicit 409 JSON.
        assert first_generation is not None
        status, _, body = request(
            self.port,
            self.token,
            "POST",
            "/api/preview",
            json.dumps(
                {
                    "generation": first_generation,
                    "recipe": {"presetId": presets[0], "strength": 1.0},
                }
            ).encode(),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(409, status)
        stale = json.loads(body)
        self.assertEqual("stale_generation", stale["code"])
        self.assertTrue(stale["error"])

    def test_03_full_size_jpeg_and_png_exports(self) -> None:
        opened = self.open_image(self.png, "png")
        for image_format, expected_mime, signature in (
            ("jpeg", "image/jpeg", b"\xff\xd8\xff"),
            ("png", "image/png", b"\x89PNG\r\n\x1a\n"),
        ):
            headers, body = self.export(
                "portra400_endura_wedding",
                image_format,
                f"中文 导出 <{image_format}>",
                quality=91,
            )
            self.assertEqual(expected_mime, headers["content-type"])
            self.assertTrue(body.startswith(signature), image_format)
            self.assertEqual("320", headers["x-spektra-width"])
            self.assertEqual("240", headers["x-spektra-height"])
            self.assertGreaterEqual(int(headers["x-spektra-ms"]), 0)
            self.assertEqual(str(len(body)), headers["content-length"])
            disposition = headers["content-disposition"]
            self.assertIn("filename*=UTF-8''", disposition)
            decoded = urllib.parse.unquote(
                disposition.split("filename*=UTF-8''", 1)[1]
            )
            self.assertTrue(decoded.startswith("中文 导出"))
            self.assertNotIn("/", decoded)
            self.assertNotIn("\\", decoded)
            self.assertFalse([k for k in headers if k.startswith("access-control-")])

            suffix = ".jpg" if image_format == "jpeg" else ".png"
            out = self.tmp / f"export-{image_format}{suffix}"
            out.write_bytes(body)
            info = sips_info(out)
            self.assertEqual("jpeg" if image_format == "jpeg" else "png", info["format"])
            self.assertEqual(self.width, info["pixelWidth"])
            self.assertEqual(self.height, info["pixelHeight"])
            self.assertEqual((opened["width"], opened["height"]), (self.width, self.height))

    def test_04_open_real_jpeg(self) -> None:
        opened = self.open_image(self.jpg, "jpeg")
        self.assertEqual(self.width, opened["width"])
        self.assertEqual(self.height, opened["height"])
        self.assertEqual("jpeg", opened["format"])
        status, _, source = request(self.port, self.token, "GET", opened["previewUrl"])
        self.assertEqual(200, status)
        png_dimensions(source)

        result = self.preview(opened["generation"] + 1, "ektar100_ultra_landscape")
        self.assertGreater(result["ms"], -1)
        status, _, image = request(self.port, self.token, "GET", result["previewUrl"])
        self.assertEqual(200, status)
        self.assertTrue(image.startswith(b"\x89PNG\r\n\x1a\n"))

    def test_05_loopback_only_host_and_session_mode(self) -> None:
        self.assertIsNotNone(self.server.httpd)
        address = self.server.httpd.server_address
        self.assertEqual("127.0.0.1", address[0])
        self.assertNotEqual(0, self.port)
        mode = stat.S_IMODE(self.session_dir.stat().st_mode)
        self.assertEqual(0o700, mode)

        # The service is reachable on loopback...
        status, _, _ = request(self.port, self.token, "GET", "/api/health")
        self.assertEqual(200, status)

        # ...and not on a non-loopback interface, when this Mac has one.
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.settimeout(1.0)
        try:
            local_ip = socket.gethostbyname(socket.gethostname())
        except OSError:
            local_ip = ""
        if local_ip and not local_ip.startswith("127."):
            self.assertNotEqual(
                0,
                probe.connect_ex((local_ip, self.port)),
                f"server unexpectedly reachable on {local_ip}",
            )
        probe.close()

        # Reject a rebinding-style Host header with Chinese JSON.
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        sock.sendall(
            (
                f"GET /api/health HTTP/1.1\r\nHost: evil.example\r\n"
                f"X-Spektra-Token: {self.token}\r\nConnection: close\r\n\r\n"
            ).encode()
        )
        raw = b""
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            raw += chunk
        sock.close()
        self.assertTrue(raw.startswith(b"HTTP/1.1 403"), raw[:100])
        self.assertIn("Host".encode(), raw)

    def test_zz_cleanup(self) -> None:
        self.assertTrue(self.session_dir.is_dir())
        pid = self.worker_pid
        port = self.port
        self.server.stop()
        self.assertFalse(self.session_dir.exists())
        if pid is not None:
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline:
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", port), timeout=0.5)


if __name__ == "__main__":
    unittest.main()
