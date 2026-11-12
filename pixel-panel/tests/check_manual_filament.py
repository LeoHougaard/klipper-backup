"""Manual XYZE and cancellable automatic filament operations on a review emulator."""
import json
import subprocess
from pathlib import Path
from urllib.request import Request, urlopen
from check_controls import ADB_COMMAND, SERIAL, adb, snapshot, tap, verify_fixture
from check_workflows import click, wait_text, wait_until


def main():
    assert SERIAL and SERIAL.startswith('emulator-'), 'Select the review emulator explicitly'
    endpoint = 'http://127.0.0.1:18725'
    output = Path('pixel-panel/.build/compact-review')
    output.mkdir(parents=True, exist_ok=True)

    def get(path):
        with urlopen(endpoint+path, timeout=5) as r: return json.load(r)['result']

    def set_state(**objects):
        with urlopen(Request(endpoint+'/fixture/state', data=json.dumps(objects).encode(),
                             headers={'Content-Type':'application/json'}), timeout=5): pass

    def scripts():
        return [json.loads(r['body'])['script'] for r in get('/fixture/requests')
                if r['path']=='/printer/gcode/script']

    def capture(name):
        (output/(name+'.png')).write_bytes(subprocess.check_output([*ADB_COMMAND,'exec-out','screencap','-p']))

    def digits(value):
        tap('Clear')
        for digit in value: tap('Decimal point' if digit=='.' else 'Digit '+digit)
        tap('Apply')

    def hot(target=200, actual=None):
        set_state(extruder={'temperature':target if actual is None else actual,
                            'target':target, 'can_extrude':True})

    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?'+'&'.join(names))['status']
    try:
        set_state(print_stats={'state':'standby','filename':''},webhooks={'state':'ready'},
                  manual_probe={'is_active':False},extruder={'temperature':22,'target':0,'can_extrude':False},
                  toolhead={'homed_axes':'xyz','position':[60,60,5,0],
                            'axis_minimum':[0,0,-1.5],'axis_maximum':[120,120,120]})
        click('Prepare'); click('Filament'); click('PLA 200°'); click('Home')
        before=len(scripts())
        click('Load filament'); wait_text('Heating to 200°')
        assert scripts()[before:]==['SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200']
        capture('heat-before-load')
        click('Cancel heating')
        wait_until(lambda: len(scripts())==before+2,'cancel target restoration')
        assert scripts()[-1]=='SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0'
        click('ABS 240°')
        before=len(scripts())
        click('Load filament'); wait_text('Heating to 240°')
        assert scripts()[before:]==['SET_HEATER_TEMPERATURE HEATER=extruder TARGET=240']
        hot(240)
        wait_until(lambda: len(scripts())==before+2,'load after thermal readiness')
        assert 'LOAD_FILAMENT\n' in scripts()[-1]
        click('Unload filament')
        wait_until(lambda: len(scripts())==before+3,'hot unload once')
        assert 'UNLOAD_FILAMENT\n' in scripts()[-1]
        print('PASS: Load heats selected material first, cancellation prevents extrusion, and hot Load/Unload each run once',flush=True)

        set_state(extruder={'temperature':22,'target':0,'can_extrude':False})
        before=len(scripts())
        click('Unload filament'); wait_text('Heating to 240°')
        click('Move')
        wait_until(lambda: len(scripts())==before+2,'leaving filament cancels pending extrusion')
        assert scripts()[-1]=='SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0'
        click('E +'); wait_text('Heat the nozzle first')
        assert len(scripts())==before+2
        hot(190)  # Manual E obeys Klipper readiness, independently of the 240° macro preset.
        wait_text('190°')
        click('Extrusion distance'); digits('7.5')
        click('E +')
        wait_until(lambda: 'G1 E7.5 F180' in scripts()[-1],'manual extrusion distance')
        click('E −')
        wait_until(lambda: 'G1 E-7.5 F180' in scripts()[-1],'manual retraction distance')
        click('Set X position'); digits('80')
        wait_until(lambda: 'G0 X20 F1200' in scripts()[-1],'absolute physical X through relative safe move')
        click('Y +')
        wait_until(lambda: 'G0 Y10 F1200' in scripts()[-1],'Y jog')
        click('Z +')
        wait_until(lambda: 'G0 Z0.1 F300' in scripts()[-1],'Z jog')
        for script in scripts()[before+2:]:
            assert 'SAVE_GCODE_STATE' in script and 'RESTORE_GCODE_STATE' in script
        capture('manual-xyze')
        print('PASS: manual XYZE, exact X position and 7.5 mm E moves preserve G-code state; cold manual E sends nothing',flush=True)

        set_state(print_stats={'state':'paused','filename':'test-plate.gcode'})
        wait_text('Print paused')
        click('X +')
        wait_until(lambda: 'G0 X10 F1200' in scripts()[-1],'manual movement while paused')
        set_state(toolhead={'homed_axes':'','position':[90,70,5.1,0],
                            'axis_minimum':[0,0,-1.5],'axis_maximum':[120,120,120]})
        before=len(scripts())
        click('X +'); wait_text('Home X first')
        assert len(scripts())==before
        print('PASS: paused manual movement is available and unhomed movement remains blocked',flush=True)
        (output/'manual-results.json').write_text(json.dumps({'passed':True,'physical_printer_commands':0},indent=2))
    finally:
        # Leave the existing review endpoint and material choice intact.
        try:
            click('Home')
            set_state(**original)
            click('Prepare'); click('Filament'); click('PLA 200°'); click('Home')
        except Exception:
            set_state(**original)


if __name__=='__main__': main()
