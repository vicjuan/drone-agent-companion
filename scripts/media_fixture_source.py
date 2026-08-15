#!/usr/bin/env python3
"""Emit a clocked raw-RGB fixture without Pillow, drawtext, or network access.

The upper seven-segment row is local HH:MM:SS.mmm. The lower row is the
unambiguous 13-digit Unix epoch in milliseconds. The timestamp is sampled before
each frame is rendered, so a browser-visible frame can be compared with the
observer's wall clock without mistaking publisher startup for glass latency.
"""

from __future__ import annotations

import argparse
import datetime as dt
import signal
import sys
import time
from typing import BinaryIO, Iterable


SEGMENTS_BY_DIGIT = {
    "0": "ab cdef".replace(" ", ""),
    "1": "bc",
    "2": "abdeg",
    "3": "abcdg",
    "4": "bcfg",
    "5": "acdfg",
    "6": "acdefg",
    "7": "abc",
    "8": "abcdefg",
    "9": "abcdfg",
}

RGB = tuple[int, int, int]
RUNNING = True


def _stop(_signum: int, _frame: object) -> None:
    global RUNNING
    RUNNING = False


def _fill_rect(
    frame: bytearray,
    width: int,
    height: int,
    x: int,
    y: int,
    rect_width: int,
    rect_height: int,
    color: RGB,
) -> None:
    left = max(0, x)
    top = max(0, y)
    right = min(width, x + rect_width)
    bottom = min(height, y + rect_height)
    if left >= right or top >= bottom:
        return
    row = bytes(color) * (right - left)
    for row_y in range(top, bottom):
        offset = (row_y * width + left) * 3
        frame[offset : offset + len(row)] = row


def _digit_width(character: str, scale: int) -> int:
    return (3 if character in ":." else 8) * scale


def _text_width(text: str, scale: int) -> int:
    if not text:
        return 0
    gap = 2 * scale
    return sum(_digit_width(character, scale) for character in text) + gap * (len(text) - 1)


def _draw_digit(
    frame: bytearray,
    width: int,
    height: int,
    x: int,
    y: int,
    character: str,
    scale: int,
    color: RGB,
) -> None:
    thickness = 2 * scale
    if character == ":":
        _fill_rect(frame, width, height, x, y + 4 * scale, thickness, thickness, color)
        _fill_rect(frame, width, height, x, y + 9 * scale, thickness, thickness, color)
        return
    if character == ".":
        _fill_rect(frame, width, height, x, y + 12 * scale, thickness, thickness, color)
        return

    segments = SEGMENTS_BY_DIGIT[character]
    horizontal_width = 5 * scale
    vertical_height = 5 * scale
    rectangles = {
        "a": (x + scale, y, horizontal_width, thickness),
        "b": (x + 6 * scale, y + scale, thickness, vertical_height),
        "c": (x + 6 * scale, y + 8 * scale, thickness, vertical_height),
        "d": (x + scale, y + 13 * scale, horizontal_width, thickness),
        "e": (x, y + 8 * scale, thickness, vertical_height),
        "f": (x, y + scale, thickness, vertical_height),
        "g": (x + scale, y + 6 * scale, horizontal_width, thickness),
    }
    for segment in segments:
        _fill_rect(frame, width, height, *rectangles[segment], color)


def _draw_text(
    frame: bytearray,
    width: int,
    height: int,
    y: int,
    text: str,
    scale: int,
    color: RGB,
) -> None:
    gap = 2 * scale
    cursor = (width - _text_width(text, scale)) // 2
    for character in text:
        _draw_digit(frame, width, height, cursor, y, character, scale, color)
        cursor += _digit_width(character, scale) + gap


def timestamp_strings(epoch_millis: int) -> tuple[str, str]:
    local = dt.datetime.fromtimestamp(epoch_millis / 1000.0).astimezone()
    human = f"{local:%H:%M:%S}.{epoch_millis % 1000:03d}"
    epoch = f"{epoch_millis:013d}"
    return human, epoch


def render_frame(width: int, height: int, epoch_millis: int, frame_index: int) -> bytes:
    frame = bytearray(bytes((8, 16, 30)) * (width * height))

    # A moving, high-contrast publisher marker makes frozen or recovered streams
    # obvious even before reading the clock rows.
    bar_width = max(24, width // 12)
    bar_x = (frame_index * 7) % (width + bar_width) - bar_width
    _fill_rect(frame, width, height, bar_x, 0, bar_width, height, (18, 70, 112))
    _fill_rect(frame, width, height, 0, height // 2 - 2, width, 4, (48, 96, 132))

    human, epoch = timestamp_strings(epoch_millis)
    scale = max(2, min(4, width // 200, height // 80))
    digit_height = 15 * scale
    upper_y = max(8, height // 4 - digit_height // 2)
    lower_y = min(height - digit_height - 8, (height * 3) // 4 - digit_height // 2)
    _draw_text(frame, width, height, upper_y, human, scale, (70, 245, 255))
    _draw_text(frame, width, height, lower_y, epoch, scale, (255, 214, 64))

    # Frame-phase blocks make short stalls visible even when two timestamps round
    # to a similar human-readable value.
    phase = frame_index % 8
    for index in range(8):
        color = (240, 80, 80) if index == phase else (50, 38, 52)
        _fill_rect(frame, width, height, 8 + index * 18, height - 16, 12, 8, color)
    return bytes(frame)


def emit_frames(
    output: BinaryIO,
    width: int,
    height: int,
    fps: int,
    frame_limit: int,
) -> int:
    interval = 1.0 / fps
    next_frame_at = time.monotonic()
    frame_index = 0
    while RUNNING and (frame_limit == 0 or frame_index < frame_limit):
        epoch_millis = time.time_ns() // 1_000_000
        output.write(render_frame(width, height, epoch_millis, frame_index))
        output.flush()
        frame_index += 1

        next_frame_at += interval
        delay = next_frame_at - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        elif delay < -interval:
            # Do not produce a catch-up burst after a stalled consumer.
            next_frame_at = time.monotonic()
    return frame_index


def self_test() -> None:
    width = 320
    height = 180
    epoch_millis = 1_735_689_600_123
    frame = render_frame(width, height, epoch_millis, frame_index=5)
    if len(frame) != width * height * 3:
        raise RuntimeError("raw RGB frame size is incorrect")
    if len(set(frame)) < 5:
        raise RuntimeError("fixture frame does not contain the expected visual contrast")
    human, epoch = timestamp_strings(epoch_millis)
    if not human.endswith(".123") or epoch != "1735689600123":
        raise RuntimeError("fixture timestamp encoding is incorrect")


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be non-negative")
    return parsed


def parse_args(arguments: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--width", type=positive_int, default=640)
    parser.add_argument("--height", type=positive_int, default=360)
    parser.add_argument("--fps", type=positive_int, default=30)
    parser.add_argument("--frames", type=non_negative_int, default=0, help="0 streams forever")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(list(arguments))
    if args.width < 320 or args.height < 180:
        parser.error("width and height must be at least 320x180")
    if args.width % 2 or args.height % 2:
        parser.error("width and height must be even for yuv420p encoding")
    if args.fps > 60:
        parser.error("fps must not exceed 60")
    return args


def main(arguments: Iterable[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if arguments is None else arguments)
    if args.self_test:
        self_test()
        print("media_fixture_source=PASS")
        return 0

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    try:
        emit_frames(sys.stdout.buffer, args.width, args.height, args.fps, args.frames)
    except BrokenPipeError:
        # ffmpeg owns the read side. Its normal shutdown closes the pipe and is
        # the ownership-safe way to stop this helper too.
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
