"""Loopback-only review of the actual Android app. No real printer or phone.

Run with --serial emulator-5554 after opening the app on the fixture endpoint.
The browser selects simulated printer state and a native screen independently;
captures remain immutable so annotations keep their original context.
"""
import argparse
import copy
import html
import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from fixture_server import Fixture

STATES = {
    'idle': 'Ready', 'heating': 'Heating', 'printing': 'Printing', 'paused': 'Paused',
    'complete': 'Finished', 'cancelled': 'Cancelled', 'error': 'Print failed',
    'unhomed': 'Axes not homed', 'shutdown': 'Klipper shutdown', 'offline': 'Disconnected',
    'front': 'Front paper contact', 'rear': 'Rear paper contact',
}
SCREENS = {
    'home': 'Home', 'move': 'Manual movement', 'filament': 'Filament', 'files': 'Files',
    'camera': 'Camera (off)', 'more': 'More', 'connection': 'Connection',
    'maintenance': 'Maintenance', 'calibration': 'Bed calibration', 'heightmap': 'Heightmap',
    'details': 'Print details', 'nozzle': 'Nozzle temperature', 'bed': 'Bed temperature',
    'temperature-keypad': 'Temperature keypad', 'tuning': 'Print tuning',
    'xy-speed': 'XY speed', 'z-speed': 'Z speed', 'e-speed': 'Extrusion speed',
    'distance': 'Extrusion distance', 'position': 'Exact position', 'fan': 'Part fan',
    'presets': 'Temperature presets',
    'filament-temperature': 'Filament temperature',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixture-port', type=int, default=18725)
    parser.add_argument('--port', type=int, default=18726)
    parser.add_argument('--fixture-snapshot',type=Path,
                        help='Reuse fixed fixture status/files for matched visual evaluation')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Review controls require an emulator serial, never a physical phone')
    os.environ['ANDROID_SERIAL'] = args.serial
    from check_controls import ADB_COMMAND, AGENT_DEVICE_COMMAND, adb, device, snapshot, tap, verify_fixture
    from check_workflows import click

    fixture = Fixture()
    if args.fixture_snapshot:
        data = json.loads(args.fixture_snapshot.read_text(encoding='utf-8'))
        fixture.status = data['status']
        fixture.files = data['files']
    base = copy.deepcopy(fixture.status)
    captures = Path(__file__).resolve().parents[1] / '.build/review-captures'
    captures.mkdir(parents=True, exist_ok=True)
    selection = {'state': 'idle', 'screen': 'home'}
    operation = threading.Lock()

    def set_state(name):
        s = copy.deepcopy(base)
        s['toolhead'].update(homed_axes='xyz', position=[60, 60, 5, 0])
        if name in ('printing', 'paused', 'complete', 'cancelled', 'error'):
            s['print_stats'].update(state=name,
                filename='Voron_parts/HeatCore_4_toolhead_front_ABS_0.2mm.gcode',
                print_duration=3760, info={'current_layer': 45, 'total_layer': 168})
            s['virtual_sdcard']['progress'] = 1 if name == 'complete' else .27
            if name in ('printing', 'paused'):
                s['extruder'].update(temperature=239.6, target=240, can_extrude=True)
                s['heater_bed'].update(temperature=99.8, target=100)
            if name == 'error': s['print_stats']['message'] = 'Simulated interrupted print'
        if name in ('front', 'rear'):
            s['manual_probe'].update(is_active=True, z_position=.125)
            s['y_axis_z_offset']['calibration_point'] = 0 if name == 'front' else 1
        if name == 'heating':
            s['extruder'].update(temperature=72, target=240)
            s['heater_bed'].update(temperature=52, target=100)
        if name == 'unhomed': s['toolhead']['homed_axes'] = ''
        if name == 'shutdown':
            s['webhooks'].update(state='shutdown', state_message='Simulated printer shutdown')
        with fixture.lock:
            fixture.status = s
            fixture.offline = name == 'offline'
        selection['state'] = name

    def close_dialogs():
        for _ in range(3):
            root = snapshot()
            if any(n.get('text') == 'Close' and n.get('clickable') == 'true' for n in root.iter('node')):
                tap('Close', root)
            else: break

    def open_screen(name):
        close_dialogs()
        click('Home')
        if name == 'home': return
        routes = {
            'move': ['Prepare'], 'filament': ['Prepare', 'Filament'], 'files': ['Files'],
            'camera': ['Camera'], 'more': ['More'], 'connection': ['Connection details'],
            'maintenance': ['More', 'Maintenance'],
            'calibration': ['More', 'Maintenance', 'Bed calibration'],
            'heightmap': ['More', 'Maintenance', 'Bed calibration', 'View heightmap'],
            'details': ['Files', 'Print details: test-plate.gcode'],
            'nozzle': ['Set nozzle temperature'], 'bed': ['Set bed temperature'],
            'temperature-keypad': ['Set nozzle temperature', 'Custom target'],
            'tuning': ['Tune print'], 'xy-speed': ['Prepare', 'XY movement speed'],
            'z-speed': ['Prepare', 'Z movement speed'], 'e-speed': ['Prepare', 'E movement speed'],
            'distance': ['Prepare', 'Extrusion distance'], 'position': ['Prepare', 'Set X position'],
            'fan': ['Prepare', 'Part fan'],
            'presets': ['Temperature presets'],
            'filament-temperature': ['Prepare', 'Filament', 'Filament temperature'],
        }
        for label in routes[name]: click(label)

    def capture():
        name = str(time.time_ns()) + '.png'
        if AGENT_DEVICE_COMMAND:
            device('screenshot', str(captures / name))
        else:
            data = subprocess.check_output([*ADB_COMMAND, 'exec-out', 'screencap', '-p'], timeout=15)
            (captures / name).write_bytes(data)
        return '/view/' + name

    class Controls(BaseHTTPRequestHandler):
        def log_message(self, *a): pass

        def redirect(self, target):
            self.send_response(303)
            self.send_header('Location', target)
            self.end_headers()

        def do_POST(self):
            if self.path != '/select': return self.send_error(404)
            data = parse_qs(self.rfile.read(int(self.headers.get('Content-Length', 0))).decode())
            state, screen = data.get('state', ['idle'])[0], data.get('screen', ['home'])[0]
            if state not in STATES or screen not in SCREENS: return self.send_error(400)
            try:
                with operation:
                    # Tuning belongs to a job; paper contacts belong to calibration.
                    if screen == 'tuning' and state not in ('printing', 'paused'): state = 'printing'
                    if screen == 'presets' and state in ('printing', 'paused'): state = 'idle'
                    if state in ('front', 'rear'): screen = 'calibration'
                    if state in ('offline', 'shutdown') and screen not in ('home', 'connection', 'more', 'maintenance'):
                        screen = 'home'
                    if state == 'unhomed' and screen == 'position': screen = 'move'
                    close_dialogs()
                    set_state(state)
                    time.sleep(2.4)
                    open_screen(screen)
                    selection['screen'] = screen
                    # Android publishes the accessibility tree before the final
                    # rendered frame; wait before capturing an immutable image.
                    time.sleep(1.2)
                    self.redirect(capture())
            except Exception as e:
                self.page(error=str(e))

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == '/capture':
                with operation: self.redirect(capture())
                return
            if path.startswith('/png/'):
                name = path.rsplit('/', 1)[-1]
                if not name.removesuffix('.png').isdigit(): return self.send_error(400)
                file = captures / name
                if not file.is_file(): return self.send_error(404)
                data = file.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self.page()

        def page(self, error=''):
            def options(items, key):
                return ''.join(f'<option value="{k}" {"selected" if selection[key] == k else ""}>{v}</option>' for k, v in items.items())
            page = '''<!doctype html><html><meta charset="utf-8"><title>Voron review states</title>
<style>body{background:#101113;color:#eceeed;font:14px system-ui;margin:16px}
header,form{display:flex;align-items:center;gap:12px;flex-wrap:wrap}header{justify-content:space-between;margin-bottom:12px}
label{display:flex;gap:8px;align-items:center}button,select,a{font:inherit;color:#eceeed;background:#2b2d31;border:1px solid #41444a;border-radius:5px;padding:9px 12px;text-decoration:none}
button{cursor:pointer}p{color:#adb5b2;margin:8px 0}img{display:block;width:100%;height:auto}body.zoom img{width:auto;max-width:none}.error{color:#ff9592}</style>
<header><form method="post" action="/select"><label>Printer <select name="state">'''
            page += options(STATES, 'state') + '</select></label><label>Screen <select name="screen">'
            page += options(SCREENS, 'screen') + '''</select></label><button>Open in app</button></form>
<a href="/capture">Capture current app</a></header>
<p>Actual emulator app, simulated printer. Use Device to interact, then capture. Use Annotate preview beside the camera to mark the image.</p>
<p><button onclick="document.body.classList.toggle('zoom')">Fit / full resolution</button></p>'''
            if error: page += '<p class="error">' + html.escape(error) + '</p>'
            if self.path.startswith('/view/'):
                name = self.path.rsplit('/', 1)[-1]
                if name.removesuffix('.png').isdigit():
                    page += f'<img alt="Actual Android app screenshot" src="/png/{name}">'
            data = page.encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    set_state('idle')
    server = fixture.server(args.fixture_port)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', f'tcp:{args.fixture_port}', f'tcp:{args.fixture_port}')
    close_dialogs()
    verify_fixture(args.fixture_port)
    print(f'Actual {args.serial}; fixture {args.fixture_port}; review {args.port}; loopback only', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Controls).serve_forever()


if __name__ == '__main__': main()
