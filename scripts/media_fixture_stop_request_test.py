#!/usr/bin/env python3
"""Discriminating tests for nonce-scoped cooperative fixture stop requests."""

from __future__ import annotations

import importlib.util
import os
import pathlib
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest


sys.dont_write_bytecode = True
SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
CONTROL_PATH = SCRIPT_DIR / "media_fixture_stop_request.py"
CONTROLLER_PATH = SCRIPT_DIR / "media-fixture.sh"
PUBLISHER_PATH = SCRIPT_DIR / "media_fixture_publisher.py"
SOURCE_PATH = SCRIPT_DIR / "media_fixture_source.py"

SPEC = importlib.util.spec_from_file_location("media_fixture_stop_request", CONTROL_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load stop-request helper: {CONTROL_PATH}")
CONTROL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CONTROL
SPEC.loader.exec_module(CONTROL)


def write_pid_file(state_dir: pathlib.Path, pid: int) -> None:
    path = state_dir / "publisher-supervisor.pid"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii", closefd=True) as output:
        output.write(f"{pid}\n")


class StopRequestTest(unittest.TestCase):
    def test_init_request_and_finish_are_nonce_scoped_and_mode_locked(self) -> None:
        with tempfile.TemporaryDirectory(prefix="media-stop-test-") as temporary:
            state_dir = pathlib.Path(temporary)
            state_dir.chmod(0o700)

            nonce = CONTROL.initialize_run(state_dir)
            request = CONTROL.request_stop(state_dir)

            self.assertRegex(nonce, r"^[0-9a-f]{64}$")
            self.assertEqual(f"stop-request.{nonce}", request.name)
            self.assertEqual(f"{nonce}\n", request.read_text(encoding="ascii"))
            self.assertEqual(0o600, stat.S_IMODE(request.stat().st_mode))
            self.assertEqual(request, CONTROL.request_stop(state_dir), "request must be idempotent")

            CONTROL.finish_run(state_dir, nonce)

            self.assertFalse(request.exists())
            self.assertFalse((state_dir / "run-nonce").exists())

    def test_same_argv_stale_pid_is_never_signaled_by_cross_shell_stop(self) -> None:
        with tempfile.TemporaryDirectory(prefix="media-stop-test-") as temporary:
            state_dir = pathlib.Path(temporary)
            state_dir.chmod(0o700)
            nonce = CONTROL.initialize_run(state_dir)
            signal_marker = state_dir / "decoy-was-signaled"
            stop_path = state_dir / f"stop-request.{nonce}"
            decoy_code = (
                "import pathlib,signal,sys,time;"
                "marker=pathlib.Path(sys.argv[1]);"
                "signal.signal(signal.SIGTERM,lambda *_:(marker.write_text('TERM'),sys.exit(0)));"
                "signal.signal(signal.SIGINT,lambda *_:(marker.write_text('INT'),sys.exit(0)));"
                "time.sleep(30)"
            )
            decoy = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    decoy_code,
                    str(signal_marker),
                    str(PUBLISHER_PATH),
                    "--source-script",
                    str(SOURCE_PATH),
                    "--width",
                    "640",
                    "--height",
                    "360",
                    "--fps",
                    "30",
                    "--gop",
                    "30",
                    "--rtmp-url",
                    "rtmp://127.0.0.1:1936/mock-main",
                    "--ownership-tag",
                    "drone-agent-companion-issue-7a-mac-whep",
                    "--stop-request-file",
                    str(stop_path),
                ],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                close_fds=True,
            )
            try:
                write_pid_file(state_dir, decoy.pid)

                request = CONTROL.request_stop(state_dir)
                time.sleep(0.25)

                self.assertEqual(stop_path.resolve(), request)
                self.assertIsNone(decoy.poll(), "cross-shell stop signaled a same-argv stale PID")
                self.assertFalse(signal_marker.exists(), "decoy observed a forbidden stop signal")
            finally:
                if decoy.poll() is None:
                    decoy.send_signal(signal.SIGTERM)
                    decoy.wait(timeout=3)

    def test_shell_stop_path_contains_no_process_or_docker_mutation(self) -> None:
        controller = CONTROLLER_PATH.read_text(encoding="utf-8")
        stop_body = controller.split("stop_fixture() {", 1)[1].split("\n}\n\nmain()", 1)[0]

        self.assertIn("media_fixture_stop_request.py", controller)
        self.assertIn("STOP REQUESTED", stop_body)
        self.assertNotIn("kill ", stop_body)
        self.assertNotIn("validate_owned_publisher", stop_body)
        self.assertNotIn("docker ", stop_body)
        self.assertNotIn("PUBLISHER_PID_FILE", stop_body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
