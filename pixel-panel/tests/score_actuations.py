"""Matched, label-guided native task traces on the already-open review emulator.

Input counts include digits and required confirmations. Setup/cleanup are excluded.
Automation duration is not a human usability measurement. No physical printer.
"""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path
from urllib.request import Request, urlopen
from check_controls import SERIAL, AGENT_DEVICE_COMMAND, PACKAGE, adb, device, snapshot, verify_fixture
from check_workflows import click, wait_text, wait_until

ENDPOINT = 'http://127.0.0.1:18725'
TITLES = {'Nozzle temperature', 'Bed temperature', 'Tune this print', 'Resume this print?',
          'Print details', 'Start this print?', 'Refresh heightmap?', 'Maintenance'}


def get(path):
    with urlopen(ENDPOINT + path, timeout=5) as response:
        return json.load(response)['result']


def state(**objects):
    with urlopen(Request(ENDPOINT + '/fixture/state', data=json.dumps(objects).encode(),
                         headers={'Content-Type': 'application/json'}), timeout=5):
        pass


def scripts():
    return [json.loads(r['body'])['script'] for r in get('/fixture/requests')
            if r['path'] == '/printer/gcode/script']


def location(root):
    editor = next((n.get('content-desc') for n in root.iter('node')
                   if n.get('class') == 'android.widget.EditText'), None)
    if editor:
        return 'editor: ' + editor
    titles = [n.get('text') for n in root.iter('node') if n.get('text') in TITLES]
    has_close = any(n.get('class') == 'android.widget.Button' and n.get('text') in ('Close', 'Cancel')
                    for n in root.iter('node'))
    if titles and has_close:
        return 'dialog: ' + titles[-1]
    return next((n.get('content-desc') for n in root.iter('node')
                 if n.get('content-desc', '').startswith('Screen: ')), 'unknown')


