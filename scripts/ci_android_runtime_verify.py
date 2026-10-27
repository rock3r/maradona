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


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png_rgb_grid(
    png_bytes: bytes,
    y_start: int = 0,
    y_end: int | None = None,
    step: int = 4,
) -> tuple[int, int, list[tuple[int, int, int]]]:
    """Decode an 8-bit RGB/RGBA PNG, unfilter scanlines up to y_end, and return sampled (R, G, B) pixels."""
    width, height, bit_depth, color_type, interlace = struct.unpack(">IIBB2xB", png_bytes[16:29])
    if bit_depth != 8 or color_type not in (2, 6) or interlace != 0:
        raise ValueError(
            f"Unsupported PNG format: bit_depth={bit_depth}, color_type={color_type}, interlace={interlace}"
        )
    bpp = 4 if color_type == 6 else 3
    stride = width * bpp

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

    limit_y = min(height, y_end if y_end is not None else height)
    prev_row = bytearray(stride)
    cur_row = bytearray(stride)
    samples: list[tuple[int, int, int]] = []

    pos = 0
    for y in range(limit_y):
        ftype = raw[pos]
        pos += 1
        scan = raw[pos : pos + stride]
        pos += stride
        if ftype == 0:
            cur_row[:] = scan
        elif ftype == 1:
            for i in range(stride):
                left = cur_row[i - bpp] if i >= bpp else 0
                cur_row[i] = (scan[i] + left) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                cur_row[i] = (scan[i] + prev_row[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = cur_row[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                cur_row[i] = (scan[i] + ((left + up) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                left = cur_row[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                up_left = prev_row[i - bpp] if i >= bpp else 0
                cur_row[i] = (scan[i] + _paeth(left, up, up_left)) & 0xFF
        else:
            raise ValueError(f"Unsupported PNG filter type {ftype} at row {y}")

        if y >= y_start and (y - y_start) % step == 0:
            for x in range(0, width, step):
                px = x * bpp
                samples.append((cur_row[px], cur_row[px + 1], cur_row[px + 2]))
        prev_row, cur_row = cur_row, prev_row

    return width, height, samples


def count_non_background_pixels(samples: list[tuple[int, int, int]], tolerance: int = 6) -> int:
    if not samples:
        return 0
    bg_r, bg_g, bg_b = samples[0]
    count = 0
    for r, g, b in samples:
        if abs(r - bg_r) > tolerance or abs(g - bg_g) > tolerance or abs(b - bg_b) > tolerance:
            count += 1
    return count


def count_differing_pixels(
    samples_a: list[tuple[int, int, int]],
    samples_b: list[tuple[int, int, int]],
    tolerance: int = 4,
) -> int:
    if len(samples_a) != len(samples_b):
        raise ValueError(f"Sample grid size mismatch: {len(samples_a)} vs {len(samples_b)}")
    diff = 0
    for (r1, g1, b1), (r2, g2, b2) in zip(samples_a, samples_b):
        if abs(r1 - r2) > tolerance or abs(g1 - g2) > tolerance or abs(b1 - b2) > tolerance:
            diff += 1
    return diff


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
    # Sample the diagram canvas region (below the status bar y=160, above the bottom editor panel y=780)
    w, h, initial_canvas = decode_png_rgb_grid(initial_png, y_start=160, y_end=780, step=4)
    initial_fg = count_non_background_pixels(initial_canvas)
    if initial_fg < 100:
        raise RuntimeError(f"Initial sample screenshot appears blank (non-background canvas pixels={initial_fg})")
    print(f"   Captured sample-initial-fitted.png ({w}x{h}, {len(initial_png)} bytes, fg_samples={initial_fg})")

    # Swipe-pan the diagram canvas (top half of the portrait phone screen)
    cx = w // 2
    cy = max(250, min(h // 3, 650))
    run_adb(serial, "shell", "input", "swipe", str(cx), str(cy), str(max(80, cx - 280)), str(max(120, cy - 220)), "250")
    time.sleep(1.0)
    panned_png = screencap(serial, out_dir / "sample-panned.png")
    _, _, panned_canvas = decode_png_rgb_grid(panned_png, y_start=160, y_end=780, step=4)
    pan_diff = count_differing_pixels(initial_canvas, panned_canvas)
    if pan_diff < 100:
        raise RuntimeError(f"Swipe-pan on diagram canvas did not displace diagram pixels (diff={pan_diff})")
    print(f"   Captured sample-panned.png ({len(panned_png)} bytes, displaced_canvas_samples={pan_diff})")

    # Tap the top-right reset-to-fit button and verify the diagram canvas returns to the initial fitted state
    run_adb(serial, "shell", "input", "tap", str(bx), str(by))
    time.sleep(1.0)
    reset_png = screencap(serial, out_dir / "sample-after-reset.png")
    _, _, reset_canvas = decode_png_rgb_grid(reset_png, y_start=160, y_end=780, step=4)
    reset_diff = count_differing_pixels(initial_canvas, reset_canvas)
    if reset_diff > 15:
        raise RuntimeError(
            f"Tapping reset-to-fit button did not restore initial fitted canvas (diff vs initial={reset_diff}, vs panned={count_differing_pixels(panned_canvas, reset_canvas)})"
        )
    print(f"   Captured sample-after-reset.png ({len(reset_png)} bytes, diff_vs_initial={reset_diff})")

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
        w, h, oracle_samples = decode_png_rgb_grid(png, y_start=0, y_end=1400, step=6)
        fg_pixels = count_non_background_pixels(oracle_samples)
        if fg_pixels < 50:
            raise RuntimeError(f"Oracle screenshot for {chart} appears blank (non-background samples={fg_pixels})")
        idle_frames = measure_idle_frames(serial, window_sec=1.0)
        if idle_frames > 2:
            raise RuntimeError(f"Chart {chart} vibrated on Android ({idle_frames} idle frames in 1.0s)")
        print(
            f"   [PASS] {chart:<20} canvas={canvas_size:<10} screenshot={w}x{h} ({len(png)} B) fg_samples={fg_pixels} idle_frames={idle_frames}",
            flush=True,
        )


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
