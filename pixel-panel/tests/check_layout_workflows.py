"""Review an already-open emulator app against its loopback fixture.

ANDROID_SERIAL must name an emulator. Start fixture_server.py and connect only
the emulator to that loopback endpoint first. This never launches a real phone
or connects to a physical printer. Leaves the review app idle when finished.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
from check_controls import ADB, SERIAL, adb, snapshot, tap, verify_fixture
from check_workflows import click, wait_text, wait_until


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18725)
    parser.add_argument('--output', type=Path, default=Path('pixel-panel/.build/layout-review'))
    args = parser.parse_args()
    assert SERIAL and SERIAL.startswith('emulator-'), 'Select an emulator with ANDROID_SERIAL'
    args.output.mkdir(parents=True, exist_ok=True)
    endpoint = f'http://127.0.0.1:{args.port}'

    def get(path):
        with urlopen(endpoint + path, timeout=5) as r:
            return json.load(r)['result']

    def set_state(**objects):
        data = json.dumps(objects).encode()
        with urlopen(Request(endpoint + '/fixture/state', data=data,
                             headers={'Content-Type': 'application/json'}), timeout=5):
            pass

    def scripts():
        return [json.loads(r['body'])['script'] for r in get('/fixture/requests')
                if r['path'] == '/printer/gcode/script']

    def bounds(node):
        return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))

    def find(root, label):
        return next(n for n in root.iter('node') if label in
                    (n.get('text'), n.get('content-desc')))

    def capture(name):
        root = snapshot()
        (args.output / (name + '.xml')).write_text(
            ET.tostring(root, encoding='unicode'), encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(
            [str(ADB), '-s', SERIAL, 'exec-out', 'screencap', '-p']))
        return root

    verify_fixture(args.port)
    objects = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?' + '&'.join(objects))['status']
    initial_requests = len(get('/fixture/requests'))
    try:
        set_state(print_stats={'state':'standby','filename':'','print_duration':0},
                  webhooks={'state':'ready'}, manual_probe={'is_active':False},
                  extruder={'temperature':22,'target':0,'can_extrude':False},
                  heater_bed={'temperature':23,'target':0},
                  toolhead={'homed_axes':'xyz','position':[60,60,5,0],
                            'axis_minimum':[0,0,-1.5],'axis_maximum':[120,120,120]})
        wait_text('Ready')
        root = capture('01-idle')
        nav = [bounds(find(root, 'Open ' + tab)) for tab in ['Home','Prepare','Files','Camera','More']]
        assert max(y[3]-y[1] for y in nav)-min(y[3]-y[1] for y in nav) <= 1, nav
        assert max(nav[i+1][1]-nav[i][3] for i in range(4))-min(nav[i+1][1]-nav[i][3] for i in range(4)) <= 1
        assert not any(n.get('text') in ('PREHEAT','Stored prints','Voron 0.2') for n in root.iter('node'))
        home = bounds(find(root,'Home axes'))
        nozzle = bounds(find(root,'Set nozzle temperature'))
        stop = bounds(find(root,'Emergency stop'))
        assert nozzle[0] > home[2] and abs(nozzle[1]-nav[0][1]) <= 1
        rail_x = nozzle[0]
        assert abs(stop[3]-nav[-1][3]) <= 1
        print('PASS: even navigation, right-side status, no duplicate header/library/preheat labels', flush=True)

        # Vertical gestures on the side rail still commit only once on release.
        x,y,r,b = nozzle
        before = len(scripts())
        adb('shell','input','swipe',str((x+r)//2),str((y+b)//2),str((x+r)//2),str((y+b)//2+168),'450')
        wait_until(lambda: len(scripts()) == before+1, 'one heater gesture command')
        assert scripts()[-1] == 'SET_HEATER_TEMPERATURE HEATER=extruder TARGET=40'
        tap('Set nozzle temperature')
        wait_text('Nozzle temperature')
        tap('Custom target')
        wait_text('Temperature target')
        keypad = capture('02-temperature-keypad')
        for n in keypad.iter('node'):
            if n.get('clickable') == 'true' and n.get('enabled') == 'true':
                a,b,c,d = bounds(n)
                assert c <= rail_x and b >= 0 and d <= 1080, (n.attrib, rail_x)
        adb('shell','input','keyevent','4')
        wait_text('Nozzle temperature')
        tap('Close')
        print('PASS: vertical heater gesture and keypad fit beside persistent emergency controls', flush=True)

        set_state(print_stats={'state':'printing','filename':'test-plate.gcode','print_duration':3760},
                  virtual_sdcard={'progress':.27},
                  extruder={'temperature':239.6,'target':240,'can_extrude':True},
                  heater_bed={'temperature':99.8,'target':100})
        wait_text('Printing')
        root = capture('03-printing')
        assert not any(n.get('text') in ('Live tuning','Speed','Flow','Z OFFSET') for n in root.iter('node'))
        tap('Tune print')
        wait_text('Tune this print')
        capture('04-tuning')
        before = len(scripts())
        tap('Closer −0.025')
        wait_until(lambda: len(scripts()) == before+1, 'live Z command')
        assert scripts()[-1] == 'SET_GCODE_OFFSET Z_ADJUST=-0.025 MOVE=1'
        tap('Speed 100%')
        wait_text('Speed (%)')
        tap('Clear')
        for digit in '115': tap('Digit '+digit)
        tap('Apply')
        wait_until(lambda: scripts()[-1] == 'M220 S115', 'precision speed applied')
        wait_text('115%')
        tap('Close')
        tap('Tune print')
        wait_text('Tune this print')
        tap('Close')
        assert not any(n.get('text') == 'Z offset' for n in snapshot().iter('node'))
        print('PASS: tuning stays behind one button; live Z and exact speed remain usable', flush=True)

        tap('Pause')
        wait_text('Print paused')
        capture('05-paused')
        tap('Change filament')
        wait_text('Change filament')
        capture('06-paused-filament')
        tap('Home')
        tap('Resume')
        wait_text('Resume this print?')
        tap('Resume')
        wait_text('Printing')
        print('PASS: pause, filament access and resume preserve their workflow', flush=True)

        set_state(print_stats={'state':'standby','filename':''},
                  manual_probe={'is_active':False},
                  extruder={'temperature':22,'target':0,'can_extrude':False})
        wait_text('Ready')
        tap('Prepare')
        tap('Move')
        capture('07-move')
        tap('Bed setup')
        wait_text('Front / rear bed calibration')
        capture('08-bed-setup')
        tap('Start paper calibration')
        tap('Home & calibrate')
        wait_text('Front paper contact')
        capture('09-front-contact')
        click('Accept contact')
        wait_text('Rear paper contact')
        capture('10-rear-contact')
        click('Accept contact')
        wait_text('Calibration saved')
        tap('Files')
        tap('Print details: test-plate.gcode')
        wait_text('Print details')
        capture('11-print-details')
        tap('Close')
        print('PASS: paper calibration and Files details remain available without redundant Home navigation', flush=True)

        tap('Home')
        tap('Set nozzle temperature')
        tap('Custom target')
        wait_text('Temperature target')
        # Native accessibility reports only the dialog window, so use saved
        # emergency bounds from the verified main screen, which stays fixed.
        a,b,c,d = stop
        adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
        wait_until(lambda: any(r['path']=='/printer/emergency_stop'
                              for r in get('/fixture/requests')[initial_requests:]), 'emergency stop through keypad')
        wait_text('Printer shutdown')
        print('PASS: emergency stop works with keypad open', flush=True)
        (args.output/'results.json').write_text(json.dumps({'passed':True,'serial':SERIAL,'physical_printer_commands':0},indent=2))
    finally:
        # Back closes a sheet; Home then returns to the normal review state.
        adb('shell','input','keyevent','4')
        set_state(**original)
        tap('Home')


if __name__ == '__main__':
    main()
