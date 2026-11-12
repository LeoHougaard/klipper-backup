"""Home exposes the selected filament temperature and preserves it on restart."""
import argparse
import json
from pathlib import Path
from check_controls import AGENT_DEVICE_COMMAND, PACKAGE, SERIAL, device, snapshot, verify_fixture
from check_workflows import click, wait_text, wait_until
from score_actuations import get, scripts, state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert SERIAL and SERIAL.startswith('emulator-') and AGENT_DEVICE_COMMAND
    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?'+'&'.join(names))['status']
    try:
        state(webhooks={'state': 'ready'}, print_stats={'state': 'standby', 'filename': ''},
              manual_probe={'is_active': False}, extruder={'temperature': 22, 'target': 0, 'can_extrude': False},
              heater_bed={'temperature': 23, 'target': 0})
        click('Home')
        before = len(scripts())
        click('Filament temperature'); click('Use 240 °C')
        wait_until(lambda: any(n.get('content-desc') == 'Filament temperature' and n.get('text') == '240°'
                              for n in snapshot().iter('node')), 'visible Home filament target')
        assert len(scripts()) == before
        device('screenshot', str((args.output/'home-abs.png').resolve()))
        device('open', PACKAGE, '--relaunch', '--launch-args=--es', '--launch-args=test_endpoint',
               '--launch-args=http://127.0.0.1:18725')
        verify_fixture(18725)
        wait_until(lambda: any(n.get('content-desc') == 'Filament temperature' and n.get('text') == '240°'
                              for n in snapshot().iter('node')), 'remembered material after restart')
        click('Load filament'); wait_text('Heating to 240°')
        assert scripts()[before:] == ['SET_HEATER_TEMPERATURE HEATER=extruder TARGET=240']
        click('Cancel heating')
        wait_until(lambda: scripts()[-1] == 'SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0', 'cancel restoration')
        assert not any('LOAD_FILAMENT' in s for s in scripts()[before:])
        click('Home'); click('Filament temperature'); click('Use 200 °C')
        (args.output/'results.json').write_text(json.dumps({'passed': True, 'selection_inputs': 2,
            'selection_does_not_heat': True, 'restart_preserves_selection': True,
            'home_load_uses_visible_target': True, 'physical_printer_commands': 0}, indent=2), encoding='utf-8')
        print('PASS: two-input Home selection is visible, survives restart and controls cancellable autoheat', flush=True)
    finally:
        state(**original)
        click('Home')


if __name__ == '__main__':
    main()
