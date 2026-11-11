"""Matched Prepare screenshots on the actual Pixel; no physical printer access."""
import argparse
import json
import subprocess
import threading
from pathlib import Path
from check_controls import adb, snapshot, tap, ADB, PACKAGE
from check_workflows import wait_text, wait_until
from fixture_server import Fixture


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    f = Fixture()
    server = f.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    def capture(name):
        root = snapshot()
        data = [{k:n.get(k) for k in ("text", "content-desc", "bounds", "enabled", "clickable", "selected")}
                for n in root.iter("node") if n.get("text") or n.get("clickable") == "true"]
        (args.output / (name + ".json")).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        (args.output / (name + ".png")).write_bytes(subprocess.check_output([str(ADB), "exec-out", "screencap", "-p"]))
        print("Captured", name, flush=True)
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        wait_text("22°")
        tap("Prepare")
        capture("01-move-unhomed")
        f.status["toolhead"].update(homed_axes="xyz", position=[60,60,5,0])
        wait_text("X 60.0")
        capture("02-move-homed")
        tap("Filament")
        capture("03-filament-cold")
        tap("ABS 240°")
        f.status["extruder"].update(temperature=73, target=240)
        wait_text("73°")
        capture("08-filament-heating")
        f.status["print_stats"].update(state="paused", filename="test-plate.gcode")
        f.status["extruder"].update(temperature=239.6, target=240, can_extrude=True)
        wait_text("Print paused")
        tap("Move")
        tap("Filament")
        capture("04-filament-paused")
        f.status["print_stats"]["state"] = "standby"
        tap("Bed setup")
        capture("05-bed-setup")
        f.status["manual_probe"].update(is_active=True, z_position=.125)
        f.status["y_axis_z_offset"]["calibration_point"] = 0
        wait_text("Front paper contact")
        capture("06-front-contact")
        f.status["y_axis_z_offset"]["calibration_point"] = 1
        wait_text("Rear paper contact")
        capture("07-rear-contact")
        assert not f.requests, "Capture issued a printer command"
        (args.output / "manifest.json").write_text(json.dumps({"states":8,"physical_commands":0,"fixture_commands":0}), encoding="utf-8")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")

if __name__ == "__main__":
    main()
