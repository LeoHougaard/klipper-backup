"""Moonraker fixture for exercising hardware actions without touching a printer.

Run on Windows, then adb reverse tcp:18725 tcp:18725 and launch the debug APK
with --es test_endpoint http://127.0.0.1:18725. Requests are retained in memory.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time
import re
import struct
import zlib
from urllib.parse import urlsplit, parse_qs


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
            "configfile": {"settings": {"extruder": {"max_temp": 400, "min_extrude_temp": 170,
                                                    "max_extrude_only_distance": 1400,
                                                    "max_extrude_only_velocity": 75},
                                       "printer": {"max_velocity": 300, "max_z_velocity": 15},
                                       "heater_bed": {"max_temp": 110}}},
            "fan": {"speed": .35},
            "gcode_move": {"homing_origin": [0, 0, -.025, 0], "speed_factor": 1, "extrude_factor": 1},
            "manual_probe": {"is_active": False, "z_position": None},
            "y_axis_z_offset": {"calibration_point": None, "front_offset": .03, "rear_offset": -.03},
            "bed_mesh": {"profile_name": "default", "mesh_min": [10, 10], "mesh_max": [110, 110],
                         "probed_matrix": [[round((x-2) * .012 + (y-2) * .018 + .005*(x-2)**2, 4)
                                            for x in range(5)] for y in range(5)]},
        }
        self.commands = ["G28", "M84", "LOAD_FILAMENT", "UNLOAD_FILAMENT", "CALIBRATE_BED_TILT",
                         "CALIBRATE_COMPENSATED_BED_MESH", "TESTZ", "ACCEPT", "ABORT"]
        self.files = [{"path": name, "size": size, "modified": time.time() - i * 3600}
                      for i, (name, size) in enumerate([
                          ("test-plate.gcode", 123456),
                          ("Voron_parts/HeatCore_4_toolhead_front_ABS_0.2mm.gcode", 5642732),
                          ("First_layer_120x120_PLA.gcode", 175000),
                          ("Cable_chain_mount_rev_3.gcode", 2100000),
                          ("Bracket_without_metadata.gcode", 12345)])]
        self.offline = False
        self.query_delay = 0
        self.frozen_status = None
        self.query_started = threading.Event()
        self.command_delay = 0
        self.gcode_store = []
        self.calibration_result = "success"
        self.fail_discovery = {}

    def respond(self, message):
        self.gcode_store.append({"time": time.time(), "type": "response", "message": message})

    def apply_gcode(self, script):
        for line in script.splitlines():
            if line.startswith("G28"):
                self.status["toolhead"]["homed_axes"] = "xyz"
            elif line.startswith("G0 "):
                for axis, amount in re.findall(r"([XYZ])(-?[\d.]+)", line):
                    self.status["toolhead"]["position"]["XYZ".index(axis)] += float(amount)
            elif line.startswith("SET_HEATER_TEMPERATURE"):
                heater = re.search(r"HEATER=(\w+)", line)[1]
                target = float(re.search(r"TARGET=([\d.]+)", line)[1])
                self.status[heater]["target"] = target
            elif line.startswith("TURN_OFF_HEATERS"):
                self.status["extruder"]["target"] = self.status["heater_bed"]["target"] = 0
            elif line.startswith("SET_GCODE_OFFSET"):
                self.status["gcode_move"]["homing_origin"][2] += float(re.search(r"Z_ADJUST=(-?[\d.]+)", line)[1])
            elif line.startswith("M220 S"):
                self.status["gcode_move"]["speed_factor"] = float(line[6:]) / 100
            elif line.startswith("M221 S"):
                self.status["gcode_move"]["extrude_factor"] = float(line[6:]) / 100
            elif line.startswith("M106 S"):
                self.status["fan"]["speed"] = float(line[6:]) / 255
            elif line.startswith("CALIBRATE_BED_TILT"):
                self.status["manual_probe"] = {"is_active": True, "z_position": .2}
                self.status["y_axis_z_offset"]["calibration_point"] = 0
            elif line.startswith("TESTZ Z="):
                self.status["manual_probe"]["z_position"] += float(line[8:])
            elif line == "ACCEPT":
                if self.status["y_axis_z_offset"]["calibration_point"] == 0:
                    self.status["y_axis_z_offset"]["calibration_point"] = 1
                    self.status["manual_probe"]["z_position"] = .25
                else:
                    self.status["manual_probe"]["is_active"] = False
                    self.status["y_axis_z_offset"]["calibration_point"] = None
                    if self.calibration_result == "reject":
                        self.respond("Calibration result exceeds the 1.000 mm safety limit; offsets were not changed.")
                    else:
                        self.respond("Paper calibration complete: front 0.03 mm, rear -0.03 mm. Rebuilding a compensated 5x5 heightmap.")
                        if self.calibration_result == "success":
                            self.respond("Front/rear Z calibration complete. The displayed heightmap includes the saved correction.")
                        else:
                            self.respond("Probe triggered prior to movement")
            elif line == "ABORT":
                self.status["manual_probe"]["is_active"] = False
                self.status["y_axis_z_offset"]["calibration_point"] = None

    @staticmethod
    def thumbnail(kind="plate"):
        # Distinct synthetic model previews exercise identity/caching. They are
        # test data, not screenshots claimed to come from an actual slicer.
        w = h = 96
        rows = []
        for y in range(h):
            row = bytearray(b"\0")
            for x in range(w):
                if kind == "toolhead":
                    solid = 16 < x < 80 and 13 < y < 83 and not (32 < x < 64 and 30 < y < 64)
                elif kind == "layer":
                    solid = 13 < x < 83 and 13 < y < 83 and (x % 12 < 3 or y % 12 < 3)
                elif kind == "chain":
                    solid = (18 < x < 33 and 15 < y < 80) or (18 < x < 80 and 65 < y < 80)
                else:
                    solid = 15 < x < 81 and 24 < y < 72 and not (35 < x < 61 and 39 < y < 57)
                row.extend((130, 181, 156) if solid else (25, 31, 34))
            rows.append(bytes(row))
        raw = b"".join(rows)
        def chunk(kind, data):
            return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

    def server(self, port=18725):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                path = urlsplit(self.path).path
                if fixture.offline:
                    self.send_error(503)
                    return
                if fixture.fail_discovery.get(path, 0) > 0:
                    fixture.fail_discovery[path] -= 1
                    self.send_error(503)
                    return
                if path == "/printer/objects/query":
                    if fixture.query_delay:
                        fixture.query_started.set()
                        time.sleep(fixture.query_delay)
                    requested = [s.split("=")[0] for s in urlsplit(self.path).query.split("&")]
                    source = fixture.frozen_status if fixture.frozen_status is not None else fixture.status
                    self.reply({"status": {k: v for k, v in source.items() if k in requested}})
                elif path == "/printer/objects/list":
                    self.reply({"objects": list(fixture.status)})
                elif path == "/printer/gcode/help":
                    self.reply({name: "Fixture command" for name in fixture.commands})
                elif path == "/server/info":
                    self.reply({"klippy_connected": True, "klippy_state": fixture.status["webhooks"]["state"]})
                elif path == "/server/gcode_store":
                    self.reply({"gcode_store": fixture.gcode_store[-1000:]})
                elif path == "/server/files/list":
                    self.reply(fixture.files)
                elif path == "/server/files/metadata":
                    name = parse_qs(urlsplit(self.path).query).get("filename", [""])[0]
                    kind = "toolhead" if "HeatCore" in name else "layer" if "PLA" in name else "chain" if "chain" in name else "plate"
                    self.reply({} if "without_metadata" in name else {
                        "estimated_time": 1320 if "PLA" in name else 9360, "filament_type": "PLA" if "PLA" in name else "ABS", "filament_total": 2400,
                        "thumbnails": [{"width": 96, "height": 96, "relative_path": f".thumbs/fixture-{kind}.png"}]})
                elif "/.thumbs/fixture-" in path:
                    data = fixture.thumbnail(path.rsplit("fixture-", 1)[1].removesuffix(".png"))
                    self.send_response(200)
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
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
                                                 "length": len(body), "time": time.monotonic()})
                    if self.path == "/printer/gcode/script":
                        fixture.apply_gcode(json.loads(body)["script"])
                        if fixture.command_delay:
                            time.sleep(fixture.command_delay)
                    elif self.path == "/printer/print/start":
                        fixture.status["print_stats"].update(state="printing", filename=json.loads(body)["filename"])
                    elif self.path == "/printer/print/pause":
                        fixture.status["print_stats"]["state"] = "paused"
                    elif self.path == "/printer/print/resume":
                        fixture.status["print_stats"]["state"] = "printing"
                    elif self.path == "/printer/print/cancel":
                        fixture.status["print_stats"]["state"] = "cancelled"
                    elif self.path == "/printer/emergency_stop":
                        fixture.status["webhooks"].update(state="shutdown", state_message="Emergency stop")
                        fixture.status["print_stats"].update(state="error", message="Emergency stop")
                        for heater in ("extruder", "heater_bed"):
                            fixture.status[heater]["target"] = 0
                    elif self.path in ("/printer/restart", "/printer/firmware_restart"):
                        fixture.status["webhooks"].update(state="ready", state_message="Printer ready")
                        fixture.status["print_stats"].update(state="standby", message="")
                        fixture.status["virtual_sdcard"]["progress"] = 0
                self.reply("ok")

            def reply(self, result):
                payload = json.dumps({"result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try:
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass  # Expected when testing a withheld response past client timeout.

        return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    print("Moonraker fixture on 127.0.0.1:18725; no printer connection", flush=True)
    Fixture().server().serve_forever()
