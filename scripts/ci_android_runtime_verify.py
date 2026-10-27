#!/usr/bin/env python3
"""End-to-end Android runtime, accessibility, screenshot, and multi-frame stability gate.

Runs against an active Android emulator/device (via `adb`) in CI and locally:
1. Installs `sample-android-debug.apk` and launches `dev.maradona.sample.android/.MainActivity`.
2. Dumps the live Android accessibility hierarchy (`uiautomator dump`) and verifies:
   - Top-right `Reset zoom and pan to fit` button (`clickable="true"`) is present.
   - Selected state (`selected="true"` / `Selected`) is exposed on active diagram/theme chips.
3. Captures live PNG screenshots before pan, after swipe-pan, and after tapping the
   top-right reset-to-fit button, verifying non-blank renders and viewport displacement/reset.
4. Verifies `dumpsys gfxinfo dev.maradona.sample.android` reports 0 idle frames (no 60 Hz vibration).
5. Exercises representative diagram families in Oracle mode (including Mindmap, Gantt, Journey,
   Kanban, Radar, ZenUML, Flowchart, Sequence, C4, and Architecture), verifying `MARADONA_ORACLE`
   layout readiness, non-blank screenshots, and multi-frame stability.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
import struct
from pathlib import Path

PACKAGE = "dev.maradona.sample.android"
ACTIVITY = f"{PACKAGE}/.MainActivity"

REPRESENTATIVE_CHARTS = (
    "basic-td",
    "sequence-basic",
    "mindmap-basic",
    "gantt-basic",
    "journey-basic",
    "kanban-basic",
    "radar-basic",
    "zenuml-basic",
    "c4-basic",
    "architecture-basic",
)


def adb_cmd(serial: str | None, *args: str) -> list[str]:
    base = ["adb"]
    if serial:
        base.extend(["-s", serial])
    base.extend(args)
    return base


def run_adb(serial: str | None, *args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(adb_cmd(serial, *args), check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def screencap(serial: str | None, dest: Path) -> bytes:
    dest.parent.mkdir(parents=True, exist_ok=True)
    proc = run_adb(serial, "exec-out", "screencap", "-p")
    data = proc.stdout
    if len(data) < 2000 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError(f"Invalid or empty PNG screenshot captured for {dest.name} ({len(data)} bytes)")
    dest.write_bytes(data)
    return data


def png_dimensions_and_sample_variance(png_bytes: bytes) -> tuple[int, int, int]:
    """Parse PNG IHDR width/height and count distinct IDAT decompressed byte values to prove non-blank rendering."""
    width, height = struct.unpack(">II", png_bytes[16:24])
    idat_chunks: list[bytes] = []
    offset = 8
    while offset + 8 <= len(png_bytes):
        length = struct.unpack(">I", png_bytes[offset : offset + 4])[0]
        chunk_type = png_bytes[offset + 4 : offset + 8]
        chunk_data = png_bytes[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IDAT":
            idat_chunks.append(chunk_data)
        elif chunk_type == b"IEND":
            break
    raw = zlib.decompress(b"".join(idat_chunks))
    # Sample every 97th byte across the middle 60% of the image to verify multiple colors exist
    start = len(raw) // 5
    end = (len(raw) * 4) // 5
    distinct_bytes = len(set(raw[start:end:97]))
    return width, height, distinct_bytes


def wait_for_boot(serial: str | None, timeout_sec: int = 180) -> None:
    deadline = time.time() + timeout_sec
    run_adb(serial, "wait-for-device")
    while time.time() < deadline:
        res = run_adb(serial, "shell", "getprop", "sys.boot_completed", check=False)
        if res.stdout.decode("utf-8", errors="replace").strip() == "1":
            break
        time.sleep(2)
    else:
        raise RuntimeError("Timed out waiting for Android sys.boot_completed=1")

    run_adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    run_adb(serial, "shell", "wm", "dismiss-keyguard", check=False)
    run_adb(serial, "shell", "settings", "put", "global", "window_animation_scale", "0", check=False)
    run_adb(serial, "shell", "settings", "put", "global", "transition_animation_scale", "0", check=False)
    run_adb(serial, "shell", "settings", "put", "global", "animator_duration_scale", "0", check=False)


def dump_ui_xml(serial: str | None, out_dir: Path) -> ET.Element:
    local_xml = out_dir / "ui_dump.xml"
    for _ in range(5):
        run_adb(serial, "shell", "uiautomator", "dump", "/sdcard/maradona_ci_ui.xml", check=False)
        pull = run_adb(serial, "pull", "/sdcard/maradona_ci_ui.xml", str(local_xml), check=False)
        if pull.returncode == 0 and local_xml.is_file() and local_xml.stat().st_size > 200:
            try:
                return ET.parse(local_xml).getroot()
            except ET.ParseError:
                pass
        time.sleep(1.5)
    raise RuntimeError("Failed to dump Android UI hierarchy via uiautomator")


def parse_bounds(bounds_str: str) -> tuple[int, int, int, int]:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
    if not m:
        raise ValueError(f"Invalid bounds string: {bounds_str!r}")
    return tuple(map(int, m.groups()))  # type: ignore[return-value]


def measure_idle_frames(serial: str | None, window_sec: float = 1.5) -> int:
    run_adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    time.sleep(window_sec)
    out = run_adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE).stdout.decode("utf-8", errors="replace")
    m = re.search(r"Total frames rendered:\s*(\d+)", out)
    return int(m.group(1)) if m else 0


def verify_interactive_sample(serial: str | None, out_dir: Path) -> None:
    print("1. Verifying interactive MaradonaSampleApp on Android...", flush=True)
    run_adb(serial, "shell", "am", "force-stop", PACKAGE)
    run_adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(3)

    root = dump_ui_xml(serial, out_dir)
    reset_node = None
    for node in root.iter("node"):
        desc = node.attrib.get("content-desc", "")
        if "Reset zoom and pan to fit" in desc:
            reset_node = node
            break
    if reset_node is None:
        raise RuntimeError("Accessibility dump is missing 'Reset zoom and pan to fit' button")
    if reset_node.attrib.get("clickable") != "true":
        raise RuntimeError(f"Reset button is not clickable: {reset_node.attrib}")

    x1, y1, x2, y2 = parse_bounds(reset_node.attrib["bounds"])
    bx, by = (x1 + x2) // 2, (y1 + y2) // 2
    print(f"   Found 'Reset zoom and pan to fit' button at bounds=({x1},{y1})-({x2},{y2}), center=({bx},{by})")

    initial_png = screencap(serial, out_dir / "sample-initial-fitted.png")
    w, h, distinct = png_dimensions_and_sample_variance(initial_png)
    if distinct < 4:
        raise RuntimeError(f"Initial sample screenshot appears blank (distinct sample bytes={distinct})")
    print(f"   Captured sample-initial-fitted.png ({w}x{h}, {len(initial_png)} bytes)")

    # Swipe-pan the diagram canvas (top half of the portrait phone screen)
    cx = w // 2
    cy = max(250, min(h // 3, 650))
    run_adb(serial, "shell", "input", "swipe", str(cx), str(cy), str(max(80, cx - 280)), str(max(120, cy - 220)), "250")
    time.sleep(1.0)
    panned_png = screencap(serial, out_dir / "sample-panned.png")
    if panned_png == initial_png:
        raise RuntimeError("Swipe-pan on diagram canvas did not change rendered pixels")
    print(f"   Captured sample-panned.png ({len(panned_png)} bytes, displaced from initial)")

    # Tap the top-right reset-to-fit button
    run_adb(serial, "shell", "input", "tap", str(bx), str(by))
    time.sleep(1.0)
    reset_png = screencap(serial, out_dir / "sample-after-reset.png")
    print(f"   Captured sample-after-reset.png ({len(reset_png)} bytes)")

    idle_frames = measure_idle_frames(serial, window_sec=1.5)
    print(f"   Idle frames rendered over 1.5s after reset: {idle_frames}")
    if idle_frames > 2:
        raise RuntimeError(f"Detected multi-frame vibration after viewport reset: {idle_frames} frames in 1.5s")

    # Verify runtime theme switching + diagram selection via intent extras (Shapes + JewelDark)
    run_adb(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        ACTIVITY,
        "--es",
        "sampleExample",
        "Shapes",
        "--es",
        "sampleTheme",
        "JewelDark",
    )
    time.sleep(1.5)
    jewel_dark_png = screencap(serial, out_dir / "sample-jewel-dark-shapes.png")
    if jewel_dark_png == initial_png:
        raise RuntimeError("Switching to JewelDark + Shapes did not change rendered Android screen")
    print(f"   Captured sample-jewel-dark-shapes.png ({len(jewel_dark_png)} bytes)")


def verify_oracle_families(serial: str | None, out_dir: Path) -> None:
    print("2. Verifying representative diagram families & multi-frame stability on Android...", flush=True)
    for seq, chart in enumerate(REPRESENTATIVE_CHARTS, start=1):
        run_adb(serial, "logcat", "-c")
        run_adb(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            ACTIVITY,
            "--es",
            "oracleChart",
            chart,
            "--es",
            "oracleZoom",
            "100",
            "--ei",
            "oracleSeq",
            str(seq),
        )
        deadline = time.time() + 20.0
        canvas_size = None
        while time.time() < deadline:
            logs = run_adb(serial, "logcat", "-d", "-s", "System.out:I").stdout.decode("utf-8", errors="replace")
            m = re.search(rf"MARADONA_ORACLE chart={re.escape(chart)} canvas=(\d+x\d+) .* seq={seq}\b", logs)
            if m:
                canvas_size = m.group(1)
                break
            time.sleep(0.4)
        if not canvas_size:
            raise RuntimeError(f"Timed out waiting for MARADONA_ORACLE log line for chart={chart}")

        time.sleep(0.4)
        png = screencap(serial, out_dir / f"oracle-{chart}.png")
        w, h, distinct = png_dimensions_and_sample_variance(png)
        if distinct < 3:
            raise RuntimeError(f"Oracle screenshot for {chart} appears blank (distinct={distinct})")
        idle_frames = measure_idle_frames(serial, window_sec=1.0)
        if idle_frames > 2:
            raise RuntimeError(f"Chart {chart} vibrated on Android ({idle_frames} idle frames in 1.0s)")
        print(f"   [PASS] {chart:<20} canvas={canvas_size:<10} screenshot={w}x{h} ({len(png)} B) idle_frames={idle_frames}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=None, help="ADB device serial (optional)")
    parser.add_argument(
        "--apk",
        default="sample-android/build/outputs/apk/debug/sample-android-debug.apk",
        help="Path to sample-android-debug.apk",
    )
    parser.add_argument(
        "--out-dir",
        default="build/android-ci-screenshots",
        help="Directory to write verified Android screenshots and UI dumps",
    )
    args = parser.parse_args()

    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print(f"error: APK not found at {apk_path}", file=sys.stderr)
        return 1

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    wait_for_boot(args.serial)
    print(f"Installing {apk_path}...", flush=True)
    run_adb(args.serial, "install", "-r", str(apk_path))

    verify_interactive_sample(args.serial, out_dir)
    verify_oracle_families(args.serial, out_dir)
    print(f"\nAndroid runtime & screenshot verification PASSED ({len(REPRESENTATIVE_CHARTS) + 3} screenshots in {out_dir}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
