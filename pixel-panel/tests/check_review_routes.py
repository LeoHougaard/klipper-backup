"""Verify review selection drives real native screens, without printer actions."""
import json
import re
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from check_controls import SERIAL, snapshot


def main():
    assert SERIAL and SERIAL.startswith('emulator-')
    base = 'http://127.0.0.1:18726'
    output = Path('pixel-panel/.build/space-review')
    output.mkdir(exist_ok=True)
    cases = [
        ('printing','home','Pause'), ('paused','home','Resume'),
        ('printing','tuning','Tune this print'),
        ('idle','temperature-keypad','Nozzle target (°C)'),
        ('idle','heightmap','Active heightmap'),
        ('front','calibration','Front paper contact'),
        ('rear','calibration','Rear paper contact'),
        ('idle','files','Search stored prints'),
        ('idle','home','Home axes'),
    ]
    results = []
    for state, screen, expected in cases:
        request = Request(base+'/select', data=urlencode({'state':state,'screen':screen}).encode())
        with urlopen(request, timeout=90) as response:
            page = response.read().decode()
            assert '/view/' in response.url, re.sub('<[^>]*>',' ',page)[-500:]
            image = re.search(r'<img[^>]+src="([^"]+)"',page).group(1)
            with urlopen(base+image) as capture:
                (output/(state+'-'+screen+'.png')).write_bytes(capture.read())
        root = snapshot()
        assert any(expected in (n.get('text',''),n.get('content-desc','')) for n in root.iter('node')), expected
        results.append({'state':state,'screen':screen,'passed':True})
        print('PASS:',state,screen,flush=True)
    with urlopen('http://127.0.0.1:18725/fixture/requests') as response:
        requests = json.load(response)['result']
    assert not any(r['path'].startswith('/printer/') for r in requests), requests
    (output/'review-results.json').write_text(json.dumps({'cases':results,'printer_commands':0},indent=2))


if __name__ == '__main__': main()
