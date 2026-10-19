#!/usr/bin/env python3
"""Guards the oracle against measuring renders that were never regenerated.

The renders are written by the test task, outside Gradle's declared outputs, so
Gradle is free to consider the task up to date and skip it. When that happens the
comparison happily re-measures the previous run and reports PASS - which is how a
whole round of rendering fixes was "verified" against two-hour-old images.

Every comparison therefore checks that each render is newer than the newest
source that can change it, and fails with an actionable message instead.
"""
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

# Sources whose change must invalidate a render.
SOURCE_GLOBS = (
    "maradona/src/**/*.kt",
    "sample-shared/src/**/*.kt",
    "sample-desktop/src/**/*.kt",
    "oracle/charts/*.mmd",
    "oracle/goldens/*.png",
    "oracle/tools/sync_charts.py",
)


def newest_source_mtime():
    newest = 0.0
    for pattern in SOURCE_GLOBS:
        for path in ROOT.glob(pattern):
            newest = max(newest, path.stat().st_mtime)
    return newest


def stale_renders(directory, pattern="*.png"):
    """Renders older than the newest source, or an empty list."""
    newest = newest_source_mtime()
    stale = []
    for path in sorted(pathlib.Path(directory).glob(pattern)):
        if path.stat().st_mtime < newest:
            stale.append(path.name)
    return stale


def require_fresh(directory, hint):
    stale = stale_renders(directory)
    if not stale:
        return True
    print(
        f"STALE: {len(stale)} render(s) in {pathlib.Path(directory).name} are older than the "
        f"sources they are supposed to come from, e.g. {', '.join(stale[:3])}",
        file=__import__("sys").stderr,
    )
    print(f"  Re-render before comparing: {hint}", file=__import__("sys").stderr)
    return False
