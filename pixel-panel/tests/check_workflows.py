"""Actual Android workflows against an isolated fixture. Never contacts a printer.

Uses the app's nonpersistent debug endpoint and real native controls. Requires
exclusive access to the test phone; cleanup restores its saved printer endpoint.
"""
import json
import math
import re
import threading
import time
from check_controls import adb, snapshot, tap, PACKAGE
from fixture_server import Fixture


def wait_until(predicate, description, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.05)
    raise AssertionError("Timed out: " + description)


def nodes_with(root, text, exact=False):
    return [n for n in root.iter("node") if
            (n.get("text", "") == text if exact else text in n.get("text", ""))]


def wait_text(text, timeout=15):
    return wait_until(lambda: nodes_with(snapshot(), text), "UI text " + repr(text), timeout)


def click(label):
    def enabled():
        root = snapshot()
        found = any(n.get("enabled") == "true" and n.get("clickable") == "true" and
                   (n.get("text", "").casefold() == label.casefold() or
                    n.get("content-desc", "").casefold() in
                    (label.casefold(), "open " + label.casefold()))
                   for n in root.iter("node"))
        return (root,) if found else None
    root = wait_until(enabled, "enabled control " + repr(label))[0]
    tap(label, root)


def scripts(fixture):
    with fixture.lock:
        return [json.loads(r["body"])["script"] for r in fixture.requests
                if r["path"] == "/printer/gcode/script"]


def send_and_observe(fixture, label, expected):
    count = len(scripts(fixture))
    click(label)
    wait_until(lambda: len(scripts(fixture)) == count + 1, expected + " reached fixture")
    assert scripts(fixture)[-1] == expected, scripts(fixture)


def start_paper(fixture, result):
    fixture.calibration_result = result
    click("Start paper calibration")
    send_and_observe(fixture, "Home & calibrate", "CALIBRATE_BED_TILT PROBE_COUNT=5")
    wait_text("Front paper contact")
    send_and_observe(fixture, "Closer", "TESTZ Z=-0.025")
    wait_until(lambda: math.isclose(fixture.status["manual_probe"]["z_position"], .175), "front TESTZ result")
    send_and_observe(fixture, "Accept contact", "ACCEPT")
    wait_text("Rear paper contact")
    send_and_observe(fixture, "Further", "TESTZ Z=0.025")
    wait_until(lambda: math.isclose(fixture.status["manual_probe"]["z_position"], .275), "rear TESTZ result")


