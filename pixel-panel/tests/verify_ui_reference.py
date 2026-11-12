"""Verify immutable UI anchors and fixed reference image hashes. Never rescore."""
import hashlib
import json
from pathlib import Path


def verify():
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root/'evaluation/reference-lock.json').read_text(encoding='utf-8'))
    assert lock['anchor_scores'] == {'starting_build':1,'bambu':8}
    assert len(lock['categories']) == 12
    assert lock['target'] == {'minimum_score':9.8,'minimum_categories':10}
    assert set(lock['bambu_scores']) == set(lock['categories'])
    assert all(score == 8 for score in lock['bambu_scores'].values())
    reference = root/'.build/ui-hillclimb/reference'
    for name, expected in lock['reference_images'].items():
        path = reference/name
        assert path.is_file(), f'Missing frozen reference {path}; restore the locked image, do not substitute a new one'
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'Reference image changed: {name}'
    baseline = root/'.build/ui-hillclimb/baseline'
    for name, expected in lock['baseline_images'].items():
        assert hashlib.sha256((baseline/name).read_bytes()).hexdigest() == expected, f'Starting image changed: {name}'
    print('Fixed Bambu=8, start=1, 12 categories and frozen image hashes verified',flush=True)


if __name__ == '__main__': verify()
