"""Actual app: uncertain calibration receipt and cancellation during preflight."""
import json
import re
import threading
from check_controls import adb, snapshot, PACKAGE
from check_workflows import click, wait_text, wait_until, scripts
from fixture_server import Fixture


def main():
    fixture = Fixture()
    fixture.status["toolhead"].update(homed_axes="xyz", position=[50, 50, 10, 0])
    fixture.status["manual_probe"].update(is_active=True, z_position=.25)
    fixture.status["y_axis_z_offset"]["calibration_point"] = 1
    fixture.calibration_result = "mesh_error"
    server = fixture.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        click("Prepare")
        click("More")
        click("Maintenance")
        click("Bed calibration")
        wait_text("Rear paper contact")
        click("Accept contact")
        root = wait_text("Contacts accepted. Saving and heightmap completion unconfirmed")
        assert not any("Offsets saved" in n.get("text", "") for n in root[0].iter("node"))
        assert scripts(fixture) == ["ACCEPT"]
        print("PASS: intermediate receipt never claims persistence or completed mesh", flush=True)
        click("Move")
        root = snapshot()
        def center(label):
            node = next(n for n in root.iter("node") if n.get("text") == label or n.get("content-desc") == label)
            x,y,r,b = map(int, re.findall(r"\d+", node.get("bounds")))
            return str((x+r)//2), str((y+b)//2)
        move, stop = center("X +"), center("Emergency stop")
        # Even a subsequent stale Ready response must not revive the command
        # that was preparing when E-stop invalidated its generation.
        fixture.frozen_status = json.loads(json.dumps(fixture.status))
        fixture.query_delay = 3
        fixture.query_started.clear()
        adb("shell", "input", "tap", *move)
        assert fixture.query_started.wait(5)
        adb("shell", "input", "tap", *stop)
        wait_until(lambda: any(r["path"] == "/printer/emergency_stop" for r in fixture.requests), "E-stop request")
        wait_until(lambda: any(n.get("text") == "X +" and n.get("enabled") == "true"
                              for n in snapshot().iter("node")), "preflight completes and releases its gate", 30)
        assert scripts(fixture) == ["ACCEPT"], "Pre-stop ordinary command was dispatched afterward"
        print("PASS: E-stop cancels pre-dispatch work even when delayed telemetry still reports Ready", flush=True)
    finally:
        fixture.query_delay = 0
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