def main():
    fixture = Fixture()
    fixture.status["toolhead"].update(homed_axes="xyz", position=[50, 50, 10, 0])
    server = fixture.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es",
            "test_endpoint", f"http://127.0.0.1:{port}")
        click("Prepare")
        click("Bed setup")
        outcomes = {
            "success": "Calibration saved",
            "reject": "Correction exceeded the safety limit. Previous offsets kept",
            "mesh_error": "Contacts accepted. Saving and heightmap completion unconfirmed",
        }
        for outcome, expected in outcomes.items():
            start_paper(fixture, outcome)
            send_and_observe(fixture, "Accept contact", "ACCEPT")
            wait_text(expected)
            assert not fixture.status["manual_probe"]["is_active"]
            assert fixture.status["y_axis_z_offset"]["front_offset"] == .03
            assert fixture.status["y_axis_z_offset"]["rear_offset"] == -.03
            print("PASS: front/rear paper workflow reports " + outcome, flush=True)

        fixture.calibration_result = "success"
        click("Start paper calibration")
        send_and_observe(fixture, "Home & calibrate", "CALIBRATE_BED_TILT PROBE_COUNT=5")
        wait_text("Front paper contact")
        click("Abort calibration")
        send_and_observe(fixture, "Abort calibration", "ABORT")
        wait_text("Calibration aborted")
        assert not fixture.status["manual_probe"]["is_active"]
        print("PASS: abort leaves paper workflow and preserves saved offsets", flush=True)

        fixture.status["print_stats"].update(state="printing", filename="fixture-active.gcode")
        click("Home")
        click("Tune print")
        wait_text("Z offset")
        for _ in range(3):
            send_and_observe(fixture, "Further +0.025", "SET_GCODE_OFFSET Z_ADJUST=0.025 MOVE=1")
        assert math.isclose(fixture.status["gcode_move"]["homing_origin"][2], .05)
        for label, expected in [
                ("Increase speed by 10 percent", "M220 S110"),
                ("Increase speed by 10 percent", "M220 S120"),
                ("Decrease flow by 5 percent", "M221 S95"),
                ("Decrease flow by 5 percent", "M221 S90")]:
            send_and_observe(fixture, label, expected)
        assert math.isclose(fixture.status["gcode_move"]["speed_factor"], 1.2)
        assert math.isclose(fixture.status["gcode_move"]["extrude_factor"], .9)
        fixture.status["fan"]["speed"] = .03
        wait_text("3%")
        send_and_observe(fixture, "Decrease part fan by 10 percent", "M106 S0")
        fixture.status["fan"]["speed"] = .93
        wait_text("93%")
        send_and_observe(fixture, "Increase part fan by 10 percent", "M106 S255")
        wait_text("100%")
        root = snapshot()
        assert any(n.get("content-desc") == "Increase part fan by 10 percent" and n.get("enabled") == "false" for n in root.iter("node"))
        print("PASS: non-round slicer fan percentages reach Off and Full endpoints", flush=True)
        click("Close")
        print("PASS: repeated live Z, speed and flow adjustments use fresh values", flush=True)

        click("Tune print")
        fixture.command_delay = 25
        before = len(scripts(fixture))
        send_and_observe(fixture, "Increase flow by 5 percent", "M221 S95")
        wait_text("Response not confirmed", 35)
        wait_until(lambda: any(n.get("content-desc") == "Increase flow by 5 percent"
                              and n.get("enabled") == "true" for n in snapshot().iter("node")),
                   "normal controls recover after uncertain short command", 10)
        assert len(scripts(fixture)) == before + 1, "Uncertain command was replayed"
        fixture.command_delay = 0
        click("Close")
        print("PASS: accepted command with stalled reply releases normal controls, reports uncertainty and never replays", flush=True)

        # Cache its full visible target before opening a separate native window.
        # Accessibility enumerates only the focused dialog, so use the already
        # measured physical target to prove touch passes through to the header.
        stop_node = next(n for n in snapshot().iter("node") if n.get("content-desc") == "Emergency stop")
        left, top, right, bottom = map(int, re.findall(r"\d+", stop_node.get("bounds")))
        assert min(right-left, bottom-top) >= 126
        click("Tune print")
        fixture.command_delay = 25
        send_and_observe(fixture, "Increase flow by 5 percent", "M221 S100")
        pending = fixture.requests[-1]["time"]
        began = time.monotonic()
        # Do not wait for the ordinary-command gate: E-stop must bypass it.
        adb("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))
        stop = wait_until(lambda: next((r for r in fixture.requests
                         if r["path"] == "/printer/emergency_stop"), None), "urgent stop dispatch", 3)
        assert stop["time"] - began < 5, "E-stop waited behind ordinary work"
        assert stop["time"] < pending + fixture.command_delay, "Ordinary work finished before E-stop"
        wait_text("Emergency stop sent")
        wait_until(lambda: time.monotonic() >= pending + fixture.command_delay + .5,
                   "delayed ordinary command has finished", 35)
        for _ in range(2):
            root = snapshot()
            assert nodes_with(root, "Emergency stop sent"), "Urgent result was overwritten"
        assert fixture.status["webhooks"]["state"] == "shutdown"
        print("PASS: full header E-stop works through an open sheet, bypasses delayed command and survives late callbacks", flush=True)
    finally:
        fixture.command_delay = 0
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "rm", "-f", "/data/local/tmp/voron-controls-test.xml")
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
