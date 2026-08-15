#!/usr/bin/env python3
"""Lifecycle and command-policy tests for the foreground publisher supervisor."""

from __future__ import annotations

import importlib.util
import os
import pathlib
import re
import selectors
import stat
import subprocess
import sys
import tempfile
import time
import unittest


sys.dont_write_bytecode = True
SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
PUBLISHER_PATH = SCRIPT_DIR / "media_fixture_publisher.py"
SOURCE_PATH = SCRIPT_DIR / "media_fixture_source.py"
OWNERSHIP_TAG = "drone-agent-companion-issue-7a-mac-whep"
RTMP_URL = "rtmp://127.0.0.1:1936/mock-main"
RUN_NONCE = "a" * 64

SPEC = importlib.util.spec_from_file_location("media_fixture_publisher", PUBLISHER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load publisher supervisor: {PUBLISHER_PATH}")
PUBLISHER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PUBLISHER
SPEC.loader.exec_module(PUBLISHER)


def publisher_command(ffmpeg_bin: pathlib.Path, stop_request_file: pathlib.Path) -> list[str]:
    return [
        sys.executable,
        "-u",
        str(PUBLISHER_PATH),
        "--source-script",
        str(SOURCE_PATH),
        "--ffmpeg-bin",
        str(ffmpeg_bin),
        "--width",
        "320",
        "--height",
        "180",
        "--fps",
        "30",
        "--gop",
        "30",
        "--rtmp-url",
        RTMP_URL,
        "--ownership-tag",
        OWNERSHIP_TAG,
        "--stop-request-file",
        str(stop_request_file),
    ]


def write_fake_ffmpeg(directory: pathlib.Path, body: str) -> pathlib.Path:
    executable = directory / "fake_ffmpeg"
    executable.write_text(f"#!{sys.executable}\n{body}", encoding="utf-8")
    executable.chmod(executable.stat().st_mode | stat.S_IXUSR)
    return executable


def write_stop_request(path: pathlib.Path, nonce: str) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii", closefd=True) as output:
        output.write(f"{nonce}\n")


def read_ready_line(process: subprocess.Popen[str], timeout: float = 5.0) -> str:
    if process.stdout is None:
        raise AssertionError("supervisor stdout was not captured")
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        events = selector.select(timeout)
        if not events:
            raise AssertionError("supervisor did not announce child ownership")
        line = process.stdout.readline()
        if not line:
            stderr = process.stderr.read() if process.stderr is not None else ""
            raise AssertionError(f"supervisor exited before readiness: {stderr}")
        return line
    finally:
        selector.close()


def owned_child_pids(ready_line: str) -> tuple[int, int]:
    match = re.search(r"source_pid=(\d+) ffmpeg_pid=(\d+)", ready_line)
    if match is None:
        raise AssertionError(f"readiness line lacks owned child PIDs: {ready_line!r}")
    return int(match.group(1)), int(match.group(2))


def process_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def wait_until_gone(pids: tuple[int, ...], timeout: float = 3.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not any(process_exists(pid) for pid in pids):
            return True
        time.sleep(0.05)
    return not any(process_exists(pid) for pid in pids)


class PublisherSupervisorTest(unittest.TestCase):
    def test_ffmpeg_command_freezes_low_latency_h264_contract(self) -> None:
        config = PUBLISHER.PublisherConfig(
            source_script=SOURCE_PATH,
            ffmpeg_bin="ffmpeg",
            width=640,
            height=360,
            fps=30,
            gop=30,
            rtmp_url=RTMP_URL,
            ownership_tag=OWNERSHIP_TAG,
            stop_request_file=pathlib.Path(f"/tmp/stop-request.{RUN_NONCE}"),
        )

        command = PUBLISHER.build_ffmpeg_command(config)

        self.assertIn("baseline", command)
        self.assertEqual("0", command[command.index("-bf") + 1])
        self.assertEqual("30", command[command.index("-g") + 1])
        self.assertEqual("30", command[command.index("-keyint_min") + 1])
        self.assertEqual("0", command[command.index("-sc_threshold") + 1])
        self.assertEqual(f"comment={OWNERSHIP_TAG}", command[command.index("-metadata") + 1])
        self.assertEqual(RTMP_URL, command[-1])

    def test_term_reaps_source_and_ffmpeg_children(self) -> None:
        with tempfile.TemporaryDirectory(prefix="media-publisher-test-") as temporary:
            fake_ffmpeg = write_fake_ffmpeg(
                pathlib.Path(temporary),
                "import sys\n"
                "while sys.stdin.buffer.read(65536):\n"
                "    pass\n",
            )
            environment = os.environ.copy()
            environment["PYTHONDONTWRITEBYTECODE"] = "1"
            stop_request = pathlib.Path(temporary) / f"stop-request.{RUN_NONCE}"
            process = subprocess.Popen(
                publisher_command(fake_ffmpeg, stop_request),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=environment,
                close_fds=True,
            )
            child_pids: tuple[int, int] = ()
            try:
                ready = read_ready_line(process)
                child_pids = owned_child_pids(ready)
                self.assertTrue(all(process_exists(pid) for pid in child_pids))
                time.sleep(1.2)
                self.assertIsNone(process.poll(), "supervisor did not survive the former one-second boundary")
                self.assertTrue(all(process_exists(pid) for pid in child_pids))

                process.terminate()
                stdout, stderr = process.communicate(timeout=8)

                self.assertEqual(0, process.returncode, ready + stdout + stderr)
                self.assertIn("stopping on signal=15", stdout)
                self.assertTrue(wait_until_gone(child_pids), f"owned children survived: {child_pids}")
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=8)
                if child_pids:
                    self.assertTrue(wait_until_gone(child_pids), f"test leaked children: {child_pids}")

    def test_unexpected_ffmpeg_exit_stops_source_and_fails_supervisor(self) -> None:
        with tempfile.TemporaryDirectory(prefix="media-publisher-test-") as temporary:
            fake_ffmpeg = write_fake_ffmpeg(pathlib.Path(temporary), "raise SystemExit(7)\n")
            stop_request = pathlib.Path(temporary) / f"stop-request.{RUN_NONCE}"
            result = subprocess.run(
                publisher_command(fake_ffmpeg, stop_request),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
                timeout=8,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            )

            child_pids = owned_child_pids(result.stdout.splitlines(keepends=True)[0])
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("exited unexpectedly", result.stderr)
            self.assertTrue(wait_until_gone(child_pids), f"owned children survived: {child_pids}")

    def test_only_exact_nonce_request_stops_and_reaps_children(self) -> None:
        with tempfile.TemporaryDirectory(prefix="media-publisher-test-") as temporary:
            temporary_path = pathlib.Path(temporary)
            fake_ffmpeg = write_fake_ffmpeg(
                temporary_path,
                "import sys\n"
                "while sys.stdin.buffer.read(65536):\n"
                "    pass\n",
            )
            stop_request = temporary_path / f"stop-request.{RUN_NONCE}"
            stale_request = temporary_path / f"stop-request.{'b' * 64}"
            write_stop_request(stale_request, "b" * 64)
            process = subprocess.Popen(
                publisher_command(fake_ffmpeg, stop_request),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                close_fds=True,
            )
            child_pids: tuple[int, int] = ()
            try:
                ready = read_ready_line(process)
                child_pids = owned_child_pids(ready)
                time.sleep(0.3)
                self.assertIsNone(process.poll(), "a stale nonce request stopped the active run")

                write_stop_request(stop_request, RUN_NONCE)
                stdout, stderr = process.communicate(timeout=8)

                self.assertEqual(0, process.returncode, ready + stdout + stderr)
                self.assertIn("stopping on nonce-scoped request", stdout)
                self.assertTrue(wait_until_gone(child_pids), f"owned children survived: {child_pids}")
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=8)
                if child_pids:
                    self.assertTrue(wait_until_gone(child_pids), f"test leaked children: {child_pids}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
