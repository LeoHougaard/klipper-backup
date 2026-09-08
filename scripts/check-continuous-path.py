"""Replay an uploaded continuous path through the printer's actual lookahead.

Usage: python check-continuous-path.py toolhead.py upload.gcode settings.json
settings.json contains Moonraker configfile.settings. No hardware is activated.
This checks geometry and queue requests, not step generation or physical feed.
"""
import ast
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'klipper_extras'))
from continuous_extrusion import ContinuousExtrusion


def check(source_path, gcode_path, settings_path):
    source = Path(source_path).read_bytes()
    classes = [n for n in ast.parse(source).body if isinstance(n, ast.ClassDef)
               and n.name in ('Move', 'LookAheadQueue')]
    assert len(classes) == 2
    ns = {'math': math, 'LOOKAHEAD_FLUSH_TIME': .150}
    exec(compile(ast.Module(body=classes, type_ignores=[]), source_path, 'exec'), ns)
    settings = json.loads(Path(settings_path).read_text(encoding='utf-8-sig'))
    cfg = settings['printer']
    ext = settings['extruder']
    backend = ContinuousExtrusion.__new__(ContinuousExtrusion)
    backend.rate = .5
    backend.position = 0.
    backend.last_end_time = None
    backend.segments = 0
    failures = []
    backend.printer = SimpleNamespace(invoke_shutdown=failures.append)
    area = math.pi * (ext['filament_diameter'] / 2.) ** 2
    backend.extruder = SimpleNamespace(trapq=None, trapq_append=lambda *a: None,
        filament_area=area, max_extrude_ratio=ext['max_extrude_cross_section']/area,
        last_position=0.)
    toolhead = SimpleNamespace(max_accel=cfg['max_accel'], max_velocity=cfg['max_velocity'],
        junction_deviation=cfg['square_corner_velocity']**2 * (math.sqrt(2.)-1.) / cfg['max_accel'],
        mcr_pseudo_accel=cfg['max_accel']*(1.-cfg['minimum_cruise_ratio']),
        extra_axes=[SimpleNamespace(calc_junction=backend._calc_junction)])
    queue = ns['LookAheadQueue']()
    pos = [0., 0., 0., 0.]
    bounds = [[math.inf, -math.inf] for _ in range(3)]
    active = relative = False
    print_time = 0.
    max_area = 0.
    def process(moves):
        nonlocal print_time, max_area
        for move in moves:
            if min(move.accel_t, move.cruise_t, move.decel_t) < -1e-12:
                raise ValueError(('Negative planned duration', move.__dict__))
            duration = move.accel_t + move.cruise_t + move.decel_t
            max_area = max(max_area, backend.rate*duration*area/move.move_d)
            backend._process_move(print_time, move, 3)
            assert not failures, (failures, move.__dict__, max_area)
            print_time += duration
        assert not failures, failures
    for number, line in enumerate(Path(gcode_path).read_text().splitlines(), 1):
        code = line.split(';', 1)[0].strip()
        if code.startswith('SET_CONTINUOUS_EXTRUSION '):
            rate = float(code.split('RATE=')[1])
            if rate:
                assert not active
                backend.rate = rate
                active = True
            else:
                process(queue.flush())
                active = False
            continue
        if code in ('G90', 'G91'):
            relative = code == 'G91'
        if not re.match(r'^G[01] ', code):
            continue
        values = {k: float(v) for k, v in re.findall(r'([XYZEF])([-+0-9.e]+)', code)}
        end = pos.copy()
        for i, axis in enumerate('XYZ'):
            if axis in values:
                end[i] = values[axis] + (pos[i] if relative else 0.)
        end[3] += values.get('E', 0.)
        if active:
            assert relative and set(values) == set('XYZEF'), number
            assert values['E'] > 0 and values['F'] > 0, number
            for i, axis in enumerate('xyz'):
                limits = settings['stepper_'+axis]
                assert limits.get('position_min', 0.) <= end[i] <= limits['position_max'], (number, end)
                bounds[i][0] = min(bounds[i][0], end[i])
                bounds[i][1] = max(bounds[i][1], end[i])
            move = ns['Move'](toolhead, pos, end, values['F']/60.)
            assert move.is_kinematic_move
            if move.axes_d[2]:
                ratio = move.move_d/abs(move.axes_d[2])
                move.limit_speed(cfg['max_z_velocity']*ratio, cfg['max_z_accel']*ratio)
            assert move.axes_r[3]*area <= ext['max_extrude_cross_section'], number
            queue.add_move(move)
            if queue.junction_flush <= 0.:
                process(queue.flush(lazy=True))
        pos = end
    assert not active and backend.segments > 0
    return dict(toolhead_sha256=hashlib.sha256(source).hexdigest(),
                gcode_sha256=hashlib.sha256(Path(gcode_path).read_bytes()).hexdigest(),
                moves=backend.segments, xyz_bounds=bounds, planned_seconds=print_time,
                actual_filament_mm=backend.position, max_average_cross_section_mm2=max_area,
                configured_cross_section_limit_mm2=ext['max_extrude_cross_section'],
                scope='Offline lookahead and extrusion queue requests; no MCU or physical validation')


if __name__ == '__main__':
    print(json.dumps(check(*sys.argv[1:]), indent=2))
