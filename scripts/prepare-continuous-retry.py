"""Combine sub-5-micron continuous moves, retaining endpoints and nominal E.

Usage: python prepare-continuous-retry.py original.gcode retry.gcode
The original is preserved. Run check-continuous-path.py on the output.
"""
import json
import math
from pathlib import Path
import re
import sys


def prepare(source, destination):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError('Use a new output filename')
    output = []
    pending = []
    length = 0.
    rate = 0.
    combined = 0
    def flush():
        nonlocal length, combined
        if not pending:
            return
        values = {k: sum(move[k] for move in pending) for k in 'XYZE'}
        distance = math.sqrt(sum(values[k]**2 for k in 'XYZ'))
        if distance <= 1e-9:
            raise ValueError('Degenerate combined motion requires reslicing')
        speed = rate*60.*distance/values['E']
        output.append('G1 ' + ' '.join(f'{k}{values[k]:.12f}' for k in 'XYZE') + f' F{speed:.9f}')
        combined += len(pending)-1
        pending.clear()
        length = 0.
    for line in source.read_text().splitlines():
        code = line.split(';', 1)[0].strip()
        if code.startswith('SET_CONTINUOUS_EXTRUSION '):
            flush()
            rate = float(code.split('RATE=')[1])
        if rate and code.startswith('G1 '):
            values = {k: float(v) for k, v in re.findall(r'([XYZEF])([-+0-9.e]+)', code)}
            assert set(values) == set('XYZEF')
            distance = math.sqrt(sum(values[k]**2 for k in 'XYZ'))
            if pending or distance < .005:
                pending.append(values)
                length += distance
                if length >= .005:
                    flush()
                continue
        # Layer/progress annotations do not interrupt continuous motion.
        if pending and code and not code.startswith('M73 '):
            flush()
        output.append(line)
    flush()
    destination.write_text('\n'.join(output)+'\n', encoding='utf-8')
    return dict(combined_moves=combined, minimum_path_mm=.005, output=str(destination))


if __name__ == '__main__':
    print(json.dumps(prepare(*sys.argv[1:]), indent=2))
