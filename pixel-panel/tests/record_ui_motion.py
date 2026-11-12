"""Record real UI motion and renderer timings through the bound Device session."""
import argparse
import json
import re
import time
from pathlib import Path
from check_controls import AGENT_DEVICE_COMMAND, PACKAGE, SERIAL, adb, device, snapshot, verify_fixture
from check_workflows import click, wait_text
from score_actuations import get, state


def frame_records(raw):
    result = []
    header = None
    for line in raw.splitlines():
        cells = line.strip().strip(',').split(',')
        if cells[0] == 'Flags':
            header = cells
        elif header and len(cells) == len(header) and cells[0].isdigit():
            values = dict(zip(header, cells))
            if values.get('Flags') != '0':
                continue
            start, end = int(values['IntendedVsync']), int(values['FrameCompleted'])
            if end > start:
                result.append({key: int(value) for key, value in values.items()})
    return result


def frame_times(raw):
    return [(f['FrameCompleted']-f['IntendedVsync'])/1_000_000 for f in frame_records(raw)]


def frame_components(raw):
    pairs = {'intended_vsync_to_input_ms': ('IntendedVsync', 'HandleInputStart'),
             'input_to_sync_queued_ms': ('HandleInputStart', 'SyncQueued'),
             'sync_start_to_swap_ms': ('SyncStart', 'SwapBuffers'),
             'swap_to_complete_ms': ('SwapBuffers', 'FrameCompleted')}
    return [{name: (f[end]-f[start])/1_000_000 for name, (start,end) in pairs.items()
             if start in f and end in f and f[end] >= f[start] > 0} for f in frame_records(raw)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--label', default='normal')
    parser.add_argument('--no-video', action='store_true', help='Measure renderer separately from recording overhead')
    parser.add_argument('--focus', choices=['filament-home'])
    args = parser.parse_args()
    assert SERIAL and SERIAL.startswith('emulator-') and AGENT_DEVICE_COMMAND
    args.output.mkdir(parents=True, exist_ok=True)
    for _ in range(3):
        root = snapshot()
        close = next((label for label in ('Cancel', 'Close') if any(
            n.get('text') == label and n.get('clickable') == 'true' for n in root.iter('node'))), None)
        if close is None:
            break
        click(close)
    verify_fixture(18725)
    names = get('/printer/objects/list')['objects']
    original = get('/printer/objects/query?'+'&'.join(names))['status']
    state(webhooks={'state': 'ready'}, print_stats={'state': 'standby', 'filename': 'test-plate.gcode'},
          manual_probe={'is_active': False}, extruder={'temperature': 22, 'target': 0, 'can_extrude': False},
          heater_bed={'temperature': 23, 'target': 0})
    click('Home'); wait_text('Ready')
    scale = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
    renderer = next((line.strip() for line in adb('shell', 'dumpsys', 'SurfaceFlinger').splitlines()
                     if line.startswith('GLES:')), 'not reported')
    cores = adb('shell', 'cat', '/sys/devices/system/cpu/online').strip()
    markers = []
    rapid_navigation_correct = False
    video = (args.output/(args.label+'.mp4')).resolve()
    if not args.no_video:
        device('record', 'start', str(video), '--scope', 'device', '--quality', 'high', '--hide-touches')
    started = time.monotonic()
    try:
        steps = ('Filament temperature', 'Use 240 °C', 'Filament temperature', 'Cancel', 'Prepare', 'Filament', 'Home') if args.focus else (
                      'Prepare', 'Filament', 'Filament temperature', 'Cancel', 'Move',
                      'XY move distance', 'Use 1 mm', 'Home', 'Set nozzle temperature',
                      'Custom target', 'Cancel', 'Close', 'Files', 'More', 'Maintenance', 'Close', 'Home')
        for label in steps:
            adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset')
            before = time.monotonic()
            click(label)
            raw = adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats')
            raw_name = args.label+'-'+str(len(markers)).zfill(2)+'-framestats.txt'
            (args.output/raw_name).write_text(raw, encoding='utf-8')
            marker = {'raw_framestats': raw_name, 'frame_components': frame_components(raw), 'target': label, 'start_seconds': round(before-started, 3),
                      'end_seconds': round(time.monotonic()-started, 3), 'frame_ms': frame_times(raw)}
            markers.append(marker)
        root = snapshot()
        prepare = next(n for n in root.iter('node') if n.get('content-desc') == 'Open Prepare')
        l,t,r,b = map(int, re.findall(r'\d+', prepare.get('bounds')))
        device('press', str((l+r)//2), str((t+b)//2), '--count', '3', '--interval-ms', '40', '--settle')
        assert any(n.get('content-desc') == 'Screen: Prepare' for n in snapshot().iter('node'))
        rapid_navigation_correct = True
    finally:
        if not args.no_video:
            result = device('record', 'stop')
            (args.output/(args.label+'-export.txt')).write_text(result, encoding='utf-8')
        ordered = sorted(value for item in markers for value in item['frame_ms'])
        summary = {'label': args.label, 'animator_duration_scale': scale,
                   'recording_active': not args.no_video,
                   'renderer': renderer, 'cpu_online': cores, 'focus': args.focus, 'markers': markers,
                   'renderer_frame_count': len(ordered),
                   'renderer_p95_ms': ordered[min(len(ordered)-1, int(len(ordered)*.95))] if ordered else None,
                   'renderer_over_33ms': sum(value > 33.34 for value in ordered),
                   'rapid_navigation_final_state_correct': rapid_navigation_correct,
                   'measurement_scope': 'Activity renderer on emulator; WindowManager transitions assessed from actual recording',
                   'frame_latency_definition': 'FrameCompleted minus IntendedVsync; latency is not FPS. Label-guided inspection can perturb the emulator.',
                   'physical_printer_commands': 0}
        (args.output/(args.label+'.json')).write_text(json.dumps(summary, indent=2), encoding='utf-8')
    click('XY move distance'); click('Use 10 mm'); click('Home')
    if args.focus:
        click('Filament temperature'); click('Use 200 °C')
    state(**original)
    print(args.label, len(ordered), 'frames; p95', summary['renderer_p95_ms'], 'ms', flush=True)


if __name__ == '__main__':
    main()
