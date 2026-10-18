#!/usr/bin/env python3
"""Validate the frozen audit and, optionally, current Surefire evidence; render matrix.md."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
VALUES = {'PASS_VERIFIED': 1, 'PARTIAL': .5, 'FAIL': 0, 'NOT_IMPLEMENTED': 0}
STATUSES = set(VALUES) | {'NOT_TESTABLE', 'NOT_APPLICABLE'}


def metrics(rows):
    applicable = [r for r in rows if r['status'] != 'NOT_APPLICABLE']
    known = [r for r in applicable if r['status'] != 'NOT_TESTABLE']
    tested = [r for r in known if r['tests']]
    n = len(applicable)
    verified = sum(r['status'] == 'PASS_VERIFIED' for r in applicable)
    return dict(applicable=n, assessed=len(known), behaviorally_tested=len(tested),
                fully_verified=verified, verified_coverage_pct=round(100 * verified / n, 2),
                behavioral_coverage_pct=round(100 * len(tested) / n, 2),
                present_pct=round(100 * sum(r['status'] in ('PASS_VERIFIED', 'PARTIAL', 'FAIL') for r in known) / len(known), 2),
                parity_assessed_pct=round(100 * sum(VALUES[r['status']] for r in known) / len(known), 2),
                parity_tested_pct=round(100 * sum(VALUES[r['status']] for r in tested) / len(tested), 2) if tested else None,
                not_testable_pct=round(100 * sum(r['status'] == 'NOT_TESTABLE' for r in applicable) / n, 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true', help='Regenerate matrix.md from report.json')
    parser.add_argument('--surefire', type=Path, help='Verify cited test methods against current XML reports')
    args = parser.parse_args()
    report = json.loads((HERE / 'report.json').read_text())
    rows = report['features']
    expected = {f'{p}-{i:03}' for p, count in [('AGT', 7), ('SUB', 9), ('SES', 10), ('TOO', 7), ('PER', 5), ('MCP', 10), ('ORC', 5)] for i in range(1, count + 1)}
    assert len(rows) == len(expected) and {r['id'] for r in rows} == expected
    tests = {t['id']: t for t in report['tests']}
    for row in rows:
        assert row['status'] in STATUSES
        assert row['observed'] and row['expected'] and row['evidence']
        if row['status'] == 'PASS_VERIFIED':
            assert row['tests'], row['id']
        if row['status'] not in ('PASS_VERIFIED', 'NOT_APPLICABLE'):
            assert row['gap_id'] and row['severity']
        for evidence in row['evidence']:
            lines = (ROOT / evidence['path']).read_text().splitlines()
            assert 1 <= evidence['line'] <= len(lines)
        for test in row['tests']:
            assert tests[test]['outcome'] == 'passed'
    for source in json.loads((HERE / 'source-manifest.json').read_text()):
        assert hashlib.sha256((ROOT / source['path']).read_bytes()).hexdigest() == source['sha256'], f"Audited source changed: {source['path']}"
    for domain in report['domains']:
        assert domain['metrics'] == metrics([r for r in rows if r['domain'] == domain['id']])
    for key, value in metrics(rows).items():
        assert report['coverage'][key] == value, key
    weighted = round(sum(d['weight_pct'] * d['metrics']['parity_assessed_pct'] / 100 for d in report['domains']), 2)
    assert report['coverage']['weighted_parity_assessed_pct'] == weighted
    assert sum(d['weight_pct'] for d in report['domains']) == 100
    if args.surefire:
        current = {}
        total = Counter()
        for file in args.surefire.glob('TEST-*.xml'):
            suite = ET.parse(file).getroot()
            total.update({k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')})
            for case in suite.findall('testcase'):
                key = case.get('classname').split('.')[-1] + '#' + case.get('name')
                current[key] = all(case.find(tag) is None for tag in ('failure', 'error', 'skipped'))
        for test in tests:
            assert current.get(test), f'Missing or failed test: {test}'
        assert dict(total) == report['validation']['suite'], dict(total)
    if args.render:
        output = ['# Matrice de parité fonctionnelle', '',
                  'Référence : [baseline.md](baseline.md). Verdict : **pas de parité fonctionnelle**. Les 53 lignes représentent des exigences composites ; un test positif sur une partie ne suffit pas à valider toute la ligne.', '',
                  'Un test de caractérisation qui réussit en reproduisant un défaut laisse la fonctionnalité en **FAIL**. Les absences MCP sont confirmées par inspection du catalogue, du dispatch, du bootstrap et du build, sans test de protocole inventé.', '',
                  '| ID | Fonctionnalité | OpenCode | Syleks | Statut | Preuve | Test |',
                  '|---|---|---|---|---|---|---|']
        for r in rows:
            ev = '<br>'.join(f"[{e['path'].split('/')[-1]}:{e['line']}](../../../{e['path']}#L{e['line']})" for e in r['evidence'])
            tc = '<br>'.join('`' + t + '` : PASS' for t in r['tests']) or 'Inspection statique ; aucun test comportemental'
            cells = [r['id'], r['name'], f"[{r['expected']}]({r['reference']['url']})", r['observed'], '**' + r['status'] + '**', ev, tc]
            output.append('| ' + ' | '.join(c.replace('|', '\\|').replace('\n', ' ') for c in cells) + ' |')
        output += ['', '## Scores et couverture', '',
                   'Poids demandés conservés ; PER et ORC regroupés (10 lignes) pour éviter le double comptage des permissions. Providers et E2E sont des coupes transverses, non des points supplémentaires.', '',
                   '| Domaine | Poids | PASS complets / exigences | Couverture comportementale | Présence | Score audité | Score sur lignes testées | Non testable |',
                   '|---|---:|---:|---:|---:|---:|---:|---:|']
        for d in report['domains']:
            m = d['metrics']; tested = '—' if m['parity_tested_pct'] is None else str(m['parity_tested_pct']) + ' %'
            output.append(f"| {d['name']} | {d['weight_pct']} % | {m['fully_verified']}/{m['applicable']} | {m['behavioral_coverage_pct']} % | {m['present_pct']} % | {m['parity_assessed_pct']} % | {tested} | {m['not_testable_pct']} % |")
        m = report['coverage']
        output += ['', f"Score pondéré sur le périmètre **audité** : **{weighted} %**. Couverture intégralement vérifiée : **{m['fully_verified']}/{m['applicable']} = {m['verified_coverage_pct']} %** ; sondes comportementales : **{m['behaviorally_tested']}/{m['applicable']} = {m['behavioral_coverage_pct']} %**. Présence : **{m['present_pct']} %** (un FAIL compte comme présent, pas comme conforme).", '',
                   f"Score non pondéré sur les seules lignes ayant des tests exécutés : **{m['parity_tested_pct']} %** ({m['behaviorally_tested']} lignes). Aucun score pondéré global testé n’est calculé : MCP n’a aucune ligne testée et représente 20 % du poids. Réattribuer ce poids masquerait son absence.", '',
                   'Formules : PASS_VERIFIED=1, PARTIAL=0,5, FAIL/NOT_IMPLEMENTED=0. Score audité = somme / lignes auditées connues. Score testé = même calcul limité aux lignes avec tests réussis cités (y compris les tests de défaut). Couverture vérifiée = PASS complets / lignes applicables ; couverture comportementale = lignes avec au moins une sonde exécutée / lignes applicables. Présence = (PASS+PARTIAL+FAIL) / lignes connues.', '',
                   'Aucun NOT_APPLICABLE ni NOT_TESTABLE dans les 53 exigences : les absences d’implémentation sont connues et ne deviennent pas des inconnues. Cela ne signifie pas que toutes les variantes ont été testées. Modèles réels, abonnement ChatGPT, terminal humain et scénarios MCP restent non exécutés ; voir [test-plan.md](test-plan.md) et [validation.md](validation.md). Les inconnues de capacités réelles des modèles sont séparées dans la matrice providers, hors score.', '',
                   'Le score mesure ces exigences composites, pas une couverture de code ni une probabilité de sécurité. Un résultat 50 % PARTIAL est une convention comptable, pas une mesure fine des sous-capacités.', '']
        (HERE / 'matrix.md').write_text('\n'.join(output))
    print(f"Audit consistent: {len(rows)} features, {len(tests)} cited tests, weighted assessed parity {weighted}%")


if __name__ == '__main__':
    main()
