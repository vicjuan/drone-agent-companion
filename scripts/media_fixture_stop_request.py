#!/usr/bin/env python3
"""Create and retire nonce-scoped stop requests without touching any process."""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import secrets
import stat
import sys
from typing import Iterable


NONCE_PATTERN = re.compile(r"^[0-9a-f]{64}$")
RUN_NONCE_NAME = "run-nonce"
STOP_REQUEST_PREFIX = "stop-request."


class ControlStateError(RuntimeError):
    pass


def validate_state_directory(state_dir: pathlib.Path) -> pathlib.Path:
    try:
        metadata = state_dir.lstat()
    except FileNotFoundError as error:
        raise ControlStateError(f"state directory is missing: {state_dir}") from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise ControlStateError(f"state path must be a real directory: {state_dir}")
    if metadata.st_uid != os.getuid():
        raise ControlStateError(f"state directory is not owned by uid {os.getuid()}")
    if stat.S_IMODE(metadata.st_mode) != 0o700:
        raise ControlStateError("state directory mode must be exactly 0700")
    return state_dir.resolve(strict=True)


def _write_exclusive(path: pathlib.Path, value: str) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii", closefd=True) as output:
            output.write(f"{value}\n")
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        raise


def _read_nonce(path: pathlib.Path) -> str:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except FileNotFoundError as error:
        raise ControlStateError(f"active run nonce is missing: {path}") from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ControlStateError(f"control file must be regular: {path}")
        if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ControlStateError(f"control file ownership/mode is invalid: {path}")
        payload = os.read(descriptor, 67)
    finally:
        os.close(descriptor)
    lines = payload.decode("ascii").splitlines()
    if len(lines) != 1 or NONCE_PATTERN.fullmatch(lines[0]) is None:
        raise ControlStateError(f"control file does not contain one fixed-width nonce: {path}")
    return lines[0]


def initialize_run(state_dir: pathlib.Path) -> str:
    state_dir = validate_state_directory(state_dir)
    nonce_path = state_dir / RUN_NONCE_NAME
    if nonce_path.exists() or nonce_path.is_symlink():
        raise ControlStateError("an active or stale run nonce already exists")
    nonce = secrets.token_hex(32)
    _write_exclusive(nonce_path, nonce)
    return nonce


def request_stop(state_dir: pathlib.Path) -> pathlib.Path:
    state_dir = validate_state_directory(state_dir)
    nonce = _read_nonce(state_dir / RUN_NONCE_NAME)
    request_path = state_dir / f"{STOP_REQUEST_PREFIX}{nonce}"
    try:
        _write_exclusive(request_path, nonce)
    except FileExistsError:
        existing = _read_nonce(request_path)
        if existing != nonce:
            raise ControlStateError("existing stop request does not match the active run")
    return request_path


def finish_run(state_dir: pathlib.Path, nonce: str) -> None:
    if NONCE_PATTERN.fullmatch(nonce) is None:
        raise ControlStateError("finish nonce is malformed")
    state_dir = validate_state_directory(state_dir)
    nonce_path = state_dir / RUN_NONCE_NAME
    active_nonce = _read_nonce(nonce_path)
    if active_nonce != nonce:
        raise ControlStateError("finish nonce does not own the active run")

    request_path = state_dir / f"{STOP_REQUEST_PREFIX}{nonce}"
    if request_path.exists() or request_path.is_symlink():
        request_nonce = _read_nonce(request_path)
        if request_nonce != nonce:
            raise ControlStateError("stop request does not belong to the active run")
        request_path.unlink()
    nonce_path.unlink()


def parse_args(arguments: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("init", "request"):
        child = subparsers.add_parser(command)
        child.add_argument("--state-dir", type=pathlib.Path, required=True)
    finish = subparsers.add_parser("finish")
    finish.add_argument("--state-dir", type=pathlib.Path, required=True)
    finish.add_argument("--nonce", required=True)
    return parser.parse_args(list(arguments))


def main(arguments: Iterable[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if arguments is None else arguments)
    try:
        if args.command == "init":
            print(initialize_run(args.state_dir))
        elif args.command == "request":
            print(request_stop(args.state_dir))
        else:
            finish_run(args.state_dir, args.nonce)
    except (ControlStateError, OSError, UnicodeError) as error:
        print(f"[media-stop-request] ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
