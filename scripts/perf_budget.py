#!/usr/bin/env python3
"""Deterministic offline corpus and report-only performance measurements for jsrc."""

import argparse
import fcntl
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import os
import platform
import random
import signal
import shutil
import tempfile
import threading
import statistics
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

def package_count(count):
    return min(200, max(20, count // 50))


def corpus_path(root, number, count):
    packages = package_count(count)
    return root / 'src/main/java/bench' / f'p{number % packages:03d}' / f'C{number:05d}.java'


def source_text(number, count, seed):
    rng = random.Random((seed << 32) ^ number)
    packages = package_count(count)
    package = f'bench.p{number % packages:03d}'
    offsets = [1, 7] + rng.sample(range(8, min(count, 40)), k=min(2, max(0, count - 8)))
    targets = sorted({(number - offset) % count for offset in offsets})
    imports = {f'bench.p{i % packages:03d}.C{i:05d}' for i in targets
               if i % packages != number % packages}
    if number % 5 == 0:
        imports.update(('java.util.List', 'java.util.Map', 'java.util.Set', 'java.util.Optional'))
    calls = ' + '.join(f'C{i:05d}.value()' for i in targets)
    lines = [f'package {package};', '']
    lines += [f'import {name};' for name in sorted(imports)]
    if number % 2 == 0:
        lines += ['', '@SuppressWarnings("unused")']
    else:
        lines.append('')
    lines += [f'public class C{number:05d} {{',
              f'    public static int value() {{ return {number}; }}',
              f'    public int route() {{ return {calls}; }}']
    extra_methods = rng.choices([0, 1, 2, 3, 8, 12], weights=[15, 20, 25, 20, 15, 5])[0]
    for method in range(extra_methods):
        if extra_methods >= 8 and method % 2 == 0:
            lines.append('    @Deprecated')
        lines += [f'    public int metric{method}(int input) {{', '        int result = input;']
        body_lines = rng.randint(6, 12) if extra_methods >= 8 else rng.randint(2, 6)
        for step in range(body_lines):
            update = f'Math.abs({step} + {number})' if step % 3 == 0 else f'{step} + {number}'
            lines.append(f'        result += {update};')
        lines += ['        return result + route();', '    }']
    if number % 10 == 0:
        lines.append('    public static int value(int input) { return input + value(); }')
    if number % 20 == 0:
        lines.append('    @Deprecated public int legacy() { return route(); }')
    lines += ['}', '']
    return '\n'.join(lines)


def corpus_digest(root):
    digest = hashlib.sha256()
    files = sorted((root / 'src/main/java').rglob('*.java'))
    for path in files:
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def generate_corpus(root, count, seed):
    if count < 8:
        raise ValueError('corpus requires at least 8 files')
    root = root.resolve()
    source_root = root / 'src/main/java'
    if source_root.exists() and any(source_root.rglob('*.java')):
        raise ValueError('corpus target already contains Java files')
    for number in range(count):
        path = corpus_path(root, number, count)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source_text(number, count, seed), encoding='utf-8')
    # Explicit Java 17 project declaration avoids tool-specific default source levels.
    (root / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion>'
                                  '<groupId>bench</groupId><artifactId>corpus</artifactId>'
                                  '<version>1</version><properties>'
                                  '<maven.compiler.release>17</maven.compiler.release>'
                                  '</properties></project>\n', encoding='utf-8')
    manifest = {'schema': 1, 'files': count, 'seed': seed, 'source_level': 17,
                'packages': package_count(count), 'sha256': corpus_digest(root),
                'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'parameters': {'call_edges_per_file': [2, 4], 'extra_methods_per_file': [0, 12],
                               'overload_every': 10, 'annotation_every': 20,
                               'package_assignment': f'file_number_mod_{package_count(count)}'}}
    (root / 'corpus-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def select_changes(count, changed, seed):
    if changed < 0 or changed > count:
        raise ValueError('invalid change count')
    rng = random.Random(seed)
    buckets = [[] for _ in range(package_count(count))]
    for number in range(count):
        buckets[number % package_count(count)].append(number)
    for bucket in buckets:
        rng.shuffle(bucket)
    result = []
    while len(result) < changed:
        for bucket in buckets:
            if bucket and len(result) < changed:
                result.append(bucket.pop())
    return sorted(result)


def distribution(values):
    ordered = sorted(values)
    def percentile(p):
        return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * p))]
    return {'min': ordered[0], 'p50': percentile(0.50), 'p95': percentile(0.95),
            'max': ordered[-1]}


