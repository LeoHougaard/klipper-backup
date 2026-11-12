"""Matched native captures for the frozen UI improvement evaluation."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
from check_controls import SERIAL, snapshot, adb
from PIL import Image
from io import BytesIO
from verify_ui_reference import verify

CASES = {
    'idle-home': ('idle','home'), 'heating-home': ('heating','home'),
    'printing-home': ('printing','home'), 'paused-home': ('paused','home'),
    'idle-move': ('idle','move'), 'idle-filament': ('idle','filament'),
    'idle-files': ('idle','files'), 'idle-more': ('idle','more'),
    'idle-connection': ('idle','connection'),
    'idle-temperature-keypad': ('idle','temperature-keypad'),
    'printing-tuning': ('printing','tuning'), 'idle-maintenance': ('idle','maintenance'),
    'idle-calibration': ('idle','calibration'), 'front-calibration': ('front','calibration'),
    'idle-heightmap': ('idle','heightmap'),
    'idle-presets': ('idle','presets'), 'paused-filament': ('paused','filament'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cases', nargs='+', choices=CASES, default=list(CASES))
    args = parser.parse_args()
    verify()
    assert SERIAL and SERIAL.startswith('emulator-'), 'Explicit emulator serial required'
    args.output.mkdir(parents=True, exist_ok=True)
    density = adb('shell','wm','density').strip()
    font_scale = adb('shell','settings','get','system','font_scale').strip()
    assert density == 'Physical density: 420' and font_scale == '1.0', (density,font_scale)
    manifest = {'_device':{'serial':SERIAL,'density':density,'font_scale':font_scale}}
    for name in args.cases:
        state, screen = CASES[name]
        with urlopen(Request('http://127.0.0.1:18726/select',
                             data=urlencode({'state':state,'screen':screen}).encode()), timeout=90) as r:
            page = r.read().decode()
            assert '/view/' in r.url, re.sub('<[^>]*>',' ',page)[-500:]
            image = re.search(r'<img[^>]+src="([^"]+)"',page).group(1)
        with urlopen('http://127.0.0.1:18726'+image, timeout=15) as r: png = r.read()
        assert Image.open(BytesIO(png)).size == (2424,1080), 'Unmatched capture size'
        (args.output/(name+'.png')).write_bytes(png)
        root = snapshot()
        (args.output/(name+'.xml')).write_text(ET.tostring(root,encoding='unicode'),encoding='utf-8')
        manifest[name] = {'state':state,'screen':screen,'sha256':hashlib.sha256(png).hexdigest()}
        print('Captured',name,flush=True)
    manifest['_source_commit'] = subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    manifest['_source_sha256'] = {
        str(p):hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(Path('pixel-panel/app').rglob('*')) if p.is_file()}
    apk = Path('pixel-panel/.build/Voron.apk')
    if apk.exists(): manifest['_apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
    (args.output/'capture-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')


if __name__ == '__main__': main()
