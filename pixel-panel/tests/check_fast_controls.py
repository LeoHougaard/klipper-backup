"""Verify warning confirmations and explicit fast bypass on the real app/fixture."""
import threading
from check_controls import adb, snapshot, PACKAGE
from check_workflows import click, wait_text, wait_until, scripts
from fixture_server import Fixture


def main():
    fixture = Fixture()
    server = fixture.server(0)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
    initial = None
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--es", "test_endpoint", f"http://127.0.0.1:{port}")
        click("More")
        initial = any(n.get("text") == "Fast controls: on" for n in snapshot().iter("node"))
        if initial:
            click("Fast controls: on")
        click("Prepare")
        click("Home axes")
        wait_text("Home all axes?")
        assert scripts(fixture) == [], "Normal warning dispatched before confirmation"
        click("Back")
        click("More")
        click("Fast controls: off")
        click("Prepare")
        click("Home axes")
        wait_until(lambda: scripts(fixture) == ["G28"], "one-tap fast home")
        assert not any(n.get("text") == "Home all axes?" for n in snapshot().iter("node"))
        print("PASS: normal warning waits; Fast controls dispatches once with no repeat confirmation", flush=True)
    finally:
        if initial is not None:
            click("More")
            current = any(n.get("text") == "Fast controls: on" for n in snapshot().iter("node"))
            if current != initial:
                click("Fast controls: on" if current else "Fast controls: off")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()
        server.server_close()
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")


if __name__ == "__main__":
    main()
