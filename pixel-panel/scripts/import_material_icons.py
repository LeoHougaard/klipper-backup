"""Import pinned Google Material Symbols Rounded as native vector resources."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen
import hashlib
import json
import xml.etree.ElementTree as ET
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path

REVISION = '10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7'
BASE = f'https://raw.githubusercontent.com/google/material-design-icons/{REVISION}/'
ICONS = {
    'home':'home', 'move':'open_with', 'files':'folder', 'camera':'photo_camera',
    'more':'more_horiz', 'heat':'device_thermostat', 'bed':'table_bar',
    'filament':'spoke', 'calibrate':'vertical_align_bottom', 'wifi':'wifi',
    'stop':'report', 'edit':'edit', 'play':'play_arrow', 'pause':'pause',
    'check':'check', 'back':'arrow_back', 'arrow':'chevron_right',
    'refresh':'refresh', 'light':'lightbulb', 'load':'south', 'unload':'north',
    'speed':'speed', 'cool':'ac_unit', 'up':'keyboard_arrow_up',
    'down':'keyboard_arrow_down', 'left':'keyboard_arrow_left', 'right':'keyboard_arrow_right',
    'mesh':'grid_on', 'tools':'build', 'external':'open_in_new',
}
# Solid transport glyphs stay recognizable at the same optical size as text.
FILLED = {'pause', 'play'}


def main():
    root = Path(__file__).resolve().parents[1]
    output = root/'app/res/drawable'
    output.mkdir(exist_ok=True)

    def fetch(item):
        alias, name = item
        variant = '_fill1' if alias in FILLED else ''
        path = f'symbols/web/{name}/materialsymbolsrounded/{name}{variant}_24px.svg'
        with urlopen(BASE+path, timeout=20) as response: svg = response.read()
        source = ET.fromstring(svg)
        x,y,width,height = map(float,source.get('viewBox').split())
        paths = [v.get('d') for v in source.iter('{http://www.w3.org/2000/svg}path') if v.get('fill') != 'none']
        assert paths and width == height
        xml = f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="{width:g}" android:viewportHeight="{height:g}">\n'
        xml += f'    <group android:translateX="{-x:g}" android:translateY="{-y:g}">\n'
        xml += ''.join(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />\n' for path in paths)
        xml += '    </group>\n</vector>\n'
        pen = BoundsPen(None)
        for data in paths: parse_path(data, pen)
        left,top,right,bottom = pen.bounds
        bounds = [(left-x)/width,(top-y)/height,(right-x)/width,(bottom-y)/height]
        return alias, path, svg, xml, bounds

    # Download every asset before changing the source set.
    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(fetch, ICONS.items()))
    with urlopen(BASE+'LICENSE',timeout=20) as response: license_text = response.read()
    notices = root/'third-party/material-symbols'
    notices.mkdir(parents=True,exist_ok=True)
    (notices/'LICENSE').write_bytes(license_text)
    manifest = {'project':'Google Material Symbols Rounded','revision':REVISION,'assets':{}}
    metrics = 'package dev.leo.voronpanel;\n\n// Generated from pinned SVG paths by scripts/import_material_icons.py.\nfinal class IconMetrics {\n  static float[] bounds(String name) {\n    switch (name) {\n'
    for alias,path,svg,xml,bounds in results:
        (output/('panel_'+alias+'.xml')).write_text(xml,encoding='utf-8')
        manifest['assets'][alias] = {'source':BASE+path,'sha256':hashlib.sha256(svg).hexdigest()}
        metrics += f'      case "{alias}": return new float[] {{' + ','.join(f'{v:.6f}f' for v in bounds) + '};\n'
    metrics += '      default: throw new IllegalArgumentException("Missing icon metrics: " + name);\n    }\n  }\n}\n'
    (root/'app/src/IconMetrics.java').write_text(metrics,encoding='utf-8')
    (notices/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(f'Imported {len(results)} pinned Material Symbols Rounded vectors')


if __name__ == '__main__': main()
