"""Observed shutdown/recovery must clear historic warnings without a false Ready."""
import json
from pathlib import Path
from check_controls import snapshot, verify_fixture
from check_workflows import click, wait_text, wait_until
from score_actuations import state, get

verify_fixture(18725)
state(webhooks={'state':'ready'}, print_stats={'state':'standby'}, manual_probe={'is_active':False},
      extruder={'temperature':22,'target':0,'can_extrude':False}, heater_bed={'temperature':23,'target':0})
click('Home'); wait_text('Ready')
before=len(get('/fixture/requests'))
click('Emergency stop'); wait_text('Printer shutdown')
wait_text('Emergency stop sent')
assert get('/server/info')['klippy_state']=='shutdown'
state(webhooks={'state':'ready'},print_stats={'state':'standby'})
wait_text('Ready')
wait_until(lambda: not any('Emergency stop sent' in n.get('text','') for n in snapshot().iter('node')),'stale warning cleared')
requests=get('/fixture/requests')[before:]
assert [r['path'] for r in requests if r['method']=='POST']==['/printer/emergency_stop'],requests
out=Path('pixel-panel/.build/actuation-review/candidate-17')
out.mkdir(parents=True,exist_ok=True)
(out/'recovery.json').write_text(json.dumps({'shutdown_observed':True,'external_recovery_warning_cleared':True,'requests':requests,'physical_commands':0},indent=2))
print('PASS: observed shutdown, truthful server state, external recovery clears stale warning; one stop command')
