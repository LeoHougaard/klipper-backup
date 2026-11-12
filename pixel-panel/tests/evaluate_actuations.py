"""Score matched actuation traces without turning functional passes into visual scores."""
import argparse
import json
from pathlib import Path


def evaluate(record, budgets, replacement_verified):
    tasks = {r['task']: r for r in record['task_traces']}
    assert record['completed'] and set(tasks) == set(budgets), 'Incomplete task evidence'
    fidelity = [t.get('correct_command', t.get('no_command', False)) for t in tasks.values()]
    fidelity.append(replacement_verified)
    context = [tasks[n]['contextual_units'] for n in ('Nozzle exact target', 'Bed exact target')]
    context += [tasks['Cancel nozzle custom target']['no_command'],
                tasks['Cancel nozzle custom target']['returns_to_heater_context'], replacement_verified]
    interruption = [tasks[n]['no_extrusion'] for n in ('Cold Unload and cancel', 'Cold Load and cancel')]
    interruption += [tasks['Paused filament change and resume']['resumed'],
                     tasks['E-stop from numeric editor']['persistent_stop']]
    return {'intent_fidelity': 10*sum(fidelity)/len(fidelity),
            'input_efficiency': 10*sum(min(1, budgets[n]/tasks[n]['input_count']) for n in budgets)/len(budgets),
            'context_and_editing': 10*sum(context)/len(context),
            'interruption_and_recovery': 10*sum(interruption)/len(interruption),
            'total_inputs': sum(t['input_count'] for t in tasks.values()),
            'critical_known_defects': [] if replacement_verified else ['Selected multi-digit input is overwritten unless Clear is used'],
            'inputs_by_task': {n: tasks[n]['input_count'] for n in budgets}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    contract = json.loads((root/'evaluation/rubric-v2.json').read_text(encoding='utf-8'))
    budgets = {t['task']: t['input_budget'] for t in contract['actuation_method']['tasks']}
    baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
    candidate = json.loads(args.candidate.read_text(encoding='utf-8'))
    assert baseline['baseline_clear_workaround'] and not candidate['baseline_clear_workaround']
    assert candidate['cleanup_completed'], 'Candidate workflow did not cleanly complete'
    # Candidate exact multi-digit E/nozzle/bed tasks deliberately omit Clear.
    for name in ('Exact E distance and speed', 'Nozzle exact target', 'Bed exact target'):
        task = next(t for t in candidate['task_traces'] if t['task'] == name)
        assert task['correct_command'] and not any(i['target'] == 'Clear' for i in task['inputs'])
    result = {'baseline_apk_sha256': baseline['installed_apk_sha256'],
              'candidate_apk_sha256': candidate['installed_apk_sha256'],
              'baseline': evaluate(baseline, budgets, False),
              'candidate': evaluate(candidate, budgets, True),
              'visual_scores_changed_by_this_script': False,
              'scope': 'Frozen label-guided tasks on isolated emulator; not human discovery or physical-printer performance',
              'baseline_note': 'All ten measured tasks completed; post-run Ready wait assumed a reset print state. Raw trace is preserved.',
              'selection_defect_evidence': 'ui-history/versions/actuation-baseline-038/evidence/baseline/selected-input-overwrite.png'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: result[k] for k in ('baseline', 'candidate')}, indent=2))


if __name__ == '__main__':
    main()
