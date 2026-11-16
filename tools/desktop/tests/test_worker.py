# -*- coding: utf-8 -*-
"""Worker manager tests: real protocol, fake crash/timeout/stale paths. GPLv3."""

from __future__ import annotations

import json
import os
import shutil
import struct
import sys
import tempfile
import threading
import time
import unittest
import zlib
from pathlib import Path

DESKTOP = Path(__file__).resolve().parents[1]
if str(DESKTOP) not in sys.path:
    sys.path.insert(0, str(DESKTOP))

import worker  # noqa: E402

REPO_ROOT = DESKTOP.parents[1]
ASSETS = REPO_ROOT / "engine" / "spektra-core" / "src" / "main" / "assets" / "spektra"
REAL_WORKER = DESKTOP / ".build" / "spektra_worker"

FAKE_WORKER = r'''#!/usr/bin/env python3
import json, os, sys, time

session = sys.argv[2]

def emit(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()

def note(name, text):
    path = os.path.join(session, name)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(text + "\n")

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    request = json.loads(line)
    op = request.get("op")
    if op == "open":
        if request.get("path") == "stderr.png":
            sys.stderr.write("SECRET-PHOTO-NAME.jpg\n")
            sys.stderr.flush()
            sys.exit(9)
        emit({"ok": True, "width": 40, "height": 30, "preview": "source.png",
              "previewWidth": 40, "previewHeight": 30, "orientation": 1})
    elif op == "preview":
        preset = request.get("recipe", {}).get("presetId", "")
        out = request["out"]
        note(out + ".started", "1")
        if preset == "hang":
            time.sleep(60)
        if preset.startswith("sleep:"):
            time.sleep(float(preset.split(":", 1)[1]))
        with open(os.path.join(session, out), "wb") as handle:
            handle.write(b"\x89PNG\r\n\x1a\nfake")
        emit({"ok": True, "width": 40, "height": 30, "ms": 7})
    elif op == "export":
        out = request["out"]
        note("ops.log", "start " + out)
        time.sleep(0.2)
        with open(os.path.join(session, out), "wb") as handle:
            handle.write(b"\xff\xd8\xfffake")
        note("ops.log", "end " + out)
        emit({"ok": True, "path": out, "format": "jpeg", "width": 40,
              "height": 30, "bytes": 8, "ms": 3})
    elif op == "reset":
        emit({"ok": True})
    elif op == "close":
        emit({"ok": True})
        break
    else:
        emit({"ok": False, "error": "unknown op"})
'''

EXIT_WORKER = r'''#!/usr/bin/env python3
import sys, time
sys.stderr.write("SECRET-PHOTO-NAME.jpg\n")
sys.stderr.flush()
time.sleep(0.02)
sys.exit(5)
'''


def make_png(width: int = 64, height: int = 48) -> bytes:
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
            rows.extend(((x * 7) % 256, (y * 5) % 256, 128))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(rows)))
        + chunk(b"IEND", b"")
    )


