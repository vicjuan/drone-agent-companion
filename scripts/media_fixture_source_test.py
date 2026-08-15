#!/usr/bin/env python3
"""Stdlib-only tests for the issue #7A raw-RGB fixture source."""

from __future__ import annotations

import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import unittest


sys.dont_write_bytecode = True
SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
SOURCE_PATH = SCRIPT_DIR / "media_fixture_source.py"

SPEC = importlib.util.spec_from_file_location("media_fixture_source", SOURCE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load fixture source: {SOURCE_PATH}")
SOURCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SOURCE)


class MediaFixtureSourceTest(unittest.TestCase):
    def test_timestamp_contains_human_clock_and_unambiguous_epoch_millis(self) -> None:
        human, epoch = SOURCE.timestamp_strings(1_735_689_600_123)

        self.assertRegex(human, re.compile(r"^\d{2}:\d{2}:\d{2}\.123$"))
        self.assertEqual("1735689600123", epoch)

    def test_rendered_frame_has_exact_rgb24_size_and_visible_motion(self) -> None:
        width = 320
        height = 180

        first = SOURCE.render_frame(width, height, 1_735_689_600_123, frame_index=0)
        second = SOURCE.render_frame(width, height, 1_735_689_600_156, frame_index=1)

        self.assertEqual(width * height * 3, len(first))
        self.assertEqual(width * height * 3, len(second))
        self.assertNotEqual(first, second)
        self.assertGreaterEqual(len(set(first)), 8)

    def test_finite_cli_writes_exactly_the_requested_frames(self) -> None:
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"

        result = subprocess.run(
            [
                sys.executable,
                str(SOURCE_PATH),
                "--width",
                "320",
                "--height",
                "180",
                "--fps",
                "60",
                "--frames",
                "2",
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            timeout=5,
        )

        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
        self.assertEqual(320 * 180 * 3 * 2, len(result.stdout))
        self.assertEqual(b"", result.stderr)

    def test_cli_rejects_dimensions_that_ffmpeg_cannot_encode_as_yuv420p(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SOURCE_PATH), "--width", "321", "--height", "180", "--frames", "1"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
        )

        self.assertEqual(2, result.returncode)
        self.assertIn(b"must be even", result.stderr)
        self.assertEqual(b"", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
