"""Seal UI versions outside .build, with a tracked index and verified hashes."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / 'ui-history'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_index():
    path = HISTORY/'index.json'
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'versions': []}


def seal(identity, label, decision, evidence, historical=False):
    assert re.fullmatch(r'[a-z0-9-]+', identity), 'Use a simple version identifier'
    index = read_index()
    if any(v['id'] == identity for v in index['versions']):
        print('Already sealed:', identity)
        return
    if not historical:
        apk_hash = digest(ROOT/'.build/Voron.apk')
        actual_source = {str(p.relative_to(ROOT.parent)).replace('\\', '/'): digest(p)
                         for p in sorted((ROOT/'app').rglob('*')) if p.is_file()}
        verified = False
        for source in evidence:
            for path in source.rglob('capture-manifest.json') if source.is_dir() else []:
                capture = json.loads(path.read_text(encoding='utf-8'))
                hashes = {k.replace('\\', '/'): v for k, v in capture.get('_source_sha256', {}).items()}
                if capture.get('_apk_sha256') == apk_hash and hashes == actual_source:
                    verified = capture.get('_installed_apk_sha256') == apk_hash
        assert verified, 'Capture the exact installed APK/current source before sealing a new UI'
    target = HISTORY/'versions'/identity
    assert not target.exists(), 'Unindexed directory exists; inspect it instead of overwriting'
    target.mkdir(parents=True)
    for source in evidence:
        source = source.resolve()
        assert source.is_relative_to(ROOT.resolve()) and source.exists(), source
        destination = target/'evidence'/source.name
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
    if not historical:
        shutil.copytree(ROOT/'app', target/'source/app')
        shutil.copy2(ROOT/'.build/Voron.apk', target/'Voron.apk')
        shutil.copytree(ROOT/'evaluation', target/'evaluation')
    files = {str(p.relative_to(target)).replace('\\', '/'): {'sha256': digest(p), 'bytes': p.stat().st_size}
             for p in sorted(target.rglob('*')) if p.is_file()}
    pngs = [p for p in files if p.endswith('.png')]
    apks = [p for p in files if p.endswith('.apk')]
    videos = [p for p in files if p.endswith('.mp4')]
    record = {'id': identity, 'label': label, 'decision': decision,
              'recorded_utc': datetime.now(timezone.utc).isoformat(),
              'recording_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'historical_import': historical, 'files': files,
              'evaluation_note': 'Working-document snapshot; only a version-specific assessment or later named comparison scores this candidate.',
              'screenshots': pngs, 'apks': apks, 'videos': videos,
              'restoration': 'Install the preserved APK for exact executable behavior; inspect saved source/capture manifests for provenance.'
                             if apks else 'No original APK was preserved. Saved captures can be ranked; exact executable restoration is unavailable.'}
    (target/'version.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    index['versions'].append({k: record[k] for k in ('id', 'label', 'decision', 'recorded_utc', 'historical_import', 'restoration')})
    (HISTORY/'index.json').write_text(json.dumps(index, indent=2)+'\n', encoding='utf-8')
    print('Sealed', identity, len(pngs), 'screenshots,', len(apks), 'APKs')


def verify():
    count = 0
    for version in read_index()['versions']:
        folder = HISTORY/'versions'/version['id']
        record = json.loads((folder/'version.json').read_text(encoding='utf-8'))
        actual_files = {str(p.relative_to(folder)).replace('\\', '/') for p in folder.rglob('*')
                        if p.is_file() and p.name != 'version.json'}
        assert actual_files == set(record['files']), 'Unindexed artifact change in ' + version['id']
        for name, expected in record['files'].items():
            path = (folder/name).resolve()
            assert path.is_relative_to(folder.resolve()), name
            assert path.is_file() and digest(path) == expected['sha256'], str(path)
            count += 1
    print('Verified', len(read_index()['versions']), 'sealed versions and', count, 'artifacts')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('verify')
    commands.add_parser('import-history')
    create = commands.add_parser('seal')
    create.add_argument('id')
    create.add_argument('--label', required=True)
    create.add_argument('--decision', required=True)
    create.add_argument('--evidence', type=Path, action='append', default=[])
    args = parser.parse_args()
    if args.command == 'verify':
        verify()
    elif args.command == 'seal':
        seal(args.id, args.label, args.decision, args.evidence)
    else:
        existing = ROOT/'.build/ui-hillclimb'
        for folder in sorted(existing.iterdir()):
            if not folder.is_dir() or folder.name == 'reference':
                continue
            identity = 'historical-' + folder.name
            decision = 'rejected gutter geometry' if folder.name == 'iteration-02' else 'historical evidence; see saved assessment and evaluation ledger'
            seal(identity, folder.name, decision, [folder], historical=True)
        seal('actuation-baseline-038', '0.3.8 actuation baseline', 'Measured before the input fixes',
             [ROOT/'.build/actuation-review/baseline', ROOT/'.build/actuation-review/baseline-source'], historical=True)
        verify()


if __name__ == '__main__':
    main()
