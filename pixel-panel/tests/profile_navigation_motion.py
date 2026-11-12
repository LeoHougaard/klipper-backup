"""Profile page transitions without accessibility snapshots during the animation.

The label-guided workflow recorder remains useful for correctness, but inspection
can perturb a software emulator. This separate run pins navigation coordinates
once, then records each transition before querying the tree again.
"""
import argparse
import json
import re
import time
from pathlib import Path
from check_controls import AGENT_DEVICE_COMMAND, PACKAGE, SERIAL, adb, device, snapshot, verify_fixture
from check_workflows import click, wait_text
from score_actuations import state, get
from record_ui_motion import frame_times, frame_components


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--label', default='navigation')
    parser.add_argument('--video', action='store_true')
    args = parser.parse_args()
    assert SERIAL and SERIAL.startswith('emulator-') and AGENT_DEVICE_COMMAND
    args.output.mkdir(parents=True, exist_ok=True)
    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?'+'&'.join(names))['status']
    state(webhooks={'state':'ready'}, print_stats={'state':'standby'}, manual_probe={'is_active':False},
          extruder={'temperature':22,'target':0,'can_extrude':False}, heater_bed={'temperature':23,'target':0})
    click('Home'); wait_text('Ready')
    positions = {}
    for n in snapshot().iter('node'):
        label = n.get('content-desc','')
        if label in ['Open '+p for p in ('Home','Prepare','Files','More')]:
            l,t,r,b = map(int,re.findall(r'\d+',n.get('bounds')))
            positions[label.removeprefix('Open ')] = [(l+r)//2,(t+b)//2]
    assert len(positions)==4, positions
    markers=[]
    final_correct=False
    if args.video:
        device('record','start',str((args.output/(args.label+'.mp4')).resolve()),'--scope','device','--quality','high','--hide-touches')
    try:
        for index,page in enumerate(['Prepare','Home','Files','Home','More','Home']*2):
            adb('shell','dumpsys','gfxinfo',PACKAGE,'reset')
            device('press',*[str(v) for v in positions[page]])
            time.sleep(.9)
            raw=adb('shell','dumpsys','gfxinfo',PACKAGE,'framestats')
            filename=args.label+'-'+str(index).zfill(2)+'-framestats.txt'
            (args.output/filename).write_text(raw,encoding='utf-8')
            markers.append({'page':page,'raw_framestats':filename,
                            'frame_ms':frame_times(raw),'frame_components':frame_components(raw)})
        final_correct=any(n.get('content-desc')=='Screen: Home' for n in snapshot().iter('node'))
        assert final_correct
    finally:
        if args.video:
            (args.output/(args.label+'-export.txt')).write_text(device('record','stop'),encoding='utf-8')
        values=sorted(v for m in markers for v in m['frame_ms'])
        report={'label':args.label,'accessibility_inspection_during_animation':False,
                'recording_active':args.video,'positions':positions,'markers':markers,
                'frame_count':len(values),'p95_ms':values[int(len(values)*.95)] if values else None,
                'final_destination_correct':final_correct,
                'animator_duration_scale':adb('shell','settings','get','global','animator_duration_scale').strip(),
                'renderer':next((s.strip() for s in adb('shell','dumpsys','SurfaceFlinger').splitlines() if s.startswith('GLES:')),'not reported'),
                'scope':'Twelve native page transitions on review emulator; no inspection during each transition; not a physical-phone benchmark',
                'frame_latency_definition':'FrameCompleted minus IntendedVsync; this is latency, not FPS or inter-frame cadence. Raw frame records are retained.',
                'physical_printer_commands':0}
        (args.output/(args.label+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
        state(**original)
    print(args.label,len(values),'frames; p95',report['p95_ms'],'ms',flush=True)


if __name__=='__main__':
    main()
