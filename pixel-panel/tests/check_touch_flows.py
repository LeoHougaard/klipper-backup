"""Actual Pixel: action-time errors, touch keypad, Back, search and keypad E-stop.

Every printer request goes to an isolated fixture, never physical hardware.
"""
import json
import re
import subprocess
import threading
from pathlib import Path
from check_controls import adb, snapshot, tap, ADB, PACKAGE, verify_fixture, ADB_COMMAND
from check_workflows import wait_text, wait_until, scripts
from fixture_server import Fixture


def main():
    output = Path("pixel-panel/.build/design/touch-flows")
    output.mkdir(parents=True, exist_ok=True)
    f = Fixture()
    server = f.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")

    def capture(name):
        (output / (name + ".png")).write_bytes(subprocess.check_output([*ADB_COMMAND, "exec-out", "screencap", "-p"]))

    def digits(value):
        root = snapshot()
        tap("Clear", root)
        for digit in value:
            tap("Digit " + digit, root)

    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        verify_fixture(port)
        root = snapshot()
        assert not any("Home before moving" in n.get("text", "") for n in root.iter("node"))
        capture("01-home")
        tap("Prepare")
        tap("X +")
        wait_text("Home X first")
        assert not f.requests, "Unhomed move dispatched"
        capture("02-action-error")
        tap("Filament")
        tap("Feed 5 mm")
        wait_text("Heat the nozzle first")
        assert not f.requests, "Cold extrusion dispatched"
        print("PASS: no standing homing warning; attempted unhomed motion/cold extrusion explain refusal with zero commands", flush=True)

        tap("Set target")
        tap("Custom target")
        wait_text("Nozzle target (°C)")
        wait_until(lambda: not any("inputmethod" in n.get("package", "")
                                  for n in snapshot().iter("node")), "system keyboard stays hidden for touch keypad")
        root = snapshot()
        for node in root.iter("node"):
            if node.get("clickable") == "true" and node.get("enabled") == "true":
                a,b,c,d = map(int,re.findall(r"\d+",node.get("bounds")))
                assert c-a >=126 and d-b >=126, (node.get("text"),node.get("bounds"))
                assert 0<=b<d<=1080, node.get("bounds")
        capture("03-keypad")
        digits("9999")
        tap("Apply")
        wait_text("Use 0")
        assert not f.requests, "Invalid number dispatched"
        digits("230")
        tap("Apply")
        wait_until(lambda: scripts(f) == ["SET_HEATER_TEMPERATURE HEATER=extruder TARGET=230"], "keypad target reached fixture exactly once")
        wait_text("230° selected")
        capture("04-keypad-applied")
        print("PASS: touch-only keypad fits, rejects out-of-range input, and applies 230 degrees exactly once", flush=True)

        tap("230° target")
        tap("Custom target")
        adb("shell", "input", "keyevent", "4")
        wait_text("Nozzle temperature")
        assert not any(n.get("text") == "Nozzle target (°C)" for n in snapshot().iter("node"))
        tap("Close")
        tap("Files")
        adb("shell", "input", "keyevent", "4")
        assert any(n.get("content-desc") == "Screen: Prepare" for n in snapshot().iter("node"))
        tap("Files")
        tap("Search stored prints")
        adb("shell", "input", "text", "test")
        adb("shell", "input", "keyevent", "66")
        assert any(n.get("content-desc") == "Search stored prints" and n.get("text") == "test" for n in snapshot().iter("node"))
        capture("05-search")
        adb("shell", "input", "keyevent", "4")
        print("PASS: Back returns keypad to temperature editor and Files to Prepare; search accepts text and Enter without losing filter", flush=True)

        f.status["print_stats"].update(state="printing", filename="test-plate.gcode")
        tap("Home")
        tap("Tune print")
        wait_text("Tune this print")
        tap("Speed 100%")
        digits("115")
        tap("Apply")
        wait_until(lambda: scripts(f)[-1] == "M220 S115", "precision speed target")
        wait_text("115%")
        capture("06-precision-tuning")
        tap("Close")
        # A modal exposes only its own accessibility window. Save the live
        # header bounds first, as in check_workflows.py; the header does not move.
        header = snapshot()
        tap("Tune print")
        tap("Speed 115%")
        tap("Emergency stop", header)
        wait_until(lambda: any(r["path"] == "/printer/emergency_stop" for r in f.requests), "E-stop through keypad")
        assert scripts(f) == ["SET_HEATER_TEMPERATURE HEATER=extruder TARGET=230", "M220 S115"]
        print("PASS: E-stop remains accessible with touch keypad open; no unwanted replay", flush=True)
        (output / "results.json").write_text(json.dumps({"passed": True, "physical_printer_commands": 0, "fixture_requests": len(f.requests)}, indent=2), encoding="utf-8")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
