#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run inside Alpine: ATL_BUILD_DIR=... ATL_PREFIX=... ATL_CORE_JAR=... python3 run.py OUT"""
import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

src = Path(__file__).resolve().parent
repo = src.parent.parent
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
build = Path(os.environ['ATL_BUILD_DIR']).resolve()
art = subprocess.check_output(['pkg-config', '--variable=libdir', 'art-standalone'], text=True).strip() + '/java'

def command(args, **kw):
    subprocess.run(list(map(str, args)), check=True, **kw)

# Two real dex revisions at the identical launch path.
for version in (1, 2):
    d = out / ('v%d' % version)
    (d / 'classes').mkdir(parents=True, exist_ok=True)
    (d / 'Stamp.java').write_text('package org.atl.prepare; public class Stamp { public static final int VALUE = %d; }' % version)
    command(['/usr/lib/jvm/java-1.8-openjdk/bin/javac', '-source', '8', '-target', '8', '-cp',
             str(build / 'src/api-impl/hax.jar') + ':' + art + '/core-all_classes.jar',
             '-d', d / 'classes', src / 'Probe.java', d / 'Stamp.java'])
    command(['dx', '--dex', '--output=' + str(d / 'classes.dex'), d / 'classes'])
    command(['aapt', 'package', '-f', '-M', src / 'AndroidManifest.xml', '-I', build / 'res/framework-res/framework-res.apk', '-F', d / 'probe.apk'])
    command(['aapt', 'add', d / 'probe.apk', 'classes.dex'], cwd=d)

apk = out / 'probe.apk'
shutil.copyfile(out / 'v1/probe.apk', apk)
env = os.environ.copy()
env.pop('ATL_APK_VERIFIER', None)  # unsigned synthetic APK; verifier failure has its own row
for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'JDWP_LISTEN'):
    env.pop(key, None)
env.update(ANDROID_APP_DATA_DIR=str(out / 'data'), XDG_CACHE_HOME=str(out / 'cache'), ATL_MEDIA_ROOT=str(out / 'empty-media'))
launcher = [str(repo / 'scripts/mobile/run.sh'), str(apk)]

def run(name, options=('--prepare',), expected=0, overrides=None):
    e = dict(env, **(overrides or {}))
    args = launcher + list(options)
    if not options:
        args = ['xvfb-run', '-a'] + args
    t = time.monotonic()
    result = subprocess.run(args, env=e, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (out / (name + '.log')).write_bytes(result.stdout)
    log = result.stdout.decode(errors='replace')
    assert (result.returncode == 0) == (expected == 0), (name, result.returncode, log[-3000:])
    if options == ('--prepare',):
        assert 'APP_CODE_RAN' not in log, name
        assert ('ATL PREPARE OK:' in log) == (expected == 0), name
    print('%s PASS %.3fs' % (name, time.monotonic() - t), flush=True)
    return log

def no_compile(log):
    assert 'Compiling dex file:' not in log and 'GenerateImage:' not in log

run('prepare-cold')
no_compile(run('prepare-repeat'))
log = run('launch-prepared', ())
assert 'PREPARE_LAUNCH_PASS 1' in log and 'APP_CODE_RAN provider' in log
no_compile(log)
shutil.copyfile(out / 'v2/probe.apk', apk)
log = run('prepare-apk-replaced')
assert 'Compiling dex file:' in log and '--dex-file=' + str(apk) in log
log = run('launch-updated', ())
assert 'PREPARE_LAUNCH_PASS 2' in log
no_compile(log)
run('verifier-failure', expected=1, overrides={'ATL_APK_VERIFIER': '/bin/false'})
run('conflicting-options', ('--prepare', '--install-internal'), expected=1)

# All startup paths must wait for the shared cache lock, including ordinary launch.
for name, options in [('prepare-lock', ['--prepare']), ('launch-lock', [])]:
    lock = open(out / 'cache/art/.atl-startup.lock', 'r+')
    fcntl.flock(lock, fcntl.LOCK_EX)
    args = launcher + options
    if not options:
        args = ['xvfb-run', '-a'] + args
    with open(out / (name + '.log'), 'wb') as log:
        p = subprocess.Popen(args, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if 'Waiting for ART startup cache lock' in (out / (name + '.log')).read_text(errors='replace'):
                    break
                if p.poll() is not None: break
                time.sleep(.05)
            assert 'Waiting for ART startup cache lock' in (out / (name + '.log')).read_text(errors='replace'), name
            assert p.poll() is None, name
            assert 'JVM launched' not in (out / (name + '.log')).read_text(errors='replace'), name
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()
        assert p.wait(timeout=60) == 0, name
    print(name + ' PASS', flush=True)

# Two fresh-cache preparers are serialized; only one compiles each artifact.
parallel_env = dict(env, XDG_CACHE_HOME=str(out / 'parallel-cache'))
with open(out / 'parallel-a.log', 'wb') as a, open(out / 'parallel-b.log', 'wb') as b:
    procs = [subprocess.Popen(launcher + ['--prepare'], env=parallel_env, stdout=f, stderr=subprocess.STDOUT) for f in (a,b)]
    statuses = [p.wait(timeout=120) for p in procs]
    assert statuses == [0, 0]
logs = [(out / ('parallel-' + n + '.log')).read_text(errors='replace') for n in ('a','b')]
assert all('ATL PREPARE OK:' in log and 'APP_CODE_RAN' not in log for log in logs)
assert sum('Compiling dex file:' in log for log in logs) == 1
print('parallel-cache PASS', flush=True)

# ART can fall back to interpreting when dex2oat fails: preparation must fail.
for p in (out / 'cache/art').rglob('*probe.apk*'):
    if p.is_file(): p.unlink()
(out / 'bin').mkdir(exist_ok=True)
compiler = out / 'bin/dex2oat'
compiler.write_text('#!/bin/sh\necho TEST_COMPILER_FAILURE >&2\nexit 1\n')
compiler.chmod(0o755)
log = run('compiler-failure', expected=1, overrides={'PATH': str(out / 'bin') + ':' + env['PATH']})
assert 'TEST_COMPILER_FAILURE' in log
assert 'ART cache is not up to date' in log
# Runtime dex replacement at a stable path must invalidate that artifact too.
# Use an isolated build view; never modify the tested build's jars.
runtime = out / 'runtime'
runtime.mkdir()
for entry in build.iterdir():
    if entry.name != 'ghax.jar': (runtime / entry.name).symlink_to(entry)
for version in (1, 2):
    d = out / ('runtime-v%d' % version)
    (d / 'classes').mkdir(parents=True)
    (d / 'RuntimeMarker.java').write_text('public class RuntimeMarker { public static final int VALUE = %d; }' % version)
    command(['/usr/lib/jvm/java-1.8-openjdk/bin/javac', '-d', d / 'classes', d / 'RuntimeMarker.java'])
    command(['dx', '--dex', '--output=' + str(d / 'ghax.jar'), d / 'classes'])
    shutil.copyfile(d / 'ghax.jar', runtime / 'ghax.jar')
    log = run('prepare-runtime-%d' % version, overrides={'ATL_BUILD_DIR': str(runtime)})
    assert 'Compiling dex file:' in log and '/./ghax.jar' in log
    no_compile(run('prepare-runtime-%d-repeat' % version, overrides={'ATL_BUILD_DIR': str(runtime)}))
log = run('launch-runtime-updated', (), overrides={'ATL_BUILD_DIR': str(runtime)})
assert 'PREPARE_LAUNCH_PASS 2' in log
no_compile(log)
print('STARTUP_PREPARATION_PASS', flush=True)