class Trace:
    candidate = False
    def __init__(self, name, output):
        self.name, self.output = name, output
        self.inputs = []
        self.started = time.monotonic()
        self.command_start = len(scripts())
        self.request_start = len(get('/fixture/requests'))

    def press(self, label, point=None):
        before = location(snapshot())
        started = time.monotonic()
        if point is None:
            click(label)
        else:
            device('press', str(point[0]), str(point[1]), '--settle')
        after = location(snapshot())
        self.inputs.append({'target': label, 'before': before, 'after': after,
                            'automation_seconds': round(time.monotonic()-started, 3)})

    def number(self, value):
        # v0.3.8's selected Editable range survives replacement: multi-digit
        # values require Clear as a workaround. Candidate must fix that defect.
        if not self.candidate and len(str(value)) > 1:
            self.press('Clear')
        for char in str(value):
            self.press('Decimal point' if char == '.' else 'Digit ' + char)
        self.press('Apply')

    def save(self, **observations):
        result = {'task': self.name, 'input_count': len(self.inputs),
                  'navigation_transitions': sum(i['before'] != i['after'] for i in self.inputs),
                  'inputs': self.inputs, 'commands': scripts()[self.command_start:],
                  'requests': get('/fixture/requests')[self.request_start:],
                  'automation_seconds': round(time.monotonic()-self.started, 3),
                  'human_completion_time': None, 'first_use_discoverability': 'not measured',
                  **observations}
        self.output.append(result)
        print(self.name, result['input_count'], 'inputs;', observations, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--candidate', action='store_true', help='Use visible quick choices when available')
    args = parser.parse_args()
    Trace.candidate = args.candidate
    assert SERIAL and SERIAL.startswith('emulator-') and AGENT_DEVICE_COMMAND
    args.output.mkdir(parents=True, exist_ok=True)
    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?' + '&'.join(names))['status']
    results = []
    installed_path = adb('shell', 'pm', 'path', PACKAGE).strip().removeprefix('package:')
    record = {'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'installed_apk_sha256': adb('shell', 'sha256sum', installed_path).split()[0],
              'model': 'sol', 'serial': SERIAL, 'task_traces': results,
              'baseline_clear_workaround': not args.candidate,
              'physical_printer_commands': 0, 'label_guided_automation': True, 'human_trial': False}

    def home(hot=True, paused=False):
        state(webhooks={'state': 'ready'}, manual_probe={'is_active': False},
              print_stats={'state': 'paused' if paused else 'standby', 'filename': 'test-plate.gcode'},
              toolhead={'homed_axes': 'xyz', 'position': [60, 60, 5, 0],
                        'axis_minimum': [0, 0, -1.5], 'axis_maximum': [120, 120, 120]},
              extruder={'temperature': 200 if hot else 22, 'target': 200 if hot else 0, 'can_extrude': hot})
        state(heater_bed={'temperature': 23, 'target': 0})
        click('Home')
        wait_text('Print paused' if paused else 'Ready')

    def set_parameter(label, value):
        click(label)
        click('Clear')
        for char in str(value):
            click('Decimal point' if char == '.' else 'Digit ' + char)
        click('Apply')

    try:
        for axis, initial, target in [('XY', 10, 1), ('Z', .1, 1)]:
            home(); set_parameter(axis + ' move distance', initial)
            t = Trace(axis + ' common jog', results)
            t.press(axis + ' move distance')
            if args.candidate:
                t.press('Use 1 mm')
            else:
                t.number(target)
            t.press('X +' if axis == 'XY' else 'Z +')
            expected = 'G0 X1 F1200' if axis == 'XY' else 'G0 Z1 F300'
            wait_until(lambda: expected in scripts()[-1], expected)
            t.save(correct_command=True)
            set_parameter(axis + ' move distance', initial)

        home(); set_parameter('Extrusion distance', 5); set_parameter('E movement speed', 3)
        t = Trace('Exact E distance and speed', results)
        t.press('Extrusion distance'); t.number('7.5')
        t.press('E movement speed'); t.number(4)
        t.press('Feed')
        wait_until(lambda: 'G1 E7.5 F240' in scripts()[-1], 'exact extrusion')
        t.save(correct_command=True)
        set_parameter('Extrusion distance', 5); set_parameter('E movement speed', 3)

        for heater, target in [('nozzle', 215), ('bed', 65)]:
            home()
            t = Trace(heater.title() + ' exact target', results)
            t.press('Set ' + heater + ' temperature'); t.press('Custom target')
            title = next(n.get('text') for n in snapshot().iter('node')
                         if n.get('class') == 'android.widget.TextView' and
                         ('target' in n.get('text', '').lower() or 'temperature' in n.get('text', '').lower()))
            t.number(target)
            key = 'extruder' if heater == 'nozzle' else 'heater_bed'
            expected = f'SET_HEATER_TEMPERATURE HEATER={key} TARGET={target}'
            wait_until(lambda: scripts()[-1] == expected, expected)
            t.save(correct_command=True, editor_title=title,
                   contextual_units=heater.title() in title and '°C' in title)

        home()
        t = Trace('Cancel nozzle custom target', results)
        t.press('Set nozzle temperature'); t.press('Custom target')
        t.press('Digit 3')
        t.press('Cancel' if args.candidate else 'Close')
        t.save(no_command=not scripts()[t.command_start:],
               returns_to_heater_context=location(snapshot()) == 'dialog: Nozzle temperature')
        if any(n.get('text') == 'Close' and n.get('clickable') == 'true' for n in snapshot().iter('node')):
            click('Close')

        for load in (False, True):
            home(hot=False)
            click('Prepare'); click('Filament'); click('PLA 200°'); click('Home')
            t = Trace('Cold ' + ('Load' if load else 'Unload') + ' and cancel', results)
            t.press('Load filament' if load else 'Unload filament')
            wait_text('Heating to 200°')
            assert scripts()[t.command_start:] == ['SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200']
            t.press('Cancel heating')
            wait_until(lambda: scripts()[-1] == 'SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0', 'heater restoration')
            t.save(correct_command=True, no_extrusion=True)

        home(paused=True)
        click('Prepare'); click('Filament'); click('PLA 200°'); click('Home')
        t = Trace('Paused filament change and resume', results)
        t.press('Unload filament')
        wait_until(lambda: 'UNLOAD_FILAMENT' in scripts()[-1], 'hot unload')
        t.press('Feed 5 mm')
        wait_until(lambda: 'G1 E5 F180' in scripts()[-1], 'manual feed')
        t.press('Load filament')
        wait_until(lambda: 'LOAD_FILAMENT' in scripts()[-1], 'hot load')
        t.press('Resume print'); t.press('Resume')
        wait_text('Printing')
        t.save(correct_command=True, resumed=True)

        stop = next(n for n in snapshot().iter('node') if n.get('content-desc') == 'Emergency stop')
        l, top, r, bottom = map(int, re.findall(r'\d+', stop.get('bounds')))
        click('Set nozzle temperature'); click('Custom target')
        t = Trace('E-stop from numeric editor', results)
        before = len(get('/fixture/requests'))
        t.press('Emergency stop', ((l+r)//2, (top+bottom)//2))
        wait_until(lambda: any(r['path'] == '/printer/emergency_stop'
                              for r in get('/fixture/requests')[before:]), 'emergency stop')
        wait_text('Printer shutdown')
        t.save(correct_command=True, persistent_stop=True)
        click('More'); click('Maintenance'); click('Restart Klipper'); click('Restart Klipper')
        # Return to the benchmark's explicit idle fixture after recovery.
        home()
        record['cleanup_completed'] = True
    finally:
        record['completed'] = len(results) == 10
        (args.output/'actuations.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
        state(**original)
        click('Home')


if __name__ == '__main__':
    main()