def profile_sources(root):
    paths = sorted(path for path in root.rglob('*.java')
                   if any(path.parts[i:i + 3] == ('src', 'main', 'java')
                          for i in range(len(path.parts) - 2))
                   and not any(part in ('target', 'build', '.gradle', '.jsrc')
                               for part in path.relative_to(root).parts))
    if not paths:
        raise ValueError('no Java files under any src/main/java')
    observations = {'bytes': [], 'loc': [], 'imports': [], 'methods': [],
                    'annotations': [], 'call_sites': [], 'types': [], 'overloads': []}
    packages = set()
    for path in paths:
        raw = path.read_bytes()
        source = raw.decode('utf-8', errors='replace')
        lines = source.splitlines()
        declaration = re.search(r'\bpackage\s+([\w.]+)\s*;', source)
        packages.add(declaration.group(1) if declaration else '<default>')
        observations['bytes'].append(len(raw))
        observations['loc'].append(sum(bool(line.strip()) for line in lines))
        observations['imports'].append(sum(line.lstrip().startswith('import ') for line in lines))
        observations['methods'].append(len(re.findall(r'\b(?:public|protected|private)\s+[^;{}]+?\(', source)))
        observations['annotations'].append(len(re.findall(r'(?m)^\s*@\w+', source)))
        observations['call_sites'].append(len(re.findall(r'\.\w+\s*\(', source)))
        observations['types'].append(len(re.findall(r'\b(?:class|interface|enum|record)\s+\w+', source)))
        method_names = re.findall(r'\b(?:public|protected|private)\s+(?:static\s+)?'
                                  r'(?:[\w<>?,\[\].]+\s+)+(\w+)\s*\(', source)
        observations['overloads'].append(len(method_names) - len(set(method_names)))
    return {'files': len(paths), 'packages': len(packages), 'distributions':
            {name: distribution(values) for name, values in observations.items()}}


def parse_index_result(output, total, changed):
    match = re.search(r'Indexed (\d+) files? \((\d+) re-indexed, (\d+) cached\)', output)
    if not match:
        raise ValueError('index output lacks file/re-indexed counts')
    files, reparsed, cached = map(int, match.groups())
    if files != total or reparsed != changed or cached != total - changed:
        raise ValueError(f'unexpected re-indexed/cached counts: {match.group(0)}')
    return {'files': files, 'reindexed': reparsed, 'cached': cached}


def validate_index_trace(trace, expected):
    if trace.get('counts', {}).get('build.reindexed') != expected:
        raise ValueError('phase count for reindexed files does not match scenario')


def answer_matches_generation(observed, before, after):
    return observed == before or observed == after


def append_calling_method(root, count, number, method, seed):
    path = corpus_path(root, number, count)
    source = path.read_text(encoding='utf-8')
    target_package = 20 % package_count(count)
    source_package = number % package_count(count)
    imported = f'import bench.p{target_package:03d}.C00020;'
    if source_package != target_package and imported not in source:
        declaration = f'package bench.p{source_package:03d};'
        source = source.replace(declaration, declaration + '\n' + imported, 1)
    at = source.rfind('}')
    source = (source[:at] +
              f'    public int {method}() {{ return C00020.value() + {seed}; }}\n' +
              source[at:])
    path.write_text(source, encoding='utf-8')


