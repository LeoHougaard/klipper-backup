"""Capture repeatable real-device UI states against the Moonraker fixture.

No commands reach a real printer. Screens and accessibility bounds are kept in
the ignored output directory; never open credential-sharing or hotspot dialogs.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from check_controls import adb, snapshot, tap, ADB, PACKAGE, ADB_COMMAND
from fixture_server import Fixture


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--candidate", action="store_true")
    parser.add_argument("--reverse", action="store_true", help="Activity-only reverse landscape; no system setting changes")
    parser.add_argument("--quality", action="store_true", help="Additional heating/file edge states and real camera-on job views")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = Fixture()
    fixture.status["fan"] = {"speed": .35}
    fixture.status["gcode_move"] = {"homing_origin": [0, 0, -.025, 0], "speed_factor": 1, "extrude_factor": 1}
    fixture.status["toolhead"]["position"] = [60, 60, 5, 0]
    fixture.status["toolhead"]["homed_axes"] = "xyz"
    server = fixture.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    captures = []

    def wait_for(predicate, description):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            root = snapshot()
            if predicate(root):
                return root
        raise AssertionError("UI did not settle: " + description)

    def navigate(label):
        tap(label)
        if args.candidate:
            wait_for(lambda root: any(n.get("content-desc") == "Screen: " + label for n in root.iter("node")), label)

    def capture(name):
        root = snapshot()
        data = []
        for node in root.iter("node"):
            if node.get("text") or node.get("clickable") == "true":
                data.append({k: node.get(k) for k in ("text", "content-desc", "class", "bounds", "enabled", "clickable", "selected")})
        (args.output / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        image = subprocess.check_output([*ADB_COMMAND, "exec-out", "screencap", "-p"])
        (args.output / f"{name}.png").write_bytes(image)
        captures.append(name)
        print("Captured", name, flush=True)

    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}", "--ez", "test_reverse_landscape", str(args.reverse).lower())
        # Each fresh accessibility snapshot also waits for the UI to settle.
        if args.candidate:
            wait_for(lambda root: any(n.get("text") == "●  Ready" for n in root.iter("node")), "ready idle")
        else:
            snapshot()
        capture("01-idle")
        if args.quality:
            fixture.status["extruder"].update(temperature=72, target=240)
            fixture.status["heater_bed"].update(temperature=52, target=100)
            wait_for(lambda root: any(n.get("text") == "●  Heating" for n in root.iter("node")), "heating")
            capture("22-heating")
            fixture.status["extruder"].update(temperature=22, target=0)
            fixture.status["heater_bed"].update(temperature=23, target=0)
            wait_for(lambda root: any(n.get("text") == "●  Ready" for n in root.iter("node")), "ready again")
        if args.candidate:
            tap("Set nozzle temperature")
            capture("16-temperature")
            tap("Close")
        for label, name in ([("Prepare", "02-control"), ("Files", "03-files"), ("Camera", "04-camera"), ("More", "05-settings")] if args.candidate else [("Control", "02-control"), ("Files", "03-files"), ("Camera", "04-camera"), ("Settings", "05-settings")]):
            navigate(label)
            capture(name)
            if args.candidate and label == "Files":
                tap("Print details: test-plate.gcode")
                capture("17-file-details")
                tap("Close")
                if args.quality:
                    tap("Print details: Voron_parts/HeatCore_4_toolhead_front_ABS_0.2mm.gcode")
                    capture("24-long-file-details")
                    tap("Close")
                    saved_files = fixture.files
                    fixture.files = [saved_files[-1]]
                    tap("Refresh")
                    wait_for(lambda root: any(n.get("content-desc") == "Print details: Bracket_without_metadata.gcode" for n in root.iter("node")), "missing metadata file")
                    tap("Print details: Bracket_without_metadata.gcode")
                    capture("23-no-preview-details")
                    tap("Close")
                    fixture.files = saved_files
        if args.candidate:
            navigate("Prepare")
            tap("Filament")
            wait_for(lambda root: any(n.get("text") == "Change filament" for n in root.iter("node")), "filament")
            capture("09-filament-cold")
            tap("More")
            tap("Maintenance")
            tap("Bed calibration")
            wait_for(lambda root: any(n.get("text") == "Front / rear bed calibration" for n in root.iter("node")), "bed setup")
            capture("10-bed-setup")
            tap("View heightmap")
            capture("18-heightmap")
            tap("Close")
            fixture.status["manual_probe"] = {"is_active": True, "z_position": .125}
            fixture.status["y_axis_z_offset"]["calibration_point"] = 0
            wait_for(lambda root: any(n.get("text") == "Front paper contact" for n in root.iter("node")), "front paper")
            capture("11-front-paper")
            fixture.status["manual_probe"]["is_active"] = False
            fixture.status["y_axis_z_offset"]["calibration_point"] = None
        fixture.status["print_stats"].update(state="printing", filename="Voron_parts/HeatCore_4_toolhead_front_ABS_0.2mm.gcode", print_duration=3760, info={"current_layer": 3, "total_layer": 168})
        fixture.status["virtual_sdcard"]["progress"] = .27
        fixture.status["extruder"].update(temperature=239.6, target=240, can_extrude=True)
        fixture.status["heater_bed"].update(temperature=99.8, target=100)
        navigate("Home" if args.candidate else "Print")
        if args.candidate:
            wait_for(lambda root: any(n.get("text") == "●  Printing" for n in root.iter("node")), "printing")
        else:
            snapshot()
        capture("06-printing")
        if args.candidate:
            navigate("Camera")
            if args.quality:
                tap("Turn camera on")
                wait_for(lambda root: any("●  Live" in n.get("text", "") for n in root.iter("node")), "live native camera")
            capture("19-camera-printing")
            navigate("Home")
            tap("Tune print")
            capture("12-live-tuning")
            tap("Close")
        fixture.status["print_stats"]["state"] = "paused"
        if args.candidate:
            wait_for(lambda root: any(n.get("text") == "●  Print paused" for n in root.iter("node")), "paused")
        else:
            snapshot()
        capture("07-paused")
        if args.candidate:
            navigate("Camera")
            capture("20-camera-paused")
            if args.quality:
                tap("Turn camera off")
            navigate("Home")
            if args.quality:
                tap("Prepare")
                tap("Filament")
                capture("25-paused-filament")
                navigate("Home")
            fixture.status["print_stats"]["state"] = "complete"
            wait_for(lambda root: any(n.get("text") == "●  Print complete" for n in root.iter("node")), "complete")
            capture("13-complete")
            fixture.status["print_stats"].update(state="error", message="Print interrupted: extrusion stopped")
            wait_for(lambda root: any(n.get("text") == "●  Print failed" for n in root.iter("node")), "failed print")
            capture("15-failed-print")
            fixture.status["webhooks"].update(state="shutdown", state_message="Printer is shutdown: Heater verification failed")
            wait_for(lambda root: any(n.get("text") == "●  Printer shutdown" for n in root.iter("node")), "shutdown")
            capture("14-shutdown")
        # Force an HTTP failure for the same app connection, with no Wi-Fi changes.
        fixture.status.clear()
        if args.candidate:
            wait_for(lambda root: any(n.get("text") == "●  Disconnected" for n in root.iter("node")), "disconnected")
        else:
            snapshot()
        capture("08-unavailable")
        assert fixture.requests == [], "Screenshot collection unexpectedly issued printer commands"
        (args.output / "manifest.json").write_text(json.dumps({"states": captures, "printer_commands": 0, "endpoint": "isolated fixture"}, indent=2), encoding="utf-8")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
