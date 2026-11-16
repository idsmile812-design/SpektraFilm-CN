#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Launch the Spektrafilm local desktop editor service. GPLv3.

Builds the native worker on demand (only when the binary is missing), starts the
loopback HTTP service on a random port, prints its token-bearing URL, and opens
the default browser unless ``--no-browser`` was given.  Ctrl+C stops the HTTP
server, closes the native worker and removes the 0700 session directory.

Usage:
    python3 tools/desktop/run.py [--port 0] [--no-browser] [--timeout 180]
"""

from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import webbrowser
from pathlib import Path

DESKTOP_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = DESKTOP_DIR.parents[1]
BUILD_SCRIPT = DESKTOP_DIR / "build.sh"
WORKER_PATH = DESKTOP_DIR / ".build" / "spektra_worker"


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="run.py",
        description="启动 Spektrafilm 本地编辑器服务（仅绑定 127.0.0.1）",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=0,
        help="HTTP 端口；0（默认）表示随机端口，始终只绑定 127.0.0.1",
    )
    parser.add_argument(
        "--no-browser",
        action="store_true",
        help="不自动打开浏览器",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=180.0,
        help="单次 native 渲染超时秒数（默认 180）",
    )
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("--port 必须在 0..65535")
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    return args


def _ensure_worker() -> None:
    """Build the worker only when the binary is missing; never rebuild in place."""
    if WORKER_PATH.is_file() and os.access(WORKER_PATH, os.X_OK):
        return
    print(f"未找到 native worker，正在构建：{WORKER_PATH}", file=sys.stderr)
    if not BUILD_SCRIPT.is_file():
        raise RuntimeError(f"缺少构建脚本：{BUILD_SCRIPT}")
    result = subprocess.run(
        ["bash", str(BUILD_SCRIPT)],
        cwd=str(PROJECT_ROOT),
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"native worker 构建失败（退出码 {result.returncode}）")
    if not WORKER_PATH.is_file():
        raise RuntimeError(f"构建完成但没有生成 {WORKER_PATH}")


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(list(sys.argv[1:] if argv is None else argv))

    # Make sibling imports work when launched as a plain script.
    if str(DESKTOP_DIR) not in sys.path:
        sys.path.insert(0, str(DESKTOP_DIR))

    try:
        _ensure_worker()
        from catalog import CatalogError
        from server import LocalServer
    except Exception as exc:
        print(f"启动失败：{exc}", file=sys.stderr)
        return 1

    server = None
    try:
        server = LocalServer(port=args.port, worker_timeout=args.timeout)
        server.start()
        url = server.url
        print("Spektrafilm 本地编辑器服务已启动。", file=sys.stderr)
        print(f"地址：{url}", file=sys.stderr)
        print(f"API 令牌：{server.token}", file=sys.stderr)
        print("按 Ctrl+C 停止并清理。", file=sys.stderr)

        def _terminate(signum: int, frame: object) -> None:
            raise KeyboardInterrupt

        try:
            signal.signal(signal.SIGTERM, _terminate)
        except (ValueError, OSError):
            pass

        if not args.no_browser:
            try:
                webbrowser.open(url)
            except Exception:
                pass
        # The HTTP server already runs on its own thread; park the main thread
        # so Ctrl+C is delivered here and cleanup always runs.
        while True:
            signal.pause()
    except KeyboardInterrupt:
        print("\n正在停止…", file=sys.stderr)
        return 0
    except CatalogError as exc:
        print(f"目录资源错误：{exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"启动失败：{exc}", file=sys.stderr)
        return 1
    finally:
        if server is not None:
            server.stop()
        print("已停止。", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
