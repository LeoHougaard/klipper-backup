"""Real Pixel temperature gestures; every heater command goes to a fixture."""
import re
import subprocess
import threading
import time
from check_controls import adb, snapshot, tap, ADB, PACKAGE, verify_fixture, ADB_COMMAND
from check_workflows import wait_until, wait_text, scripts
from fixture_server import Fixture


def main():
    f = Fixture()
    server = f.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")

    def center(label):
        node = next(n for n in snapshot().iter("node") if n.get("content-desc") == label)
        x,y,r,b = map(int,re.findall(r"\d+",node.get("bounds")))
        return (x+r)//2,(y+b)//2

    def slide(label, delta, target, heater):
        x,y = center(label)
        count = len(scripts(f))
        adb("shell", "input", "swipe", str(x),str(y),str(x),str(y+delta),"450")
        expected = f"SET_HEATER_TEMPERATURE HEATER={heater} TARGET={target}"
        wait_until(lambda: len(scripts(f)) == count+1, "one command after release")
        assert scripts(f)[-1] == expected, scripts(f)
        time.sleep(.3)
        assert len(scripts(f)) == count+1, "Gesture streamed repeated commands"

    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE+"/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        verify_fixture(port)
        slide("Set nozzle temperature", 168, 40, "extruder")
        slide("Set nozzle temperature", -84, 20, "extruder")
        slide("Set bed temperature", 42, 10, "heater_bed")
        maximum = int(f.status["configfile"]["settings"]["heater_bed"]["max_temp"] // 5 * 5)
        slide("Set bed temperature", 700, maximum, "heater_bed")
        print("PASS: down/up side-rail drags select 5-degree steps, nozzle/bed both work, clamp at configured limit, one command per release", flush=True)
        tap("Set nozzle temperature")
        wait_text("Nozzle temperature")
        tap("Close")
        assert len(scripts(f)) == 4
        print("PASS: a tap still opens presets without changing target", flush=True)
        x,y = center("Set nozzle temperature")
        before = len(scripts(f))
        swipe = subprocess.Popen([*ADB_COMMAND, "shell","input","swipe",str(x),str(y),str(x),str(y+168),"1800"], stdout=subprocess.DEVNULL)
        time.sleep(.2)
        adb("shell", "input", "keyevent", "3")
        swipe.wait(timeout=10)
        assert len(scripts(f)) == before, "Cancelled gesture applied a heater target"
        print("PASS: leaving app during a drag cancels it without sending a heater command", flush=True)
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE+"/.MainActivity")


if __name__ == "__main__":
    main()
