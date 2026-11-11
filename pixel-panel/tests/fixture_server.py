"""Moonraker fixture for exercising hardware actions without touching a printer.

Run on Windows, then adb reverse tcp:18725 tcp:18725 and launch the debug APK
with --es test_endpoint http://127.0.0.1:18725. Requests are retained in memory.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.parse import urlsplit


class Fixture:
    def __init__(self):
        self.requests = []
        self.lock = threading.Lock()
        self.status = {
            "webhooks": {"state": "ready"},
            "print_stats": {"state": "standby", "filename": "", "print_duration": 0},
            "virtual_sdcard": {"progress": 0},
            "extruder": {"temperature": 22, "target": 0, "can_extrude": False},
            "heater_bed": {"temperature": 23, "target": 0},
            "toolhead": {"homed_axes": "", "position": [0, 0, 0, 0],
                         "axis_minimum": [0, 0, -1.5], "axis_maximum": [120, 120, 120]},
            "configfile": {"settings": {"extruder": {"max_temp": 400}, "heater_bed": {"max_temp": 110}}},
        }

    def server(self, port=18725):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                path = urlsplit(self.path).path
                if path == "/printer/objects/query":
                    self.reply({"status": fixture.status})
                elif path == "/server/files/list":
                    self.reply([{"path": "test-plate.gcode", "size": 123456, "modified": 100}])
                elif path == "/machine/update/status":
                    self.reply({"version_info": {"klipper": {"version": "fixture-1", "remote_version": "fixture-2"}}})
                elif path == "/fixture/requests":
                    self.reply(fixture.requests)
                else:
                    self.reply({"klippy_connected": True, "klippy_state": "ready"})

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                if self.path == "/fixture/state":
                    fixture.status.update(json.loads(body))
                else:
                    with fixture.lock:
                        fixture.requests.append({"method": "POST", "path": self.path,
                                                 "body": body.decode(errors="replace"),
                                                 "content_type": self.headers.get("Content-Type"),
                                                 "length": len(body)})
                self.reply("ok")

            def reply(self, result):
                payload = json.dumps({"result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    print("Moonraker fixture on 127.0.0.1:18725; no printer connection", flush=True)
    Fixture().server().serve_forever()
