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
from check_controls import PACKAGE, SERIAL, snapshot, adb, device, verify_fixture
from check_workflows import click
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
    'idle-filament-keypad': ('idle','filament-temperature'),
    'idle-details': ('idle','details'), 'printing-camera': ('printing','camera'),
    'complete-home': ('complete','home'),
    'idle-distance': ('idle','distance'), 'idle-xy-speed': ('idle','xy-speed'),
    'idle-z-speed': ('idle','z-speed'), 'idle-e-speed': ('idle','e-speed'),
    'cancelled-home': ('cancelled','home'), 'error-home': ('error','home'),
    'shutdown-home': ('shutdown','home'), 'offline-home': ('offline','home'),
    'unhomed-home': ('unhomed','home'), 'rear-calibration': ('rear','calibration'),
    'idle-nozzle': ('idle','nozzle'), 'idle-bed': ('idle','bed'),
    'idle-fan': ('idle','fan'), 'idle-position': ('idle','position'),
}


def source_fingerprint():
    return {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(Path('pixel-panel/app').rglob('*')) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cases', nargs='+', choices=CASES, default=list(CASES))
    parser.add_argument('--keep-parameters', action='store_true', help='Capture deliberate custom context instead of resetting review parameters')
    args = parser.parse_args()
    verify()
    assert SERIAL and SERIAL.startswith('emulator-'), 'Explicit emulator serial required'
    args.output.mkdir(parents=True, exist_ok=True)
    density = adb('shell','wm','density').strip()
    font_scale = adb('shell','settings','get','system','font_scale').strip()
    assert density == 'Physical density: 420' and font_scale == '1.0', (density,font_scale)
    manifest = {'_device':{'serial':SERIAL,'density':density,'font_scale':font_scale}}
    manifest['_source_commit'] = subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    manifest['_source_sha256'] = source_fingerprint()
    apk = Path('pixel-panel/.build/Voron.apk')
    manifest['_apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
    receipt = apk.parent/'last-build.json'
    if receipt.exists():
        build = json.loads(receipt.read_text(encoding='utf-8'))
        built_source = {'pixel-panel/'+k: v for k, v in build['source_sha256'].items()}
        captured_source = {k.replace('\\', '/'): v for k, v in manifest['_source_sha256'].items()}
        assert built_source == captured_source and build['apk_sha256'] == manifest['_apk_sha256'], 'Build current app source before capture'
        manifest['_build_receipt_verified'] = True
    installed_path = adb('shell', 'pm', 'path', PACKAGE).strip().removeprefix('package:')
    installed_hash = adb('shell', 'sha256sum', installed_path).split()[0]
    assert installed_hash == manifest['_apk_sha256'], 'Install the exact built APK before capturing'
    manifest['_installed_apk_sha256'] = installed_hash
    if not args.keep_parameters:
        device('open', PACKAGE, '--relaunch', '--launch-args=--es', '--launch-args=test_endpoint',
               '--launch-args=http://127.0.0.1:18725')
        manifest['_transient_context'] = 'Reset by safe debug relaunch; saved endpoint unchanged'
    else:
        manifest['_transient_context'] = 'Retained deliberately'
    verify_fixture(18725)
    if not args.keep_parameters:
        # Initialize the paused-job context once, then choose an explicit
        # material target. App preferences and prior review actions must not
        # silently change the visual comparison.
        with urlopen(Request('http://127.0.0.1:18726/select',
                             data=urlencode({'state':'paused','screen':'filament'}).encode()), timeout=90) as r:
            r.read()
        click('PLA 200°'); click('Move')
        for label, choice in [('XY move distance', '10 mm'), ('Z move distance', '0.1 mm'),
                              ('Extrusion distance', '5 mm'), ('XY movement speed', '20 mm/s'),
                              ('Z movement speed', '5 mm/s'), ('E movement speed', '3 mm/s')]:
            click(label); click('Use '+choice)
        click('Home')
        manifest['_initial_parameters'] = {'filament_c':200, 'xy_mm':10, 'z_mm':0.1, 'e_mm':5,
                                            'xy_mm_s':20, 'z_mm_s':5, 'e_mm_s':3,
                                            'set_through_native_controls':True}
    else:
        manifest['_initial_parameters'] = 'Retained deliberately; inspect captured values'
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
    assert source_fingerprint() == manifest['_source_sha256'], 'App source changed during capture'
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == manifest['_apk_sha256'], 'APK changed during capture'
    (args.output/'capture-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')


if __name__ == '__main__': main()
