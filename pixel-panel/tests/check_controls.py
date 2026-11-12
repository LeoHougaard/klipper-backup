"""Exercise actual Android controls against an isolated Moonraker fixture.

No real printer is contacted. Each tap is located from a fresh accessibility
snapshot, and cleanup restores the normal printer endpoint without changing it.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from fixture_server import Fixture

ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
PACKAGE = "dev.leo.voronpanel"
SERIAL = os.environ.get("ANDROID_SERIAL")
ADB_COMMAND = [str(ADB), *(["-s", SERIAL] if SERIAL else [])]
AGENT_DEVICE_COMMAND = os.environ.get("AGENT_DEVICE_COMMAND")


def device(*args):
    """Use the Device panel's bound command without starting another inspector."""
    assert AGENT_DEVICE_COMMAND, 'Set AGENT_DEVICE_COMMAND to the command from device_open'
    try:
        return subprocess.check_output([AGENT_DEVICE_COMMAND, *args], text=True,
                                       encoding='utf-8', stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.output.strip()) from error


def agent_snapshot():
    result = json.loads(device('snapshot', '--json'))
    assert result.get('success'), result
    data = result['data']
    assert not data.get('truncated'), 'Device snapshot was truncated'
    assert data.get('snapshotQuality', {}).get('state') == 'healthy', data.get('snapshotQuality')
    # A healthy ScrollView reports scroll-hidden-below/above as partial. Those
    # are deliberately offscreen controls, not a missing accessibility root.
    root = ET.Element('hierarchy')
    parents = {}
    for item in data['nodes']:
        if not item.get('visibleToUser', True):
            continue
        rect = item['rect']
        x, y, w, h = (round(rect[key]) for key in ('x', 'y', 'width', 'height'))
        group = item.get('kind') in ('group', 'image')
        node = ET.SubElement(parents.get(item.get('parentIndex'), root), 'node', {
            'text': '' if group else item.get('label', ''),
            # The helper coalesces identical text/content descriptions into one
            # accessible label. Keep that name available to the existing tests.
            'content-desc': item.get('contentDescription', item.get('label', '')),
            'class': item.get('type', ''),
            'resource-id': item.get('identifier', ''),
            'package': item.get('bundleId', ''),
            'bounds': f'[{x},{y}][{x+w},{y+h}]',
            'clickable': str(item.get('hittable', False) or item.get('kind') == 'button').lower(),
            'enabled': str(item.get('enabled', False)).lower(),
            'selected': str(item.get('selected', False)).lower(),
            'agent-ref': '@' + item['ref'],
        })
        parents[item['index']] = node
    return root


def adb(*args):
    if args[:3] == ("shell", "am", "start") and "-W" not in args:
        args = (*args[:3], "-W", *args[3:])
    return subprocess.check_output([*ADB_COMMAND, *args], text=True, encoding="utf-8", stderr=subprocess.STDOUT)


def snapshot():
    if AGENT_DEVICE_COMMAND:
        return agent_snapshot()
    for attempt in range(3):
        try:
            result = adb("shell", "uiautomator", "dump", "/data/local/tmp/voron-controls-test.xml")
            if "UI hierchary dumped to" in result:
                break
            if "null root node" not in result or attempt == 2:
                raise RuntimeError("Android did not refresh its UI snapshot; refusing to reuse old XML: " + result.strip())
            # First frame after an install/launch may not expose a root yet.
            time.sleep(.5)
        except subprocess.CalledProcessError as error:
            if error.returncode != 137 or attempt == 2:
                raise
            # Android can kill the transient UI inspector. Retry briefly, but
            # still require a successful fresh dump before reading any XML.
            time.sleep(.5)
    if "UI hierchary dumped to" not in result:
        raise RuntimeError("Android did not refresh its UI snapshot; refusing to reuse old XML: " + result.strip())
    return ET.fromstring(adb("shell", "cat", "/data/local/tmp/voron-controls-test.xml"))


def tap(label, root=None):
    if root is None:
        root = snapshot()
    nodes = [n for n in root.iter("node") if n.get("clickable") == "true" and (
        n.get("text", "").casefold() == label.casefold() or
        n.get("content-desc", "").casefold() in (label.casefold(), "open " + label.casefold()))]
    assert len(nodes) == 1, f"Expected one clickable {label!r}, found {len(nodes)}"
    if AGENT_DEVICE_COMMAND:
        device('press', nodes[0].get('agent-ref'), '--settle')
        return
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


def verify_fixture(port):
    tap("Connection details")
    expected = f"http://127.0.0.1:{port}"
    assert any(n.get("content-desc") == "Moonraker address" and n.get("text") == expected
               for n in snapshot().iter("node")), "App is not connected to the isolated fixture"
    tap("Home")


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
