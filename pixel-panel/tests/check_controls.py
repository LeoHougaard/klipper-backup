"""Exercise actual Android controls against an isolated Moonraker fixture.

No real printer is contacted. Each tap is located from a fresh accessibility
snapshot, and cleanup restores the normal printer endpoint without changing it.
"""
from pathlib import Path
import os
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from fixture_server import Fixture

ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
PACKAGE = "dev.leo.voronpanel"


def adb(*args):
    return subprocess.check_output([str(ADB), *args], text=True, encoding="utf-8", stderr=subprocess.STDOUT)


def snapshot():
    result = adb("shell", "uiautomator", "dump", "/data/local/tmp/voron-controls-test.xml")
    if "UI hierchary dumped to" not in result:
        raise RuntimeError("Android did not refresh its UI snapshot; refusing to reuse old XML")
    return ET.fromstring(adb("shell", "cat", "/data/local/tmp/voron-controls-test.xml"))


def tap(label, root=None):
    if root is None:
        root = snapshot()
    nodes = [n for n in root.iter("node") if n.get("clickable") == "true" and (
        n.get("text", "").casefold() == label.casefold() or
        n.get("content-desc", "").casefold() in (label.casefold(), "open " + label.casefold()))]
    assert len(nodes) == 1, f"Expected one clickable {label!r}, found {len(nodes)}"
    left, top, right, bottom = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    # Input injection returning does not mean a newly rebuilt native view tree
    # has reached Accessibility yet. Let the next frame publish before reading.
    time.sleep(.25)


def wait_for_requests(fixture, count):
    deadline = time.monotonic() + 3
    while len(fixture.requests) < count and time.monotonic() < deadline:
        time.sleep(.05)
    assert len(fixture.requests) == count, fixture.requests


def main():
    fixture = Fixture()
    fixture.fail_discovery = {"/printer/objects/list": 1, "/printer/gcode/help": 1}
    server = fixture.server(0)
    port = server.server_address[1]
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        tap("Prepare")
        tap("X +")
        tap("Filament")
        tap("Feed 5 mm")
        assert fixture.requests == [], "Unhomed/cold controls sent a command"
        print("PASS: unhomed motion and cold extrusion blocked on real app")

        fixture.status["toolhead"]["homed_axes"] = "xyz"
        fixture.status["toolhead"]["position"] = [50, 50, 10, 0]
        fixture.status["extruder"].update(temperature=220, can_extrude=True)
        tap("Move")
        # Wait for observable fresh state, not a fixed delay.
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            root = snapshot()
            if any("X 50.0" in n.get("text", "") for n in root.iter("node")):
                break
        else:
            raise AssertionError("New fixture position was not displayed")
        tap("X +")
        tap("Filament")
        tap("Feed 5 mm")
        wait_for_requests(fixture, 2)
        assert "G0 X10" in fixture.requests[0]["body"]
        assert "G1 E5" in fixture.requests[1]["body"]
        assert all("RESTORE_GCODE_STATE" in item["body"] for item in fixture.requests)
        root = snapshot()
        assert any(n.get("text") == "Load filament" and n.get("enabled") == "true" for n in root.iter("node")), "Failed discovery was cached permanently"
        print("PASS: relative movement and extrusion preserve G-code state")
        print("PASS: capability discovery recovers from initial transient failures")

        fixture.status["toolhead"]["position"] = [119, 50, 10, 0]
        tap("Move")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if any("X 119.0" in n.get("text", "") for n in snapshot().iter("node")):
                break
        tap("X +")
        assert len(fixture.requests) == 2, "Out-of-bounds move was sent"
        print("PASS: configured travel limit prevents out-of-bounds move")

        tap("Files")
        root = snapshot()
        labels = [n.get("content-desc") for n in root.iter("node") if n.get("content-desc", "") == "Print details: test-plate.gcode"]
        assert len(labels) == 1
        tap(labels[0])
        tap("Start print")
        wait_for_requests(fixture, 3)
        assert fixture.requests[-1]["path"] == "/printer/print/start"
        assert "test-plate.gcode" in fixture.requests[-1]["body"]
        print("PASS: file selection and confirmed start reach fixture's print API")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "rm", "-f", "/data/local/tmp/voron-controls-test.xml")
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