def _write_executable(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    os.chmod(path, 0o700)


class FakeWorkerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="spk-worker-test-"))
        os.chmod(self.tmp, 0o700)
        self.session = self.tmp / "session"
        self.session.mkdir(mode=0o700)
        self.script = self.tmp / "fake_worker"
        _write_executable(self.script, FAKE_WORKER)
        self.clients: list[worker.WorkerClient] = []

    def tearDown(self) -> None:
        for client in self.clients:
            try:
                client.stop()
            except Exception:
                pass
        shutil.rmtree(self.tmp, ignore_errors=True)

    def client(self, timeout: float = 3.0) -> worker.WorkerClient:
        client = worker.WorkerClient(
            self.script, ASSETS, self.session, timeout=timeout
        )
        client.start()
        self.clients.append(client)
        return client

    def test_open_preview_export_and_close(self) -> None:
        client = self.client()
        pid = client.pid
        self.assertIsNotNone(pid)
        opened = client.open_image("input.png")
        self.assertEqual(40, opened["width"])
        preview = client.preview({"presetId": "__default__"}, 1)
        self.assertEqual(1, preview["generation"])
        exported = client.export({"presetId": "__default__"}, "out.jpg", quality=90)
        self.assertEqual("jpeg", exported["format"])
        client.stop()
        self.assertFalse(client.running)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_new_preview_makes_old_generation_stale_and_removes_old_file(self) -> None:
        client = self.client()
        client.open_image("input.png")
        result: dict = {}

        def old_preview() -> None:
            try:
                client.preview({"presetId": "sleep:0.55"}, 1)
                result["old"] = "returned"
            except worker.StaleGenerationError as exc:
                result["old"] = exc
            except Exception as exc:  # pragma: no cover - diagnostic
                result["old"] = exc

        thread = threading.Thread(target=old_preview)
        thread.start()
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and not list(self.session.glob("preview-1-*.started")):
            time.sleep(0.01)
        self.assertTrue(list(self.session.glob("preview-1-*.started")))

        fresh = client.preview({"presetId": "__default__"}, 2)
        thread.join(timeout=3.0)
        self.assertEqual(2, fresh["generation"])
        self.assertIsInstance(result.get("old"), worker.StaleGenerationError)
        self.assertEqual(1, result["old"].generation)
        # The stale generation's PNG must not remain published (only its marker).
        self.assertEqual([], list(self.session.glob("preview-1-*.png")))
        self.assertTrue(list(self.session.glob("preview-2-*.png")))

    def test_older_generation_is_rejected_before_native_call(self) -> None:
        client = self.client()
        client.open_image("input.png")
        client.preview({"presetId": "__default__"}, 4)
        with self.assertRaises(worker.StaleGenerationError):
            client.preview({"presetId": "__default__"}, 3)

    def test_duplicate_inflight_generation_is_stale(self) -> None:
        client = self.client()
        client.open_image("input.png")
        result: dict = {}

        def first() -> None:
            try:
                client.preview({"presetId": "sleep:0.4"}, 6)
                result["first"] = "ok"
            except Exception as exc:
                result["first"] = exc

        thread = threading.Thread(target=first)
        thread.start()
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and not list(self.session.glob("preview-6-*.started")):
            time.sleep(0.01)
        with self.assertRaises(worker.StaleGenerationError):
            client.preview({"presetId": "__default__"}, 6)
        thread.join(timeout=3.0)
        self.assertEqual("ok", result.get("first"))

    def test_export_calls_are_serialized(self) -> None:
        client = self.client()
        client.open_image("input.png")
        errors: list[Exception] = []

        def run(name: str) -> None:
            try:
                client.export({"presetId": "__default__"}, name, quality=80)
            except Exception as exc:  # pragma: no cover - diagnostic
                errors.append(exc)

        started = time.monotonic()
        threads = [
            threading.Thread(target=run, args=("one.jpg",)),
            threading.Thread(target=run, args=("two.jpg",)),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=3.0)
        elapsed = time.monotonic() - started
        self.assertEqual([], errors)
        self.assertGreaterEqual(elapsed, 0.38)
        lines = (self.session / "ops.log").read_text(encoding="utf-8").splitlines()
        self.assertEqual(4, len(lines))
        self.assertTrue(lines[0].startswith("start "))
        self.assertTrue(lines[1].startswith("end "))
        self.assertTrue(lines[2].startswith("start "))
        self.assertTrue(lines[3].startswith("end "))

    def test_timeout_kills_the_child(self) -> None:
        client = self.client(timeout=0.5)
        client.open_image("input.png")
        pid = client.pid
        with self.assertRaises(worker.WorkerTimeoutError):
            client.preview({"presetId": "hang"}, 1)
        self.assertFalse(client.running)
        assert pid is not None
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_crash_does_not_leak_stderr_photo_name(self) -> None:
        client = self.client()
        with self.assertRaises(worker.WorkerError) as caught:
            client.open_image("stderr.png")
        self.assertNotIn("SECRET-PHOTO-NAME", str(caught.exception))
        self.assertGreater(client.stderr_bytes, 0)
        self.assertFalse(client.running)

    def test_immediate_exit_at_start_does_not_leak_stderr(self) -> None:
        script = self.tmp / "exit_worker"
        _write_executable(script, EXIT_WORKER)
        client = worker.WorkerClient(script, ASSETS, self.session, timeout=1.0)
        self.clients.append(client)
        try:
            client.start()
        except worker.WorkerUnavailableError as exc:
            self.assertNotIn("SECRET-PHOTO-NAME", str(exc))
            return
        # The child may not have exited inside start()'s short readiness probe.
        time.sleep(0.2)
        with self.assertRaises(worker.WorkerError) as caught:
            client.open_image("input.png")
        self.assertNotIn("SECRET-PHOTO-NAME", str(caught.exception))

    def test_missing_binary_is_explicit(self) -> None:
        client = worker.WorkerClient(
            self.tmp / "nope", ASSETS, self.session, timeout=1.0
        )
        self.clients.append(client)
        with self.assertRaises(worker.WorkerUnavailableError):
            client.start()

    def test_unsafe_session_paths_are_rejected(self) -> None:
        client = self.client()
        for name in ("../escape.png", "/etc/passwd", "", ".hidden", "a/b.png"):
            with self.assertRaises(worker.WorkerProtocolError):
                client.open_image(name)
        with self.assertRaises(worker.WorkerProtocolError):
            client.preview({"presetId": "__default__"}, 0)
        with self.assertRaises(worker.WorkerProtocolError):
            client.export({"presetId": "__default__"}, "x.jpg", quality=0)


@unittest.skipUnless(REAL_WORKER.is_file(), "native worker not built yet")
class RealWorkerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="spk-real-worker-"))
        os.chmod(self.tmp, 0o700)
        (self.tmp / "input.png").write_bytes(make_png())
        self.client = worker.WorkerClient(
            REAL_WORKER, ASSETS, self.tmp, timeout=90.0
        )
        self.client.start()

    def tearDown(self) -> None:
        self.client.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_real_open_preview_export(self) -> None:
        opened = self.client.open_image("input.png")
        self.assertEqual(64, opened["width"])
        self.assertEqual(48, opened["height"])
        self.assertTrue((self.tmp / "source.png").is_file())

        first = self.client.preview(
            {"presetId": "portra400_endura_wedding", "strength": 1.0}, 1
        )
        self.assertEqual(1, first["generation"])
        self.assertGreaterEqual(first["ms"], 0)
        self.assertTrue((self.tmp / str(first["out"])).is_file())

        exported = self.client.export(
            {"presetId": "neutral_adobe_like", "strength": 1.0}, "export.jpg", quality=85
        )
        self.assertEqual("jpeg", exported["format"])
        self.assertEqual(64, exported["width"])
        payload = (self.tmp / "export.jpg").read_bytes()
        self.assertTrue(payload.startswith(b"\xff\xd8\xff"))


if __name__ == "__main__":
    unittest.main()
