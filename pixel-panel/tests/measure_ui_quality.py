"""Report native target geometry and rendered foreground centering, never scores.

The raster check estimates visible ink bounds, including the icon and label.
It is diagnostic evidence: it cannot establish subjective visual acceptance.
"""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import numpy as np
from PIL import Image

INK = [(242,243,244),(174,178,185),(196,245,225),(16,17,19),
       (255,149,146),(99,112,107),(116,120,127),(255,231,179)]


def measure(path, density):
    image = np.asarray(Image.open(path.with_suffix('.png')).convert('RGB'),dtype=np.int16)
    root = ET.parse(path).getroot()
    controls = []
    for n in root.iter('node'):
        if n.get('class') != 'android.widget.Button' or n.get('enabled') != 'true': continue
        x,y,r,b = map(int,re.findall(r'\d+',n.get('bounds')))
        if r <= x or b <= y: continue
        control = {'label':n.get('content-desc') or n.get('text'),
                   'bounds':[x,y,r,b], 'size_dp':[(r-x)/density,(b-y)/density]}
        inset = min(round(density*2), (r-x)//4, (b-y)//4)
        crop = image[y+inset:b-inset,x+inset:r-inset]
        if not crop.size: continue
        mask = np.zeros(crop.shape[:2],dtype=bool)
        light_surface = (np.max(np.abs(crop-np.array((196,245,225))),axis=2) <= 3).mean() > .4
        for ink in INK:
            if ink == (16,17,19) and not light_surface:
                continue
            matches = np.max(np.abs(crop-np.array(ink)),axis=2) <= 3
            # Exclude background fills and tiny corner/background fragments.
            if crop.shape[0]*crop.shape[1]*.001 < matches.sum() < crop.shape[0]*crop.shape[1]*.25:
                mask |= matches
        yy,xx = np.where(mask)
        if len(xx):
            control['ink_center_error_dp'] = [
                ((xx.min()+xx.max()+1)/2+inset-(r-x)/2)/density,
                ((yy.min()+yy.max()+1)/2+inset-(b-y)/2)/density]
        controls.append(control)
    vertical = [abs(c['ink_center_error_dp'][1]) for c in controls if 'ink_center_error_dp' in c]
    errors = [abs(c['ink_center_error_dp'][0]) for c in controls if 'ink_center_error_dp' in c]
    return {'controls':controls,'horizontal_center_error_dp':{
        'median':float(np.median(errors)) if errors else None,
        'max':max(errors) if errors else None},
        'vertical_center_error_dp':{'median':float(np.median(vertical)) if vertical else None, 'max':max(vertical) if vertical else None},
        'targets_under_48dp':[c['label'] for c in controls if min(c['size_dp']) < 47.8]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--density',type=float,default=420/160)
    args = parser.parse_args()
    report = {p.stem:measure(p,args.density) for p in args.directory.glob('*.xml')}
    (args.directory/'geometry-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    for name,result in report.items():
        print(name, result['horizontal_center_error_dp'], 'small targets:',result['targets_under_48dp'])


if __name__ == '__main__': main()
