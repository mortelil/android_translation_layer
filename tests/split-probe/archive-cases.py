#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Derive hostile containers from a signed frozen family, without changing it.
Usage: archive-cases.py FAMILY_DIR OUTPUT_DIR (must not already exist).
"""
from pathlib import Path
import sys, zipfile, warnings
src, out = map(Path, sys.argv[1:])
out.mkdir()
base=(src/'base.apk').read_bytes()
native=(src/'split_config.x86_64.apk').read_bytes()
lang=(src/'split_config.en.apk').read_bytes()
density=(src/'split_config.xxhdpi.apk').read_bytes()

def archive(name, entries):
    with zipfile.ZipFile(out/(name+'.apkm'), 'w') as z:
        for key, data in entries:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                z.writestr(key, data)

archive('colon-name', [('base.apk',base),('split:bad.apk',native)])
archive('traversal', [('base.apk',base),('../split.apk',native)])
archive('duplicate-entry', [('base.apk',base),('split.apk',native),('split.apk',lang)])
archive('duplicate-base', [('a/base.apk',base),('b/base.apk',base)])
archive('no-base', [('split.apk',native)])
archive('duplicate-split', [('base.apk',base),('a.apk',native),('b.apk',native)])
archive('flatten-collision', [('base.apk',base),('a/b.apk',native),('a_b.apk',lang),('density.apk',density)])
archive('invalid-manifest', [('base.apk',base),('bad.apk',b'not an apk')])
archive('too-many', [(str(n)+'.apk',native) for n in range(257)])
# ZIP signing blocks are no longer valid after an extra local entry is added.
tamper=out/'tampered.apk';tamper.write_bytes(native)
with zipfile.ZipFile(tamper,'a') as z:z.writestr('tampered.txt','modified after signing')
archive('invalid-signature', [('base.apk',base),('split.apk',tamper.read_bytes())])
print(out)
