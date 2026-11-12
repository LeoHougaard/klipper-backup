"""Cache primary-source design examples; freeze their hashes on first collection."""
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import hashlib
import json
from PIL import Image

root = Path(__file__).resolve().parents[1]
manifest_path = root / 'evaluation/supplemental-references.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
output = root / '.build/ui-references'
output.mkdir(parents=True, exist_ok=True)


def collect(ref):
    if ref.get('file') and ref.get('sha256'):
        cached = output / ref['file']
        if cached.is_file() and hashlib.sha256(cached.read_bytes()).hexdigest() == ref['sha256']:
            return ref
    request = Request(ref['image'], headers={'User-Agent': 'Mozilla/5.0', 'Referer': ref['source']})
    try:
        with urlopen(request, timeout=30) as response:
            data = response.read()
    except HTTPError as error:
        if error.code != 403:
            raise
        return {**ref, 'available': False, 'reason': 'Image host returned HTTP 403; not used for visual comparison'}
    digest = hashlib.sha256(data).hexdigest()
    if ref.get('sha256') and digest != ref['sha256']:
        raise RuntimeError('Reference changed: ' + ref['id'])
    image = Image.open(BytesIO(data))
    image.load()
    filename = ref['id'] + '.' + image.format.lower().replace('jpeg', 'jpg')
    (output / filename).write_bytes(data)
    return {**ref, 'available': True, 'sha256': digest, 'file': filename, 'dimensions': list(image.size)}


with ThreadPoolExecutor(max_workers=4) as pool:
    collected = list(pool.map(collect, manifest['references']))
manifest['references'] = collected
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
for ref in collected:
    print(ref['id'], ref.get('dimensions'), ref.get('file', ref.get('reason')))