def apply_mutation(root, count, scenario, seed):
    if scenario == 'edit_single':
        selected = select_changes(count, 1, seed)
    elif scenario == 'edit_1pct':
        selected = select_changes(count, max(1, count // 100), seed)
    elif scenario == 'edit_10pct':
        selected = select_changes(count, max(1, count // 10), seed)
    elif scenario == 'add_delete_1pct':
        selected = select_changes(count, max(1, count // 100), seed)
        for number in selected:
            corpus_path(root, number, count).unlink()
        for offset in range(len(selected)):
            path = corpus_path(root, count + offset, count)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source_text(count + offset, count + len(selected), seed), encoding='utf-8')
        return {'reindexed': len(selected), 'added': len(selected), 'deleted': len(selected)}
    else:
        raise ValueError(f'unknown mutation scenario: {scenario}')
    for number in selected:
        append_calling_method(root, count, number, 'revision', seed)
    return {'reindexed': len(selected), 'edited': selected}


def run_cli(command, root, args, timeout, expect_json=True, on_start=None):
    with tempfile.NamedTemporaryFile(prefix='jsrc-perf-rss-', delete=False) as sample:
        rss_path = Path(sample.name)
    trace_path = rss_path.with_suffix('.phases.json')
    start = time.monotonic_ns()
    command_line = ['/usr/bin/time', '-f', '%M', '-o', str(rss_path),
                    *command, '-d', str(root.resolve()), *args]
    if expect_json:
        command_line.append('--json')
    proc = subprocess.Popen(command_line, cwd=root, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, start_new_session=True,
                            env={**os.environ, 'JSRC_PERF_TRACE': str(trace_path)})
    if on_start is not None:
        on_start(proc.pid)
    disk_peak = [index_size(root)]
    monitor_done = threading.Event()

    def monitor_disk():
        while not monitor_done.wait(0.05):
            disk_peak[0] = max(disk_peak[0], index_size(root))

    monitor = threading.Thread(target=monitor_disk, daemon=True)
    monitor.start()
    timed_out = False
    try:
        stdout, stderr = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(proc.pid, signal.SIGKILL)
        stdout, stderr = proc.communicate()
    finally:
        monitor_done.set()
        monitor.join()
        disk_peak[0] = max(disk_peak[0], index_size(root))
        rss_text = rss_path.read_text().strip() if rss_path.exists() else ''
        rss_path.unlink(missing_ok=True)
        trace = json.loads(trace_path.read_text()) if trace_path.exists() else None
        trace_path.unlink(missing_ok=True)
    result = {'ok': proc.returncode == 0, 'exit_code': proc.returncode,
              'wall_ms': (time.monotonic_ns() - start) / 1e6,
              'peak_rss_kib': int(rss_text) if rss_text.isdigit() else None,
              'peak_disk_bytes': disk_peak[0]}
    if trace is not None:
        result['phase_trace'] = trace
    if timed_out:
        result.update(ok=False, error='timeout')
        return result
    if proc.returncode:
        result['error'] = stderr[-2000:] or stdout[-2000:]
        return result
    if not expect_json:
        result['data'] = stdout + stderr
        return result
    try:
        result['data'] = json.loads(stdout)
    except json.JSONDecodeError:
        result.update(ok=False, error='Invalid JSON output')
    return result


def normalize_answer(value):
    if isinstance(value, dict):
        return {key: sorted(normalize_answer(item)) if key == 'callers' and isinstance(item, list)
                else normalize_answer(item) for key, item in value.items()
                if key not in ('nextCommands', '_budget', 'metrics')}
    if isinstance(value, list):
        return [normalize_answer(item) for item in value]
    return value


def query_answers(command, root, timeout, changed_class=None):
    count = json.loads((root / 'corpus-manifest.json').read_text())['files']
    targets = list(warm_queries(count).items())
    if changed_class is not None and changed_class != 20:
        targets.append(('edited_symbol', ['mini', f'C{changed_class:05d}']))
    answers = {}
    for name, args in targets:
        normal = run_cli(command, root, args, timeout)
        frozen = run_cli(command, root, ['--frozen-index', *args], timeout)
        if not normal['ok'] or not frozen['ok']:
            raise ValueError(f'{name} query failed: {normal.get("error") or frozen.get("error")}')
        if name == 'graph':
            validate_graph_answer(normal['data'])
            validate_graph_answer(frozen['data'])
        answers[name] = normalize_answer(normal['data'])
        if answers[name] != normalize_answer(frozen['data']):
            raise ValueError(f'{name} differs between normal and frozen index')
    return answers


def index_size(root):
    directory = root / '.jsrc'
    if not directory.exists():
        return 0
    size = 0
    for path in directory.rglob('*'):
        try:
            if path.is_file():
                size += path.stat().st_size
        except FileNotFoundError:
            continue
    return size


def run_scenario(command, root, count, seed, scenario, timeout, verify=False,
                 require_trace=False):
    root = root.resolve()
    if root.exists():
        if not (root / 'corpus-manifest.json').exists():
            raise ValueError('refusing to replace a directory without corpus-manifest.json')
        shutil.rmtree(root)
    manifest = generate_corpus(root, count, seed)
    expected = count
    mutation = {}
    if scenario != 'cold':
        prepared = run_cli(command, root, ['index'], timeout, expect_json=False)
        if not prepared['ok']:
            return {'ok': False, 'errors': [f'preparation index: {prepared.get("error")}']}
        parse_index_result(prepared['data'], count, count)
        expected = 0
    if scenario not in ('cold', 'unchanged'):
        mutation = apply_mutation(root, count, scenario, seed)
        expected = mutation['reindexed']
    sample = run_cli(command, root, ['index'], timeout, expect_json=False)
    if not sample['ok']:
        return {'ok': False, 'errors': [f'index: {sample.get("error")}'], 'sample': sample}
    try:
        counts = parse_index_result(sample['data'], count, expected)
        trace = sample.get('phase_trace')
        if require_trace and trace is None:
            raise ValueError('index phase trace missing')
        if trace is not None:
            validate_index_trace(trace, expected)
        edited = mutation.get('edited', [])
        answers = query_answers(command, root, timeout, edited[0] if edited else None)
        bytes_on_disk = index_size(root)
        if bytes_on_disk <= 0:
            raise ValueError('index is missing on disk')
        if verify:
            shutil.rmtree(root / '.jsrc')
            clean = run_cli(command, root, ['index'], timeout, expect_json=False)
            if not clean['ok']:
                raise ValueError(f'clean rebuild failed: {clean.get("error")}')
            parse_index_result(clean['data'], count, count)
            reference = query_answers(command, root, timeout, edited[0] if edited else None)
            if answers != reference:
                raise ValueError('indexed query outputs differ from clean rebuild')
        return {'ok': True, 'scenario': scenario, 'reindexed': counts['reindexed'],
                'corpus_sha256': manifest['sha256'], 'mutated_sha256': corpus_digest(root),
                'index_bytes': bytes_on_disk,
                'sample': {key: value for key, value in sample.items() if key != 'data'},
                'answers_sha256': hashlib.sha256(json.dumps(answers, sort_keys=True).encode()).hexdigest()}
    except ValueError as ex:
        return {'ok': False, 'scenario': scenario, 'errors': [str(ex)],
                'sample': {key: value for key, value in sample.items() if key != 'data'}}


def summarize_samples(samples):
    if not samples:
        raise ValueError('no measured samples')
    durations = sorted(item['sample']['wall_ms'] for item in samples)
    rss = [item['sample'].get('peak_rss_kib') or 0 for item in samples]
    return {'median_ms': statistics.median(durations),
            'p95_ms': durations[math.ceil(0.95 * len(durations)) - 1],
            'peak_rss_kib': max(rss),
            'index_bytes': max(item.get('index_bytes', 0) for item in samples),
            'peak_disk_bytes': max(item['sample'].get('peak_disk_bytes') or 0 for item in samples)}


def evaluate_budget(baseline, current, factors, enforce):
    breaches = [key for key, factor in factors.items()
                if key in baseline and key in current and current[key] > baseline[key] * factor]
    return {'breaches': breaches, 'failed': bool(breaches and enforce)}


def compare_reports(baseline, current, factors, enforce):
    if baseline.get('environment') != current.get('environment'):
        raise ValueError('environment mismatch; budgets require the same runner')
    if baseline.get('corpus') != current.get('corpus'):
        raise ValueError('corpus mismatch')
    if baseline.get('profile') != current.get('profile'):
        raise ValueError('profile mismatch')
    if enforce:
        if baseline.get('status') != 'passed' or current.get('status') != 'passed':
            raise ValueError('enforced comparison requires passed reports')
        if not factors:
            raise ValueError('enforced comparison requires thresholds')
        missing = [key for key in factors if key not in baseline.get('summary', {})
                   or key not in current.get('summary', {})]
        if missing:
            raise ValueError(f'missing budget metrics: {missing}')
        for key, factor in factors.items():
            previous = baseline['summary'][key]
            observed = current['summary'][key]
            if any(isinstance(value, bool) or not isinstance(value, (int, float))
                   or not math.isfinite(value) for value in (previous, observed, factor)) \
                    or previous <= 0 or observed < 0 or factor <= 0:
                raise ValueError(f'invalid budget metric or factor: {key}')
    return evaluate_budget(baseline.get('summary', {}), current.get('summary', {}), factors, enforce)


SCENARIOS = ('cold', 'unchanged', 'edit_single', 'edit_1pct', 'edit_10pct',
             'add_delete_1pct')
def warm_queries(count):
    return {'symbol': ['mini', 'C00020'],
            'graph': ['--limit=10000', 'callers',
                      f'bench.p{20 % package_count(count):03d}.C00020.value()']}


def validate_graph_answer(answer):
    if not isinstance(answer, dict) or answer.get('ambiguous'):
        raise ValueError('ambiguous graph answer')
    if not isinstance(answer.get('callers'), list) or not isinstance(answer.get('total'), int) \
            or answer['total'] != len(answer['callers']):
        raise ValueError('incomplete graph answer')


def benchmark_plan(profile):
    if profile == 'smoke':
        return {'index_preparations': 0, 'index_measured': 1,
                'warm_preparations': 0, 'warm_measured': 1}
    if profile == 'dedicated':
        return {'index_preparations': 2, 'index_measured': 7,
                'warm_preparations': 5, 'warm_measured': 50}
    raise ValueError(f'unknown profile: {profile}')


def run_warm(command, root, count, seed, plan, timeout):
    prepared = run_scenario(command, root, count, seed, 'cold', timeout)
    if not prepared['ok']:
        return {'ok': False, 'errors': prepared.get('errors', ['warm preparation failed'])}
    results = {}
    for name, args in warm_queries(count).items():
        normal_reference = run_cli(command, root, args, timeout)
        if not normal_reference['ok']:
            return {'ok': False, 'errors': [f'warm {name}: {normal_reference.get("error")}']}
        if name == 'graph':
            validate_graph_answer(normal_reference['data'])
        reference = normalize_answer(normal_reference['data'])
        for mode in ('normal', 'frozen'):
            invocation = args if mode == 'normal' else ['--frozen-index', *args]
            for _ in range(plan['warm_preparations']):
                result = run_cli(command, root, invocation, timeout)
                if not result['ok'] or normalize_answer(result.get('data')) != reference:
                    return {'ok': False, 'errors': [f'warm {name}/{mode} preparation differs']}
                if name == 'graph':
                    validate_graph_answer(result['data'])
            samples = []
            for _ in range(plan['warm_measured']):
                result = run_cli(command, root, invocation, timeout)
                if not result['ok'] or normalize_answer(result.get('data')) != reference:
                    return {'ok': False, 'errors': [f'warm {name}/{mode} answer differs']}
                if name == 'graph':
                    validate_graph_answer(result['data'])
                samples.append({'sample': {key: value for key, value in result.items() if key != 'data'},
                                'index_bytes': index_size(root)})
            results[f'{name}_{mode}'] = {'samples': samples,
                                         'summary': summarize_samples(samples)}
    return {'ok': True, 'queries': results}


def lock_owners(lock_path):
    """Return holder and waiter PIDs for the POSIX lock on a Linux host."""
    if not Path('/proc/locks').exists():
        raise ValueError('dedicated concurrency benchmark requires Linux /proc/locks')
    stat = lock_path.stat()
    device = f'{os.major(stat.st_dev):02x}:{os.minor(stat.st_dev):02x}:{stat.st_ino}'
    holders, waiters = set(), set()
    for line in Path('/proc/locks').read_text().splitlines():
        parts = line.replace('->', '').split()
        if len(parts) >= 6 and parts[5] == device:
            (waiters if '->' in line else holders).add(int(parts[4]))
    return holders, waiters


def process_family(pid):
    family = {pid}
    children_path = Path(f'/proc/{pid}/task/{pid}/children')
    try:
        children = children_path.read_text().split()
    except FileNotFoundError:
        children = []
    for child in children:
        family.update(process_family(int(child)))
    return family


def wait_for_lock(lock_path, processes, waiting, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        holders, waiters = lock_owners(lock_path)
        matching = waiters if waiting else holders
        owners = [next(iter(process_family(pid) & matching), None) for pid in processes]
        if owners and all(owner is not None for owner in owners):
            return owners
        time.sleep(0.005)
    return None


def wait_for_competing_writers(lock_path, wrappers, command, timeout):
    """Observe both processes inside the product's writer-lock wait path."""
    deadline = time.monotonic() + timeout
    jcmd = Path(command[0]).with_name('jcmd')
    while time.monotonic() < deadline:
        _, posix_waiters = lock_owners(lock_path)
        observed = []
        for wrapper in wrappers:
            family = process_family(wrapper)
            blocking = family & posix_waiters
            if blocking:
                observed.append((next(iter(blocking)), 'proc_locks'))
                continue
            for pid in family - {wrapper}:
                try:
                    dump = subprocess.run([str(jcmd), str(pid), 'Thread.print'],
                                          capture_output=True, text=True, timeout=3,
                                          check=False)
                except (OSError, subprocess.TimeoutExpired):
                    continue
                if dump.returncode == 0 and 'IndexSnapshotStore.acquireFileLock' in dump.stdout:
                    observed.append((pid, 'java_thread_dump'))
                    break
        if len(observed) == len(wrappers):
            return observed
        time.sleep(0.05)
    return None


def clean_graph_reference(command, root, count, timeout, graph_args):
    with tempfile.TemporaryDirectory(prefix='jsrc-perf-reference-') as temp:
        reference_root = Path(temp) / 'project'
        shutil.copytree(root, reference_root, ignore=shutil.ignore_patterns('.jsrc'))
        indexed = run_cli(command, reference_root, ['index'], timeout, expect_json=False)
        if not indexed['ok']:
            raise ValueError(f'clean reference index: {indexed.get("error")}')
        parse_index_result(indexed['data'], count, count)
        answer = run_cli(command, reference_root, graph_args, timeout)
        if not answer['ok']:
            raise ValueError(f'clean reference graph: {answer.get("error")}')
        return normalize_answer(answer['data'])


def run_concurrent_publication(command, root, count, seed, timeout):
    """Observe readers under a real writer lock and force two writers to wait."""
    root = root.resolve()
    if root.exists():
        if not (root / 'corpus-manifest.json').exists():
            raise ValueError('refusing to replace a directory without corpus-manifest.json')
        shutil.rmtree(root)
    generate_corpus(root, count, seed)
    initial = run_cli(command, root, ['index'], timeout, expect_json=False)
    if not initial['ok']:
        return {'ok': False, 'errors': [f'initial index: {initial.get("error")}']}
    try:
        parse_index_result(initial['data'], count, count)
        graph_args = ['--frozen-index', *warm_queries(count)['graph']]
        before_result = run_cli(command, root, graph_args, timeout)
        if not before_result['ok']:
            raise ValueError(f'before graph: {before_result.get("error")}')
        before = normalize_answer(before_result['data'])
        first_source = select_changes(count, 1, seed)[0]
        apply_mutation(root, count, 'edit_single', seed)
        after = clean_graph_reference(command, root, count, timeout, graph_args)
        if before == after:
            raise ValueError('first mutation did not change the graph answer')

        lock_path = root / '.jsrc' / 'index.lock'
        writer_pid = []
        started = threading.Event()
        def start_writer(pid):
            writer_pid.append(pid)
            started.set()
        def reader():
            answers, under_lock = [], 0
            for _ in range(3):
                result = run_cli(command, root, graph_args, timeout)
                if not result['ok']:
                    raise ValueError(f'concurrent reader: {result.get("error")}')
                answers.append(normalize_answer(result['data']))
                under_lock += writer_pid[0] in lock_owners(lock_path)[0]
            return answers, under_lock
        with ThreadPoolExecutor(max_workers=5) as workers:
            writer = workers.submit(run_cli, command, root, ['index'], timeout,
                                    expect_json=False, on_start=start_writer)
            holder = (wait_for_lock(lock_path, writer_pid, False, timeout)
                      if started.wait(timeout) else None)
            if holder is None:
                raise ValueError('writer lock was not observed during publication')
            writer_pid[0] = holder[0]
            os.kill(writer_pid[0], signal.SIGSTOP)
            pause_start = time.monotonic_ns()
            try:
                if writer_pid[0] not in lock_owners(lock_path)[0]:
                    raise ValueError('writer released lock before controlled pause')
                readers = [workers.submit(reader) for _ in range(4)]
                observed = [future.result() for future in readers]
            finally:
                os.kill(writer_pid[0], signal.SIGCONT)
            paused_ms = (time.monotonic_ns() - pause_start) / 1e6
            written = writer.result()
        if not written['ok']:
            raise ValueError(f'concurrent writer: {written.get("error")}')
        parse_index_result(written['data'], count, 1)
        if any(not answer_matches_generation(answer, before, after)
               for answers, _ in observed for answer in answers):
            raise ValueError('mixed snapshot answer during concurrent publication')
        completed_under_lock = sum(held for _, held in observed)
        if completed_under_lock == 0:
            raise ValueError('no reader completed while writer held publication lock')
        settled = run_cli(command, root, graph_args, timeout)
        if not settled['ok'] or normalize_answer(settled.get('data')) != after:
            raise ValueError('reader answer after publication differs from clean reference')

        second_source = next(number for number in select_changes(count, count, seed + 1)
                             if number not in (first_source, 20))
        append_calling_method(root, count, second_source, 'competingRevision', seed + 1)
        final_reference = clean_graph_reference(command, root, count, timeout, graph_args)
        if final_reference == after:
            raise ValueError('second mutation did not change the graph answer')

        waiting_pids = []
        waiting_started = threading.Event()
        def start_competing(pid):
            waiting_pids.append(pid)
            if len(waiting_pids) == 2:
                waiting_started.set()
        with lock_path.open('a+b') as lock_file:
            fcntl.lockf(lock_file, fcntl.LOCK_EX)
            try:
                with ThreadPoolExecutor(max_workers=2) as workers:
                    futures = [workers.submit(run_cli, command, root, ['index'], timeout,
                                              expect_json=False, on_start=start_competing)
                               for _ in range(2)]
                    observed_wait = (wait_for_competing_writers(lock_path, waiting_pids,
                                                                command, timeout)
                                     if waiting_started.wait(timeout) else None)
                    fcntl.lockf(lock_file, fcntl.LOCK_UN)
                    competing = [future.result() for future in futures]
            finally:
                fcntl.lockf(lock_file, fcntl.LOCK_UN)
        if observed_wait is None:
            raise ValueError('two writers were not observed waiting for the same lock')
        if any(not result['ok'] for result in competing):
            raise ValueError('competing writer failed')
        changes = []
        for result in competing:
            parsed = None
            for expected in (0, 1):
                try:
                    parsed = parse_index_result(result['data'], count, expected)
                    break
                except ValueError:
                    continue
            if parsed is None:
                raise ValueError('competing writer returned invalid re-indexed count')
            changes.append(parsed['reindexed'])
        if 1 not in changes:
            raise ValueError('neither competing writer reported the changed source')
        verify_refresh = run_cli(command, root, ['index'], timeout, expect_json=False)
        if not verify_refresh['ok']:
            raise ValueError(f'post-writer refresh: {verify_refresh.get("error")}')
        parse_index_result(verify_refresh['data'], count, 0)
        final = run_cli(command, root, graph_args, timeout)
        if not final['ok'] or normalize_answer(final.get('data')) != final_reference:
            raise ValueError('final snapshot differs from clean reference after competing writers')
        return {'ok': True, 'reader_count': 4,
                'reader_completed_under_lock': completed_under_lock,
                'writer_paused_under_lock': True,
                'writer_pause_ms': paused_ms,
                'reader_samples': [answers for answers, _ in observed],
                'reference_reindexed': count,
                'single_writer': {k: v for k, v in written.items() if k != 'data'},
                'writer_waiters_observed': [pid for pid, _ in observed_wait],
                'writer_wait_evidence': [method for _, method in observed_wait],
                'writers': [{k: v for k, v in result.items() if k != 'data'}
                            for result in competing],
                'writer_reindexed_counts': changes,
                'final_index_bytes': index_size(root)}
    except ValueError as ex:
        return {'ok': False, 'errors': [str(ex)]}


def run_interrupted_publication(command, root, count, seed, timeout):
    """Kill a writer while it holds the publication lock, then recover."""
    root = root.resolve()
    graph_args = ['--frozen-index', *warm_queries(count)['graph']]
    try:
        before_result = run_cli(command, root, graph_args, timeout)
        if not before_result['ok']:
            raise ValueError(f'pre-interruption graph: {before_result.get("error")}')
        validate_graph_answer(before_result['data'])
        before = normalize_answer(before_result['data'])
        append_calling_method(root, count, min(count - 1, 30),
                              'interruptedRevision', seed + 2)
        after = clean_graph_reference(command, root, count, timeout, graph_args)
        if before == after:
            raise ValueError('interrupted mutation did not change the graph answer')
        lock_path = root / '.jsrc' / 'index.lock'
        writer_pid = []
        started = threading.Event()
        def start_writer(pid):
            writer_pid.append(pid)
            started.set()
        with ThreadPoolExecutor(max_workers=1) as workers:
            writer = workers.submit(run_cli, command, root, ['index'], timeout,
                                    expect_json=False, on_start=start_writer)
            holder = (wait_for_lock(lock_path, writer_pid, False, timeout)
                      if started.wait(timeout) else None)
            if holder is None:
                raise ValueError('writer lock was not observed before interruption')
            pid = holder[0]
            os.kill(pid, signal.SIGSTOP)
            stopped = True
            try:
                if pid not in lock_owners(lock_path)[0]:
                    raise ValueError('writer released lock before interruption')
                os.kill(pid, signal.SIGKILL)
                stopped = False
            finally:
                if stopped:
                    try:
                        os.kill(pid, signal.SIGCONT)
                    except ProcessLookupError:
                        pass
            interrupted = writer.result()
        if interrupted['ok']:
            raise ValueError('interrupted writer unexpectedly succeeded')
        persisted = run_cli(command, root, graph_args, timeout)
        if not persisted['ok']:
            raise ValueError(f'persisted snapshot unreadable: {persisted.get("error")}')
        validate_graph_answer(persisted['data'])
        observed = normalize_answer(persisted['data'])
        if not answer_matches_generation(observed, before, after):
            raise ValueError('mixed or corrupt snapshot after interrupted publication')
        recovered = run_cli(command, root, ['index'], timeout, expect_json=False)
        if not recovered['ok']:
            raise ValueError(f'recovery refresh: {recovered.get("error")}')
        expected = 0 if observed == after else 1
        parse_index_result(recovered['data'], count, expected)
        final = run_cli(command, root, graph_args, timeout)
        if not final['ok'] or normalize_answer(final.get('data')) != after:
            raise ValueError('recovered snapshot differs from clean rebuild')
        return {'ok': True, 'killed_under_lock': True,
                'persisted_generation': 'after' if expected == 0 else 'before',
                'recovery_reindexed': expected,
                'interrupted_writer': {k: v for k, v in interrupted.items() if k != 'data'},
                'recovery_sample': {k: v for k, v in recovered.items() if k != 'data'},
                'final_index_bytes': index_size(root)}
    except ValueError as ex:
        return {'ok': False, 'errors': [str(ex)]}


def environment(root=None, command=None):
    cpu_model = ''
    cpuinfo = Path('/proc/cpuinfo')
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith('model name'):
                cpu_model = line.partition(':')[2].strip()
                break
    governor = Path('/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor')
    filesystem = subprocess.run(['stat', '-f', '-c', '%T', str(root or Path.cwd())],
                                capture_output=True, text=True, check=False).stdout.strip()
    return {'platform': platform.platform(), 'cpu_model': cpu_model,
            'cpu_count': os.cpu_count(),
            'governor': governor.read_text().strip() if governor.exists() else 'unavailable',
            'filesystem': filesystem,
            'java': subprocess.run([command[0] if command else 'java', '-version'], capture_output=True,
                                   text=True, check=False).stderr.splitlines()[:1],
            'runner_id': os.environ.get('JSRC_PERF_RUNNER_ID', 'unidentified')}


def run_smoke_sequence(command, root, count, seed, timeout):
    scenarios = {}
    before = None
    for name, expected in (('cold', count), ('unchanged', 0), ('edit_single', 1)):
        if name == 'edit_single':
            apply_mutation(root, count, name, seed)
        result = run_cli(command, root, ['index'], timeout, expect_json=False)
        if not result['ok']:
            return {'ok': False, 'errors': [f'{name}: {result.get("error")}']}
        try:
            parse_index_result(result['data'], count, expected)
            if 'phase_trace' not in result:
                raise ValueError('index phase trace missing')
            validate_index_trace(result['phase_trace'], expected)
            answers = query_answers(command, root, timeout)
            if name == 'cold':
                before = answers
            elif name == 'unchanged' and answers != before:
                raise ValueError('unchanged refresh changed query answers')
            elif name == 'edit_single' and answers['graph'] == before['graph']:
                raise ValueError('edited call graph did not change')
        except ValueError as ex:
            return {'ok': False, 'errors': [f'{name}: {ex}']}
        sample = {'sample': {key: value for key, value in result.items() if key != 'data'},
                  'index_bytes': index_size(root), 'reindexed': expected,
                  'corpus_sha256': corpus_digest(root)}
        scenarios[name] = {'samples': [sample], 'summary': summarize_samples([sample])}
    warm = {}
    for name, args in warm_queries(count).items():
        normal = run_cli(command, root, args, timeout)
        frozen = run_cli(command, root, ['--frozen-index', *args], timeout)
        if not normal['ok'] or not frozen['ok'] or \
                normalize_answer(normal.get('data')) != normalize_answer(frozen.get('data')):
            return {'ok': False, 'errors': [f'warm {name}: normal/frozen mismatch']}
        if name == 'graph':
            validate_graph_answer(normal['data'])
            validate_graph_answer(frozen['data'])
        for mode, value in (('normal', normal), ('frozen', frozen)):
            sample = {'sample': {key: item for key, item in value.items() if key != 'data'},
                      'index_bytes': index_size(root)}
            warm[f'{name}_{mode}'] = {'samples': [sample],
                                      'summary': summarize_samples([sample])}
    return {'ok': True, 'scenarios': scenarios, 'warm': warm}


def run_benchmark(command, root, count, seed, profile, timeout, real_project=None):
    plan = benchmark_plan(profile)
    if root.exists():
        if not (root / 'corpus-manifest.json').exists():
            raise ValueError('refusing to replace a directory without corpus-manifest.json')
        shutil.rmtree(root)
    manifest = generate_corpus(root, count, seed)
    corpus_shape = profile_sources(root)
    real = None
    if real_project is not None:
        real = {'path': str(real_project.resolve()), 'profile': profile_sources(real_project),
                'commit': subprocess.run(['git', '-C', str(real_project), 'rev-parse', 'HEAD'],
                                         capture_output=True, text=True, check=False).stdout.strip()}
        if not real['commit']:
            raise ValueError('real project must have a pinned Git commit')
    report = {'schema': 1, 'run_id': str(uuid.uuid4()), 'status': 'running',
              'profile': profile, 'plan': plan,
              'command': command, 'corpus': manifest, 'corpus_shape': corpus_shape,
              'real_project': real, 'environment': environment(root, command),
              'commit': subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True,
                                       text=True, check=False).stdout.strip(),
              'scenarios': {}, 'summary': {}, 'failures': []}
    if profile == 'smoke':
        smoke = run_smoke_sequence(command, root, count, seed, timeout)
        if not smoke['ok']:
            report['status'] = 'failed'
            report['failures'] = smoke['errors']
            return report
        report['scenarios'] = smoke['scenarios']
        report['warm'] = smoke['warm']
        for name, data in {**smoke['scenarios'], **{'warm.' + k: v for k, v in smoke['warm'].items()}}.items():
            for key, value in data['summary'].items():
                report['summary'][f'{name}.{key}'] = value
        report['status'] = 'passed'
        return report
    for scenario in SCENARIOS:
        for _ in range(plan['index_preparations']):
            prep = run_scenario(command, root, count, seed, scenario, timeout,
                                require_trace=True)
            if not prep['ok']:
                report['failures'].append({scenario: prep.get('errors')})
                report['status'] = 'failed'
                return report
        samples = []
        for iteration in range(plan['index_measured']):
            sample = run_scenario(command, root, count, seed, scenario, timeout,
                                  verify=iteration == 0, require_trace=True)
            if not sample['ok']:
                report['failures'].append({scenario: sample.get('errors')})
                report['status'] = 'failed'
                return report
            samples.append(sample)
        summary = summarize_samples(samples)
        report['scenarios'][scenario] = {'samples': samples, 'summary': summary}
        for key, value in summary.items():
            report['summary'][f'{scenario}.{key}'] = value
    warm = run_warm(command, root, count, seed, plan, timeout)
    if not warm['ok']:
        report['failures'].append({'warm': warm.get('errors')})
        report['status'] = 'failed'
        return report
    report['warm'] = warm['queries']
    for name, data in warm['queries'].items():
        for key, value in data['summary'].items():
            report['summary'][f'warm.{name}.{key}'] = value
    concurrency = run_concurrent_publication(command, root, count, seed, timeout)
    if not concurrency['ok']:
        report['failures'].append({'concurrency': concurrency.get('errors')})
        report['status'] = 'failed'
        return report
    report['concurrency'] = concurrency
    recovery = run_interrupted_publication(command, root, count, seed, timeout)
    if not recovery['ok']:
        report['failures'].append({'recovery': recovery.get('errors')})
        report['status'] = 'failed'
        return report
    report['recovery'] = recovery
    report['status'] = 'passed'
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    generate = sub.add_parser('generate')
    generate.add_argument('--root', type=Path, required=True)
    generate.add_argument('--files', type=int, choices=(1000, 5000, 10000), required=True)
    generate.add_argument('--seed', type=int, default=17)
    profile_cmd = sub.add_parser('profile')
    profile_cmd.add_argument('--root', type=Path, required=True)
    measure = sub.add_parser('run')
    measure.add_argument('--root', type=Path, required=True)
    measure.add_argument('--files', type=int, choices=(1000, 5000, 10000), required=True)
    measure.add_argument('--seed', type=int, default=17)
    measure.add_argument('--profile', choices=('smoke', 'dedicated'), required=True)
    measure.add_argument('--jar', type=Path, required=True)
    measure.add_argument('--java', default='java', help='Java executable for the measured runtime')
    measure.add_argument('--real-project', type=Path)
    measure.add_argument('--timeout', type=int, default=900)
    measure.add_argument('--output', type=Path, required=True)
    compare = sub.add_parser('compare')
    compare.add_argument('--baseline', type=Path, required=True)
    compare.add_argument('--current', type=Path, required=True)
    compare.add_argument('--thresholds', type=Path, required=True)
    compare.add_argument('--second-report', type=Path)
    compare.add_argument('--enforce', action='store_true')
    options = parser.parse_args(argv)
    if options.action == 'generate':
        print(json.dumps(generate_corpus(options.root, options.files, options.seed), indent=2))
    elif options.action == 'profile':
        print(json.dumps(profile_sources(options.root), indent=2))
    elif options.action == 'run':
        command = [options.java, '--enable-native-access=ALL-UNNAMED', '-jar',
                   str(options.jar.resolve())]
        report = run_benchmark(command, options.root, options.files, options.seed,
                               options.profile, options.timeout, options.real_project)
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(report, indent=2) + '\n')
        print(f'{report["status"]}: {options.output}')
        return 0 if report['status'] == 'passed' else 1
    elif options.action == 'compare':
        baseline = json.loads(options.baseline.read_text())
        current = json.loads(options.current.read_text())
        factors = json.loads(options.thresholds.read_text())
        first = compare_reports(baseline, current, factors, options.enforce)
        if options.enforce and first['breaches']:
            if options.second_report is None:
                raise ValueError('second independent report required to confirm a breach')
            second = json.loads(options.second_report.read_text())
            if not current.get('run_id') or not second.get('run_id') \
                    or current['run_id'] == second['run_id'] \
                    or options.current.resolve() == options.second_report.resolve():
                raise ValueError('second independent report required to confirm a breach')
            confirmation = compare_reports(baseline, second, factors, True)
            first['confirmed_breaches'] = sorted(set(first['breaches']) &
                                                 set(confirmation['breaches']))
            first['failed'] = bool(first['confirmed_breaches'])
        print(json.dumps(first, indent=2))
        return 1 if first['failed'] else 0
    return 0


if __name__ == '__main__':
    sys.exit(main())
