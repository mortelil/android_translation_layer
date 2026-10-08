#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check sibling source revisions without fetching or changing any repository."""
import json
import os
import pathlib
import subprocess
import sys

repo = pathlib.Path(__file__).resolve().parents[2]
workspace = pathlib.Path(os.environ.get('ATL_WORKSPACE', repo.parent))
lock = json.loads((repo / 'dependency-lock.json').read_text())
problems = []
for name, entry in lock['dependencies'].items():
    path = workspace / name
    try:
        actual = subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
        dirty = subprocess.check_output(['git', '-C', str(path), 'status', '--porcelain'], text=True).strip()
    except (subprocess.CalledProcessError, OSError):
        problems.append(name + ': missing or unreadable checkout')
        continue
    if actual != entry['commit']:
        problems.append(name + ': expected ' + entry['commit'] + ', got ' + actual)
    if dirty:
        problems.append(name + ': has uncommitted changes')
if problems:
    print('\n'.join(problems), file=sys.stderr)
    if os.environ.get('ATL_ALLOW_DEPENDENCY_CHANGES') != '1':
        print('Use the locked revisions, or set ATL_ALLOW_DEPENDENCY_CHANGES=1 for intentional development.', file=sys.stderr)
        sys.exit(1)
    print('Continuing with explicitly allowed dependency changes.', file=sys.stderr)
else:
    print('Companion dependency revisions match dependency-lock.json.')
