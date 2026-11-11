"""Complete shortened touch workflows on the actual Pixel, isolated from printer hardware."""
import argparse
import json
import subprocess
import threading
from pathlib import Path
from check_controls import adb, snapshot, PACKAGE, ADB
from check_workflows import click, wait_text, wait_until, send_and_observe, scripts
from fixture_server import Fixture


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    fixture = Fixture()
    server = fixture.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    fast_before = None

    def capture(name):
        if args.output:
            (args.output / (name + ".png")).write_bytes(subprocess.check_output([str(ADB), "exec-out", "screencap", "-p"]))

    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        click("More")
        fast_before = any(n.get("text") == "Fast controls: on" for n in snapshot().iter("node"))
        if fast_before:
            click("Fast controls: on")
        click("Home")
        click("Home axes")
        send_and_observe(fixture, "Home axes", "G28")
        wait_text("All axes homed")
        send_and_observe(fixture, "PLA 200° / 60°", "SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200\nSET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=60")
        wait_until(lambda: fixture.status["extruder"]["target"] == 200 and fixture.status["heater_bed"]["target"] == 60, "preheat values")
        wait_text(" / 200°")
        capture("01-direct-preheat")
        send_and_observe(fixture, "Cool down", "TURN_OFF_HEATERS")
        wait_text(" / off")
        print("PASS: direct Home homing/preheat/cooldown with live header read-back", flush=True)

        click("Print details")
        click("Start print")
        wait_until(lambda: fixture.status["print_stats"]["state"] == "printing", "recent plate started")
        assert fixture.status["print_stats"]["filename"] == "test-plate.gcode"
        fixture.status["extruder"].update(temperature=239.6, target=240, can_extrude=True)
        fixture.status["heater_bed"].update(temperature=99.8, target=100)
        fixture.status["print_stats"].update(print_duration=600)
        fixture.status["virtual_sdcard"]["progress"] = .25
        click("Pause")
        wait_text("Print paused")
        click("Change filament")
        wait_text("Heat to 240°")
        capture("02-paused-filament")
        send_and_observe(fixture, "Load filament", "SAVE_GCODE_STATE NAME=panel_filament\nLOAD_FILAMENT\nRESTORE_GCODE_STATE NAME=panel_filament")
        click("Resume print")
        click("Resume")
        wait_until(lambda: fixture.status["print_stats"]["state"] == "printing", "print resumed")
        wait_until(lambda: any(n.get("content-desc") == "Screen: Home" for n in snapshot().iter("node")), "returned to active Home")
        capture("03-resumed-home")
        print("PASS: recent plate -> start -> pause -> matched-temperature filament load -> local Resume -> Home", flush=True)

        send_and_observe(fixture, "Increase speed by 10 percent", "M220 S110")
        wait_text("110%")
        assert not any(n.get("text") == "Tune this print" for n in snapshot().iter("node"))
        click("Set nozzle temperature")
        click("200°")
        capture("04-contextual-temperature")
        send_and_observe(fixture, "Set 200°", "SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200")
        wait_text(" / 200°")
        capture("05-inline-feedback")
        assert fixture.status["gcode_move"]["speed_factor"] == 1.1
        print("PASS: inline tuning and contextual temperature editor keep job state and expose confirmed returned values", flush=True)
        if args.output:
            (args.output / "results.json").write_text(json.dumps({"printer": "isolated fixture", "requests": len(fixture.requests), "physical_printer_commands": 0, "passed": True}, indent=2), encoding="utf-8")
    finally:
        if fast_before:
            click("More")
            click("Fast controls: off")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
