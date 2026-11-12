"""Home motion wheel, feed rates and direct print-speed editor on the emulator."""
import json
import math
import re
from pathlib import Path
from urllib.request import Request, urlopen
from check_controls import SERIAL, adb, snapshot, tap, verify_fixture
from check_workflows import click, wait_text, wait_until


def main():
    assert SERIAL and SERIAL.startswith('emulator-')
    endpoint = 'http://127.0.0.1:18725'

    def get(path):
        with urlopen(endpoint + path, timeout=5) as r: return json.load(r)['result']

    def state(**objects):
        with urlopen(Request(endpoint + '/fixture/state', data=json.dumps(objects).encode(),
                             headers={'Content-Type': 'application/json'}), timeout=5): pass

    def scripts():
        return [json.loads(r['body'])['script'] for r in get('/fixture/requests')
                if r['path'] == '/printer/gcode/script']

    def number(value):
        click('Clear')
        for digit in str(value): click('Decimal point' if digit == '.' else 'Digit ' + digit)
        click('Apply')

    def center(root, label):
        n = next(n for n in root.iter('node') if label in (n.get('text'), n.get('content-desc')))
        a,b,c,d = map(int, re.findall(r'\d+', n.get('bounds')))
        assert c-a >= 126 and d-b >= 126, (label, n.attrib)
        return (a+c)//2, (b+d)//2

    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?' + '&'.join(names))['status']
    try:
        state(webhooks={'state':'ready'}, print_stats={'state':'standby','filename':''},
              manual_probe={'is_active':False}, extruder={'temperature':200,'target':200,'can_extrude':True},
              toolhead={'homed_axes':'xyz','position':[60,60,5,0],
                        'axis_minimum':[0,0,-1.5],'axis_maximum':[120,120,120]})
        wait_text('Ready'); click('Home')
        for axis, speed in [('XY',40),('Z',2),('E',4)]:
            click(axis + ' movement speed'); number(speed)
        click('Extrusion distance'); number(5)
        before = len(scripts())
        click('X +')
        wait_until(lambda: len(scripts()) == before+1, 'Home XY jog')
        assert 'G0 X10 F2400' in scripts()[-1]
        click('Z +')
        wait_until(lambda: len(scripts()) == before+2, 'Home Z jog')
        assert 'G0 Z0.1 F120' in scripts()[-1]
        click('E +')
        wait_until(lambda: len(scripts()) == before+3, 'Home E jog')
        assert 'G1 E5 F240' in scripts()[-1]
        root = snapshot()
        cx,cy = center(root, 'Home axes')
        ux,uy = center(root, 'Y +')
        radius = (cy-uy)/.64
        angle = math.radians(-110)
        x,y = int(cx+math.cos(angle)*radius*.85), int(cy+math.sin(angle)*radius*.85)
        adb('shell','input','tap',str(x),str(y))
        wait_until(lambda: len(scripts()) == before+4, 'whole wheel sector responds outside label')
        assert 'G0 Y10 F2400' in scripts()[-1]
        adb('shell','input','swipe',str(ux),str(uy),str(cx),str(cy+radius*.8),'600')
        # Fresh snapshot waits for the native frame; a drag must not jog.
        snapshot()
        assert len(scripts()) == before+4
        print('PASS: Home XYZE use edited speeds; the whole wheel sector works and a drag cancels',flush=True)
        click('Prepare')
        before = len(scripts())
        click('X −')
        wait_until(lambda: len(scripts()) == before+1, 'dedicated Move XY jog')
        assert 'G0 X-10 F2400' in scripts()[-1]
        click('E −')
        wait_until(lambda: len(scripts()) == before+2, 'dedicated Move E retraction')
        assert 'G1 E-5 F240' in scripts()[-1]
        state(print_stats={'state':'paused','filename':'test-plate.gcode'})
        wait_text('Print paused')
        click('Z −')
        wait_until(lambda: len(scripts()) == before+3, 'paused dedicated Move Z jog')
        assert 'G0 Z-0.1 F120' in scripts()[-1]
        print('PASS: adjacent Move XY/Z/E controls retain edited speeds and paused motion',flush=True)
        state(print_stats={'state':'printing','filename':'test-plate.gcode'})
        wait_text('Printing')
        root = snapshot()
        assert not any(n.get('enabled') == 'true' and n.get('text') in ('X +','Y +','Z +','E +') for n in root.iter('node'))
        click('Home')
        click('Speed 100%'); number(115)
        wait_until(lambda: scripts()[-1] == 'M220 S115', 'direct print speed')
        wait_text('Speed 115%')
        print('PASS: print speed edits directly from the active job',flush=True)
        output = Path('pixel-panel/.build/space-review')
        output.mkdir(exist_ok=True)
        (output/'speed-results.json').write_text(json.dumps({'passed':True,'physical_printer_commands':0},indent=2))
    finally:
        try:
            adb('shell','input','keyevent','4')
            state(**original)
            click('Prepare')
            for axis, speed in [('XY',20),('Z',5),('E',3)]:
                click(axis+' movement speed'); number(speed)
            click('Extrusion distance'); number(5)
            click('Home')
        finally:
            state(**original)


if __name__ == '__main__': main()
