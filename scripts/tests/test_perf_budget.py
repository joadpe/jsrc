import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / 'perf_budget.py'
SPEC = importlib.util.spec_from_file_location('perf_budget', MODULE_PATH)
perf = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = perf
SPEC.loader.exec_module(perf)


class CorpusTest(unittest.TestCase):
    def test_generation_is_deterministic_and_contains_cross_package_calls(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            first = perf.generate_corpus(Path(a), 40, 17)
            second = perf.generate_corpus(Path(b), 40, 17)
            self.assertEqual(first['sha256'], second['sha256'])
            self.assertEqual(first['files'], 40)
            self.assertEqual(len(list(Path(a).rglob('*.java'))), 40)
            source = (Path(a) / 'src/main/java/bench/p000/C00020.java').read_text()
            self.assertIn('import bench.p', source)
            self.assertIn('.value()', source)
            self.assertIn('value(int input)', source)

    def test_seed_changes_corpus_and_profile_records_distributions(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            first = perf.generate_corpus(Path(a), 40, 17)
            second = perf.generate_corpus(Path(b), 40, 18)
            self.assertNotEqual(first['sha256'], second['sha256'])
            profile = perf.profile_sources(Path(a))
            self.assertEqual(profile['files'], 40)
            self.assertLess(profile['distributions']['loc']['min'], profile['distributions']['loc']['max'])
            self.assertGreater(profile['distributions']['imports']['max'], 0)

    def test_10k_package_topology_and_file_size_have_a_tail(self):
        self.assertEqual(perf.package_count(10000), 200)
        with tempfile.TemporaryDirectory() as root:
            manifest = perf.generate_corpus(Path(root), 1000, 17)
            self.assertEqual(manifest['parameters']['package_assignment'], 'file_number_mod_20')
        with tempfile.TemporaryDirectory() as root:
            manifest = perf.generate_corpus(Path(root), 5000, 17)
            self.assertEqual(manifest['parameters']['package_assignment'], 'file_number_mod_100')
        with tempfile.TemporaryDirectory() as root:
            perf.generate_corpus(Path(root), 100, 17)
            profile = perf.profile_sources(Path(root))['distributions']
            self.assertGreaterEqual(profile['loc']['p50'], 20)
            self.assertGreaterEqual(profile['loc']['p95'], 60)
            self.assertGreaterEqual(profile['imports']['p95'], 5)
            self.assertEqual(profile['types']['p50'], 1)
            self.assertGreaterEqual(profile['overloads']['max'], 1)
            self.assertLessEqual(profile['call_sites']['p95'], 50)

    def test_profile_includes_nested_modules(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / 'module-a/src/main/java/demo/A.java'
            source.parent.mkdir(parents=True)
            source.write_text('package demo; public class A {}')
            profile = perf.profile_sources(Path(root))
            self.assertEqual(profile['files'], 1)
            self.assertEqual(profile['packages'], 1)

    def test_cross_package_mutation_imports_graph_target(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root)
            perf.generate_corpus(base, 40, 17)
            perf.append_calling_method(base, 40, 1, 'revision', 17)
            source = perf.corpus_path(base, 1, 40).read_text()
            self.assertIn('import bench.p000.C00020;', source)
            self.assertIn('return C00020.value() + 17;', source)

    def test_seeded_edit_selection_spreads_across_packages(self):
        picked = perf.select_changes(1000, 100, 17)
        self.assertEqual(len(picked), 100)
        self.assertEqual(len(set(picked)), 100)
        self.assertGreaterEqual(len({i % 20 for i in picked}), 18)
        self.assertEqual(picked, perf.select_changes(1000, 100, 17))


class GateTest(unittest.TestCase):
    def test_profiles_keep_pr_and_dedicated_sampling_separate(self):
        self.assertEqual(perf.benchmark_plan('smoke')['index_measured'], 1)
        dedicated = perf.benchmark_plan('dedicated')
        self.assertEqual(dedicated['index_preparations'], 2)
        self.assertEqual(dedicated['index_measured'], 7)
        self.assertEqual(dedicated['warm_preparations'], 5)
        self.assertEqual(dedicated['warm_measured'], 50)

    def test_summary_uses_median_and_nearest_rank_p95(self):
        samples = [{'sample': {'wall_ms': n, 'peak_rss_kib': 100 + n, 'peak_disk_bytes': 2000 + n}, 'index_bytes': 1000 + n}
                   for n in range(1, 8)]
        summary = perf.summarize_samples(samples)
        self.assertEqual(summary['median_ms'], 4)
        self.assertEqual(summary['p95_ms'], 7)
        self.assertEqual(summary['peak_rss_kib'], 107)
        self.assertEqual(summary['index_bytes'], 1007)
        self.assertEqual(summary['peak_disk_bytes'], 2007)

    def test_environment_records_explicit_java_runtime(self):
        with tempfile.TemporaryDirectory() as root:
            java = Path(root) / 'java'
            java.write_text('#!/bin/sh\necho custom-jdk-22 >&2\n')
            java.chmod(0o755)
            self.assertEqual(perf.environment(Path(root), [str(java)])['java'], ['custom-jdk-22'])

    def test_environment_comparison_does_not_require_same_commit(self):
        baseline = {'status': 'passed', 'profile': 'dedicated',
                    'environment': {'cpu': 'A'}, 'corpus': {'sha256': 'x'},
                    'commit': 'old', 'summary': {'cold_ms': 100}}
        current = {**baseline, 'commit': 'new', 'summary': {'cold_ms': 110}}
        self.assertFalse(perf.compare_reports(baseline, current, {'cold_ms': 1.2}, True)['failed'])

    def test_report_only_records_regression_without_failing(self):
        baseline = {'cold_ms': 100, 'rss_kib': 1000}
        current = {'cold_ms': 150, 'rss_kib': 1000}
        result = perf.evaluate_budget(baseline, current, {'cold_ms': 1.2, 'rss_kib': 1.1}, False)
        self.assertFalse(result['failed'])
        self.assertEqual(result['breaches'], ['cold_ms'])

    def test_enforced_budget_requires_same_environment(self):
        with self.assertRaisesRegex(ValueError, 'environment'):
            perf.compare_reports({'environment': {'cpu': 'A'}}, {'environment': {'cpu': 'B'}}, {}, True)


    def test_enforced_comparison_rejects_missing_metric_or_failed_report(self):
        baseline = {'status': 'passed', 'profile': 'dedicated',
                    'environment': {'cpu': 'A'}, 'corpus': {'sha256': 'x'},
                    'summary': {'cold.median_ms': 100}}
        current = {**baseline, 'summary': {}}
        with self.assertRaisesRegex(ValueError, 'missing'):
            perf.compare_reports(baseline, current, {'cold.median_ms': 1.2}, True)
        current = {**baseline, 'status': 'failed'}
        with self.assertRaisesRegex(ValueError, 'passed'):
            perf.compare_reports(baseline, current, {'cold.median_ms': 1.2}, True)
        current = {**baseline, 'profile': 'smoke'}
        with self.assertRaisesRegex(ValueError, 'profile'):
            perf.compare_reports(baseline, current, {'cold.median_ms': 1.2}, True)

    def test_compare_cli_enforce_rejects_missing_metric_before_confirming(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            baseline = {'status': 'passed', 'profile': 'dedicated',
                        'environment': {'cpu': 'A'}, 'corpus': {'sha256': 'x'},
                        'summary': {'cold.median_ms': 100}}
            current = {**baseline, 'summary': {}}
            for name, value in (('baseline', baseline), ('current', current),
                                ('thresholds', {'cold.median_ms': 1.2})):
                (root / f'{name}.json').write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'missing'):
                perf.main(['compare', '--baseline', str(root / 'baseline.json'),
                           '--current', str(root / 'current.json'),
                           '--thresholds', str(root / 'thresholds.json'), '--enforce'])


    def test_enforced_confirmation_requires_a_distinct_run(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            report = {'status': 'passed', 'profile': 'dedicated',
                      'environment': {'cpu': 'A'}, 'corpus': {'sha256': 'x'},
                      'summary': {'cold.median_ms': 150}}
            baseline = {**report, 'run_id': 'baseline',
                        'summary': {'cold.median_ms': 100}}
            current = {**report, 'run_id': 'first'}
            for name, value in (('baseline', baseline), ('current', current),
                                ('copy', current), ('thresholds', {'cold.median_ms': 1.2})):
                (root / f'{name}.json').write_text(json.dumps(value))
            args = ['compare', '--baseline', str(root / 'baseline.json'),
                    '--current', str(root / 'current.json'),
                    '--thresholds', str(root / 'thresholds.json'), '--enforce']
            with self.assertRaisesRegex(ValueError, 'independent'):
                perf.main(args + ['--second-report', str(root / 'current.json')])
            with self.assertRaisesRegex(ValueError, 'independent'):
                perf.main(args + ['--second-report', str(root / 'copy.json')])
            (root / 'copy.json').write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'independent'):
                perf.main(args + ['--second-report', str(root / 'copy.json')])
            (root / 'second.json').write_text(json.dumps({**report, 'run_id': 'second'}))
            self.assertEqual(perf.main(args + ['--second-report', str(root / 'second.json')]), 1)


class RunnerTest(unittest.TestCase):
    def test_scenario_rebuilds_reference_and_checks_reindexed_counts(self):
        fake = """import hashlib, json, os, pathlib, sys
args = sys.argv[1:]
root = pathlib.Path(args[args.index('-d') + 1])
command = next(x for x in args if x in ('index', 'mini', 'callers'))
state_path = root / '.jsrc' / 'state.json'
files = sorted((root / 'src/main/java').rglob('*.java'))
current = {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
if command == 'index':
    previous = json.loads(state_path.read_text()) if state_path.exists() else {}
    changed = sum(previous.get(k) != v for k, v in current.items())
    state_path.parent.mkdir(exist_ok=True)
    state_path.write_text(json.dumps(current))
    pathlib.Path(os.environ['JSRC_PERF_TRACE']).write_text(json.dumps({'schema':1,'unit':'ns','durations':{},'counts':{'build.reindexed':changed}}))
    print(f'Done. Indexed {len(current)} files ({changed} re-indexed, {len(current)-changed} cached).', file=sys.stderr)
else:
    snapshot = json.loads(state_path.read_text())
    if command == 'callers':
        callers = [f'{name}:{digest}' for name, digest in sorted(snapshot.items())]
        print(json.dumps({'method': 'bench.C00020.value()', 'total': len(callers), 'callers': callers}))
    else:
        print(json.dumps({'total': len(snapshot), 'digest': hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()}))
"""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'work'
            script = Path(temp) / 'fake.py'
            script.write_text(fake)
            command = [sys.executable, str(script)]
            cold = perf.run_scenario(command, root, 40, 17, 'cold', 10, verify=True)
            self.assertTrue(cold['ok'], cold.get('errors'))
            self.assertEqual(cold['reindexed'], 40)
            unchanged = perf.run_scenario(command, root, 40, 17, 'unchanged', 10, verify=True)
            self.assertTrue(unchanged['ok'], unchanged.get('errors'))
            self.assertEqual(unchanged['reindexed'], 0)
            edited = perf.run_scenario(command, root, 40, 17, 'edit_single', 10, verify=True)
            self.assertTrue(edited['ok'], edited.get('errors'))
            self.assertEqual(edited['reindexed'], 1)
            report = perf.run_benchmark(command, root, 40, 17, 'smoke', 10)
            self.assertEqual(report['status'], 'passed', report['failures'])
            self.assertTrue(report['run_id'])
            self.assertEqual(len(report['scenarios']), 3)
            self.assertIn('warm.graph_frozen.p95_ms', report['summary'])

    def test_phase_trace_must_match_reported_reindexed_count(self):
        with self.assertRaisesRegex(ValueError, 'phase count'):
            perf.validate_index_trace({'counts': {'build.reindexed': 0}}, 1)

    def test_index_result_parser_enforces_reparsed_counts(self):
        result = perf.parse_index_result('Done. Indexed 40 files (0 re-indexed, 40 cached).', 40, 0)
        self.assertEqual(result['cached'], 40)
        with self.assertRaisesRegex(ValueError, 're-indexed'):
            perf.parse_index_result('Done. Indexed 40 files (5 re-indexed, 35 cached).', 40, 0)

    def test_graph_selector_is_fully_qualified_for_corpus_topology(self):
        self.assertEqual(perf.warm_queries(40)['graph'][-1],
                         'bench.p000.C00020.value()')
        self.assertEqual(perf.warm_queries(10000)['graph'][-1],
                         'bench.p020.C00020.value()')

    def test_ambiguous_graph_response_is_not_a_valid_sample(self):
        program = 'import json; print(json.dumps({"ambiguous": True, "candidates": []}))'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            perf.generate_corpus(root, 40, 17)
            with self.assertRaisesRegex(ValueError, 'ambiguous'):
                perf.query_answers([sys.executable, '-c', program], root, 10)

    def test_caller_order_is_not_semantically_significant(self):
        one = {'total': 2, 'callers': ['B.route', 'A.route']}
        two = {'total': 2, 'callers': ['A.route', 'B.route']}
        self.assertEqual(perf.normalize_answer(one), perf.normalize_answer(two))

    def test_snapshot_answer_must_match_complete_generation(self):
        before = {'name': 'Old'}
        after = {'name': 'New'}
        self.assertTrue(perf.answer_matches_generation(before, before, after))
        self.assertTrue(perf.answer_matches_generation(after, before, after))
        self.assertFalse(perf.answer_matches_generation({'name': 'Mixed'}, before, after))

    def test_peak_disk_includes_unpublished_temporary_file(self):
        program = """import pathlib, sys, time
root = pathlib.Path(sys.argv[sys.argv.index('-d') + 1])
index = root / '.jsrc'
index.mkdir()
temporary = index / 'generation.tmp'
temporary.write_bytes(b'x' * 20000)
time.sleep(0.3)
temporary.unlink()
print('{}')
"""
        with tempfile.TemporaryDirectory() as root:
            result = perf.run_cli([sys.executable, '-c', program], Path(root), [], 10)
            self.assertTrue(result['ok'])
            self.assertGreaterEqual(result['peak_disk_bytes'], 20000)
            self.assertEqual(perf.index_size(Path(root)), 0)

    def test_runner_collects_phase_trace_from_child(self):
        program = """import json, os, pathlib
pathlib.Path(os.environ['JSRC_PERF_TRACE']).write_text(json.dumps({
    'schema': 1, 'unit': 'ns', 'durations': {'build.hash_read': 10},
    'counts': {'build.reindexed': 1}}))
print(json.dumps({'ok': True}))
"""
        with tempfile.TemporaryDirectory() as root:
            result = perf.run_cli([sys.executable, '-c', program], Path(root), [], 10)
            self.assertTrue(result['ok'])
            self.assertEqual(result['phase_trace']['counts']['build.reindexed'], 1)

    def test_index_text_and_resource_sample_are_recorded(self):
        with tempfile.TemporaryDirectory() as root:
            result = perf.run_cli([sys.executable, '-c',
                'import sys; print("Done. Indexed 10 files (0 re-indexed, 10 cached).", file=sys.stderr)'],
                Path(root), [], 10, expect_json=False)
            self.assertTrue(result['ok'])
            self.assertIn('Indexed 10 files', result['data'])
            self.assertGreater(result['peak_rss_kib'], 0)

    def test_edit_and_add_delete_are_reproducible(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root)
            perf.generate_corpus(base, 40, 17)
            original = perf.corpus_digest(base)
            perf.apply_mutation(base, 40, 'edit_1pct', 17)
            edited = perf.corpus_path(base, perf.select_changes(40, 1, 17)[0], 40)
            self.assertIn('public int revision() { return C00020.value() + 17; }',
                          edited.read_text())
            self.assertNotEqual(original, perf.corpus_digest(base))
            self.assertEqual(len(list(base.rglob('*.java'))), 40)

    def test_timeout_preserves_resource_sample(self):
        with tempfile.TemporaryDirectory() as root:
            result = perf.run_cli([sys.executable, '-c', 'import time; time.sleep(3)'],
                                  Path(root), [], 0.1)
            self.assertFalse(result['ok'])
            self.assertEqual(result['error'], 'timeout')
            self.assertIn('exit_code', result)
            self.assertIn('peak_rss_kib', result)
            self.assertIn('peak_disk_bytes', result)

    def test_invalid_json_is_unconditional_failure(self):
        with tempfile.TemporaryDirectory() as root:
            result = perf.run_cli([sys.executable, '-c', 'print("not json")'], Path(root), [], 10)
            self.assertFalse(result['ok'])
            self.assertIn('JSON', result['error'])


    def test_concurrent_publication_checks_readers_and_competing_writers(self):
        fake = """import fcntl, json, os, pathlib, sys, time
args = sys.argv[1:]
root = pathlib.Path(args[args.index('-d') + 1])
index = root / '.jsrc'
index.mkdir(exist_ok=True)
state = index / 'generation'
files = list((root / 'src/main/java').rglob('*.java'))
changed = any('revision()' in p.read_text() for p in files)
competing = any('competingRevision()' in p.read_text() for p in files)
interrupted = any('interruptedRevision()' in p.read_text() for p in files)
if 'index' in args:
    with (index / 'index.lock').open('w') as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX)
        previous = state.read_text() if state.exists() else ''
        generation = 'interrupted' if interrupted else ('competing' if competing else ('new' if changed else 'old'))
        time.sleep(1.0 if changed else 0.01)
        if interrupted:
            skipped = False
            count = len(files) if not previous else (1 if previous != generation else 0)
        elif competing:
            marker = index / 'competing-committed'
            attempts = index / 'competing-attempts'
            attempt = int(attempts.read_text()) + 1 if attempts.exists() else 1
            attempts.write_text(str(attempt))
            skipped = os.environ.get('FAKE_SKIP_COMPETING') and previous and attempt <= 2
            count = len(files) if not previous else (0 if skipped or marker.exists() else 1)
            if not skipped:
                marker.write_text('yes')
        else:
            skipped = False
            count = len(files) if not previous else (1 if previous != generation else 0)
        if not skipped:
            state.write_text(generation)
    pathlib.Path(os.environ['JSRC_PERF_TRACE']).write_text(json.dumps({
        'schema': 1, 'unit': 'ns', 'durations': {'writer.lock_wait': 1, 'writer.lock_hold': 1},
        'counts': {'build.reindexed': count}}))
    print(f'Indexed {len(files)} files ({count} re-indexed, {len(files)-count} cached)', file=sys.stderr)
else:
    generation = state.read_text()
    if os.environ.get('FAKE_MIXED') and changed and generation == 'old':
        generation = 'mixed'
    if 'callers' in args:
        print(json.dumps({'total': 1, 'callers': [generation]}))
    else:
        print(json.dumps({'generation': generation}))
"""
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            command = [sys.executable, str(base / 'fake.py')]
            (base / 'fake.py').write_text(fake)
            good = perf.run_concurrent_publication(command, base / 'good', 40, 17, 10)
            self.assertTrue(good['ok'], good.get('errors'))
            self.assertEqual(good['reader_count'], 4)
            self.assertEqual(len(good['writers']), 2)
            self.assertGreater(good.get('reader_completed_under_lock', 0), 0)
            self.assertTrue(good.get('writer_paused_under_lock', False))
            self.assertEqual(len(good.get('writer_waiters_observed', [])), 2)
            self.assertEqual(good.get('reference_reindexed'), 40)
            recovered = perf.run_interrupted_publication(command, base / 'good', 40, 17, 10)
            self.assertTrue(recovered['ok'], recovered.get('errors'))
            self.assertTrue(recovered.get('killed_under_lock'))
            from unittest.mock import patch
            with patch.dict('os.environ', {'FAKE_MIXED': '1'}):
                bad = perf.run_concurrent_publication(command, base / 'bad', 40, 17, 10)
            self.assertFalse(bad['ok'])
            self.assertIn('mixed', str(bad['errors']).lower())
            with patch.dict('os.environ', {'FAKE_SKIP_COMPETING': '1'}):
                skipped = perf.run_concurrent_publication(command, base / 'skipped', 40, 17, 10)
            self.assertFalse(skipped['ok'])
            self.assertTrue(any(word in str(skipped['errors']).lower()
                                for word in ('re-indexed', 'final snapshot', 'neither competing writer')), skipped['errors'])


if __name__ == '__main__':
    unittest.main()
