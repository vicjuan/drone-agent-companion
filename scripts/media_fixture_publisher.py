#!/usr/bin/env python3
"""Own the synthetic RGB source and ffmpeg publisher as one fail-closed process.

The controller keeps this supervisor as a foreground-owned child. This process never
daemonizes: if either child exits, or this process receives HUP/INT/TERM, it terminates and
reaps both children before returning.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import signal
import stat
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Iterable, Sequence


EXPECTED_OWNERSHIP_TAG = "drone-agent-companion-issue-7a-mac-whep"
STOP_REQUEST_NAME_PATTERN = re.compile(r"^stop-request\.([0-9a-f]{64})$")
STOP_SIGNAL: int | None = None


@dataclass(frozen=True)
class PublisherConfig:
    source_script: pathlib.Path
    ffmpeg_bin: str
    width: int
    height: int
    fps: int
    gop: int
    rtmp_url: str
    ownership_tag: str
    stop_request_file: pathlib.Path


def _request_stop(signum: int, _frame: object) -> None:
    global STOP_SIGNAL
    STOP_SIGNAL = signum


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def parse_args(arguments: Iterable[str]) -> PublisherConfig:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-script", type=pathlib.Path, required=True)
    parser.add_argument("--ffmpeg-bin", default="ffmpeg")
    parser.add_argument("--width", type=positive_int, required=True)
    parser.add_argument("--height", type=positive_int, required=True)
    parser.add_argument("--fps", type=positive_int, required=True)
    parser.add_argument("--gop", type=positive_int, required=True)
    parser.add_argument("--rtmp-url", required=True)
    parser.add_argument("--ownership-tag", required=True)
    parser.add_argument("--stop-request-file", type=pathlib.Path, required=True)
    args = parser.parse_args(list(arguments))

    requested_source = args.source_script
    if not requested_source.is_file() or requested_source.is_symlink():
        parser.error("source script must be an existing non-symlink file")
    source_script = requested_source.resolve(strict=True)
    if args.width < 320 or args.height < 180 or args.width % 2 or args.height % 2:
        parser.error("dimensions must be even and at least 320x180")
    if args.fps > 60:
        parser.error("fps must not exceed 60")
    if args.ownership_tag != EXPECTED_OWNERSHIP_TAG:
        parser.error("publisher ownership tag is not the fixed issue #7A marker")
    if args.rtmp_url != "rtmp://127.0.0.1:1936/mock-main":
        parser.error("publisher URL is not the fixed issue #7A loopback path")
    if not args.ffmpeg_bin or any(character.isspace() for character in args.ffmpeg_bin):
        parser.error("ffmpeg executable must be a single non-empty argument")
    stop_request_parent = args.stop_request_file.parent.resolve(strict=True)
    stop_request_file = stop_request_parent / args.stop_request_file.name
    if STOP_REQUEST_NAME_PATTERN.fullmatch(stop_request_file.name) is None:
        parser.error("stop request filename must contain one 64-hex run nonce")

    return PublisherConfig(
        source_script=source_script,
        ffmpeg_bin=args.ffmpeg_bin,
        width=args.width,
        height=args.height,
        fps=args.fps,
        gop=args.gop,
        rtmp_url=args.rtmp_url,
        ownership_tag=args.ownership_tag,
        stop_request_file=stop_request_file,
    )


def build_source_command(config: PublisherConfig) -> list[str]:
    return [
        sys.executable,
        "-u",
        str(config.source_script),
        "--width",
        str(config.width),
        "--height",
        str(config.height),
        "--fps",
        str(config.fps),
    ]


def build_ffmpeg_command(config: PublisherConfig) -> list[str]:
    return [
        config.ffmpeg_bin,
        "-hide_banner",
        "-nostdin",
        "-loglevel",
        "warning",
        "-f",
        "rawvideo",
        "-pixel_format",
        "rgb24",
        "-video_size",
        f"{config.width}x{config.height}",
        "-framerate",
        str(config.fps),
        "-i",
        "pipe:0",
        "-an",
        "-metadata",
        f"comment={config.ownership_tag}",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-tune",
        "zerolatency",
        "-profile:v",
        "baseline",
        "-level:v",
        "3.0",
        "-pix_fmt",
        "yuv420p",
        "-bf",
        "0",
        "-g",
        str(config.gop),
        "-keyint_min",
        str(config.gop),
        "-sc_threshold",
        "0",
        "-b:v",
        "1200k",
        "-maxrate",
        "1200k",
        "-bufsize",
        "600k",
        "-f",
        "flv",
        "-flvflags",
        "no_duration_filesize",
        config.rtmp_url,
    ]


def _wait_or_kill(child: subprocess.Popen[bytes], deadline: float) -> None:
    remaining = max(0.0, deadline - time.monotonic())
    try:
        child.wait(timeout=remaining)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait(timeout=2.0)


def stop_and_reap(children: Sequence[subprocess.Popen[bytes]]) -> None:
    # Stop the pipe consumer first, then its producer. Both are direct children and
    # no shell or broad process lookup participates in cleanup.
    for child in reversed(children):
        if child.poll() is None:
            try:
                child.terminate()
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 3.0
    for child in reversed(children):
        _wait_or_kill(child, deadline)


def consume_stop_request(path: pathlib.Path) -> bool:
    match = STOP_REQUEST_NAME_PATTERN.fullmatch(path.name)
    if match is None:
        raise RuntimeError("stop request path lost its run nonce")
    expected = f"{match.group(1)}\n".encode("ascii")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except FileNotFoundError:
        return False
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise RuntimeError("stop request is not a regular file")
        if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o600:
            raise RuntimeError("stop request ownership/mode is invalid")
        payload = os.read(descriptor, len(expected) + 1)
    finally:
        os.close(descriptor)
    if payload != expected:
        raise RuntimeError("stop request payload does not match its run nonce")
    path.unlink()
    return True


def supervise(config: PublisherConfig) -> int:
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    source: subprocess.Popen[bytes] | None = None
    ffmpeg: subprocess.Popen[bytes] | None = None
    try:
        source = subprocess.Popen(
            build_source_command(config),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=None,
            env=environment,
            close_fds=True,
        )
        if source.stdout is None:
            raise RuntimeError("source stdout pipe was not created")
        try:
            ffmpeg = subprocess.Popen(
                build_ffmpeg_command(config),
                stdin=source.stdout,
                stdout=None,
                stderr=None,
                env=environment,
                close_fds=True,
            )
        finally:
            # The parent must not retain the read side; EOF then follows source exit.
            source.stdout.close()

        print(
            "[media-publisher] RUNNING "
            f"supervisor_pid={os.getpid()} source_pid={source.pid} ffmpeg_pid={ffmpeg.pid} "
            f"tag={config.ownership_tag}",
            flush=True,
        )
        while STOP_SIGNAL is None:
            if consume_stop_request(config.stop_request_file):
                print("[media-publisher] stopping on nonce-scoped request", flush=True)
                return 0
            source_status = source.poll()
            ffmpeg_status = ffmpeg.poll()
            if ffmpeg_status is not None:
                print(
                    f"[media-publisher] ERROR ffmpeg exited unexpectedly status={ffmpeg_status}",
                    file=sys.stderr,
                    flush=True,
                )
                return 71
            if source_status is not None:
                print(
                    f"[media-publisher] ERROR source exited unexpectedly status={source_status}",
                    file=sys.stderr,
                    flush=True,
                )
                return 70
            time.sleep(0.1)

        print(f"[media-publisher] stopping on signal={STOP_SIGNAL}", flush=True)
        return 0
    finally:
        stop_and_reap([child for child in (source, ffmpeg) if child is not None])


def main(arguments: Iterable[str] | None = None) -> int:
    global STOP_SIGNAL
    STOP_SIGNAL = None
    config = parse_args(sys.argv[1:] if arguments is None else arguments)
    signal.signal(signal.SIGINT, _request_stop)
    signal.signal(signal.SIGTERM, _request_stop)
    signal.signal(signal.SIGHUP, _request_stop)
    return supervise(config)


if __name__ == "__main__":
    raise SystemExit(main())
