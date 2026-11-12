"""Exercise the focused filament task on an already-open review emulator."""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import Request, urlopen
from check_controls import ADB_COMMAND, AGENT_DEVICE_COMMAND, SERIAL, device, snapshot, tap, verify_fixture
from check_workflows import click, wait_text, wait_until


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('pixel-panel/.build/filament-task'))
    args = parser.parse_args()
    assert SERIAL and SERIAL.startswith('emulator-'), 'Select the isolated emulator explicitly'
    args.output.mkdir(parents=True, exist_ok=True)
    endpoint = 'http://127.0.0.1:18725'

    def get(path):
        with urlopen(endpoint + path, timeout=5) as response:
            return json.load(response)['result']

    def state(**objects):
        with urlopen(Request(endpoint + '/fixture/state', data=json.dumps(objects).encode(),
                             headers={'Content-Type': 'application/json'}), timeout=5):
            pass

    def scripts():
        return [json.loads(r['body'])['script'] for r in get('/fixture/requests')
                if r['path'] == '/printer/gcode/script']

    def enter(value):
        tap('Clear')
        for char in value:
            tap('Decimal point' if char == '.' else 'Digit ' + char)
        tap('Apply')

    def capture(name):
        root = snapshot()
        (args.output / (name + '.xml')).write_text(ET.tostring(root, encoding='unicode'), encoding='utf-8')
        if AGENT_DEVICE_COMMAND:
            device('screenshot', str((args.output / (name + '.png')).resolve()))
        else:
            (args.output / (name + '.png')).write_bytes(subprocess.check_output(
                [*ADB_COMMAND, 'exec-out', 'screencap', '-p']))
        for node in root.iter('node'):
            if node.get('class') != 'android.widget.Button' or node.get('clickable') != 'true':
                continue
            left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
            # Review device density is 420 dpi. Every native action retains 48 dp.
            assert min(right-left, bottom-top) >= 126, (name, node.attrib)
        return root

    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?' + '&'.join(names))['status']
    try:
        state(print_stats={'state': 'standby', 'filename': ''}, webhooks={'state': 'ready'},
              manual_probe={'is_active': False},
              extruder={'temperature': 22, 'target': 0, 'can_extrude': False})
        click('Prepare'); click('Filament'); click('PLA 200°')
        before = len(scripts())
        click('Filament temperature'); enter('230')
        wait_until(lambda: any(n.get('content-desc') == 'Filament temperature' and n.get('text') == '230°'
                              for n in snapshot().iter('node')), 'custom material temperature readback')
        assert len(scripts()) == before, 'Selecting material must not turn a heater on'
        capture('01-custom-cold')
        click('Load filament'); wait_text('Heating to 230°')
        assert scripts()[before:] == ['SET_HEATER_TEMPERATURE HEATER=extruder TARGET=230']
        capture('02-heating')
        click('Cancel heating')
        wait_until(lambda: len(scripts()) == before+2, 'restore owned heater target')
        assert scripts()[-1] == 'SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0'
        print('PASS: custom material selection sends no command; Load heats automatically; cancel prevents extrusion', flush=True)

        state(extruder={'temperature': 230, 'target': 230, 'can_extrude': True})
        wait_text('At temperature')
        click('Extrusion distance'); enter('7.5')
        click('E movement speed'); enter('4')
        click('Feed 7.5 mm')
        wait_until(lambda: 'G1 E7.5 F240' in scripts()[-1], 'editable manual feed')
        click('Retract 7.5 mm')
        wait_until(lambda: 'G1 E-7.5 F240' in scripts()[-1], 'editable manual retract')
        capture('03-custom-feed')
        print('PASS: manual feed and retract share exact distance and speed with Move', flush=True)

        state(print_stats={'state': 'paused', 'filename': 'test-plate.gcode'},
              extruder={'temperature': 22, 'target': 230, 'can_extrude': False})
        wait_text('Print paused')
        click('Unload filament'); wait_text('Heating to 230°')
        root = capture('04-paused-heating')
        assert not any(n.get('text') == 'Resume print' for n in root.iter('node'))
        click('Cancel heating')
        wait_text('Resume print')
        capture('05-paused')
        click('Move')
        wait_text('7.5 mm'); wait_text('4 mm/s')
        print('PASS: paused heating exposes Cancel without overlapping Resume; Move preserves manual parameters', flush=True)
        (args.output/'results.json').write_text(json.dumps({'passed': True, 'physical_printer_commands': 0}, indent=2))
    finally:
        click('Home')
        state(**original)
        click('Prepare')
        click('Extrusion distance'); enter('5')
        click('E movement speed'); enter('3')
        click('Filament'); click('PLA 200°'); click('Home')


if __name__ == '__main__':
    main()
