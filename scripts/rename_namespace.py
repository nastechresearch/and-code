#!/usr/bin/env python3
"""Safely migrate an Android/Kotlin namespace throughout a repository.

The script replaces text in every repository file that can be decoded as UTF-8,
skips VCS/build output and binary files, and then moves source directories whose
path contains the old Java package path. It is intentionally idempotent.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_OLD = "com." + "yugahashimoto" + ".andcode"
DEFAULT_NEW = "com." + "nastechresearch" + ".andcode"
SKIP_DIRS = {".git", ".gradle", "build", "out", "target", "node_modules"}
SKIP_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".pdf",
    ".mp3", ".wav", ".mp4", ".mov", ".zip", ".tar", ".gz", ".7z",
    ".jar", ".aar", ".so", ".a", ".class", ".dex", ".apk", ".keystore",
    ".jks", ".ttf", ".otf", ".woff", ".woff2",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--old", default=DEFAULT_OLD)
    parser.add_argument("--new", default=DEFAULT_NEW)
    parser.add_argument("--check-only", action="store_true")
    return parser.parse_args()


def git_files(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [root / item for item in result.stdout.decode().split("\0") if item]


def is_skipped(path: Path, root: Path) -> bool:
    relative = path.relative_to(root)
    if any(part in SKIP_DIRS for part in relative.parts):
        return True
    return path.suffix.lower() in SKIP_SUFFIXES


def read_text(path: Path) -> str | None:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise RuntimeError(f"Cannot read {path}: {exc}") from exc
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def replace_text(root: Path, old: str, new: str, check_only: bool) -> tuple[int, int]:
    changed_files = 0
    replacements = 0
    for path in git_files(root):
        if not path.is_file() or is_skipped(path, root):
            continue
        text = read_text(path)
        if text is None or old not in text:
            continue
        count = text.count(old)
        updated = text.replace(old, new)
        if not check_only:
            temporary = path.with_name(path.name + ".namespace-migration.tmp")
            temporary.write_text(updated, encoding="utf-8", newline="")
            os.replace(temporary, path)
        changed_files += 1
        replacements += count
        print(f"{'would update' if check_only else 'updated'}: {path.relative_to(root)} ({count})")
    return changed_files, replacements


def move_package_dirs(root: Path, old: str, new: str, check_only: bool) -> int:
    old_path = Path(*old.split("."))
    new_path = Path(*new.split("."))
    moved = 0
    candidates: list[Path] = []
    for source_root in (root / "app" / "src", root / "benchmark" / "src"):
        if not source_root.exists():
            continue
        candidates.extend(
            path for path in source_root.rglob(str(old_path)) if path.is_dir()
        )
    # Deepest-first avoids moving a parent before any nested source tree.
    for source in sorted(set(candidates), key=lambda p: len(p.parts), reverse=True):
        # source is .../<source-root>/com/yugahashimoto/andcode; the new
        # package must be rooted at .../<source-root>, not inside the old
        # package's parent directory.
        destination = source.parents[len(old_path.parts)] / new_path
        if destination.exists():
            raise RuntimeError(f"Refusing to overwrite existing directory: {destination}")
        print(f"{'would move' if check_only else 'moving'}: {source.relative_to(root)} -> {destination.relative_to(root)}")
        if not check_only:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(destination))
        moved += 1
    return moved


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    if not (root / ".git").exists():
        raise SystemExit(f"Not a Git repository: {root}")
    if not args.old or not args.new or args.old == args.new:
        raise SystemExit("Old and new namespaces must be non-empty and different")

    print(f"Repository: {root}")
    print(f"Namespace: {args.old} -> {args.new}")
    changed_files, replacements = replace_text(root, args.old, args.new, args.check_only)
    moved_dirs = move_package_dirs(root, args.old, args.new, args.check_only)
    print(f"Summary: {changed_files} file(s), {replacements} replacement(s), {moved_dirs} directory move(s)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
