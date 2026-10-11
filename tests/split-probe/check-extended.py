#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check actual execution evidence, not just completion markers.
Usage: FEATURE_RESULTS COLLISION_RESULTS ARCHIVE_NEGATIVE_DIR IDENTITY_NEGATIVE_DIR
"""
import json, sys
from pathlib import Path
feature, collision, archives, identities = map(Path, sys.argv[1:])
f=json.loads(feature.read_text()); assert f['status']=='OK' and f['sdk']>=24, f
c=json.loads(collision.read_text())['observations']
paths=c['selected_members']
assert len(paths)==4 and len(set(paths))==4, paths
assert len({str(Path(p).parent) for p in paths})==4, paths
assert c['class_loaded'] and c['native_load_ok'] and c['native_value']==23063, c
assert c['density_sentinel_resolved'] and c['language_sentinel_resolved'], c
# libcore versions may reject a duplicate ZIP in its constructor, before our
# entry scanner runs. Both are refusal before any application code is loaded.
expected={'colon-name':{15},'traversal':{15},'duplicate-entry':{10,15},
          'duplicate-base':{14},'no-base':{13},'duplicate-split':{14},
          'invalid-manifest':{10,14},'too-many':{15},'invalid-signature':{14}}
for name, exits in expected.items():
    d=archives/name
    assert int((d/'exit').read_text()) in exits, name
    assert 'SPLITPROBE_LOADER_STATIC_INIT' not in (d/'log').read_text(errors='replace'), name
for name in ['version-zero-mismatch','major-mismatch','package-mismatch','missing-dependency','different-signer']:
    d=identities/name
    assert int((d/'exit').read_text())==14, name
    log=(d/'log').read_text(errors='replace')
    assert 'ATL APP STATUS=SPLIT_MISMATCH' in log and 'FEATURE_CONTRACT_PASS' not in log, name
print('PASS: feature code with dexless base, unique colliding members, 9 archive and 5 identity/signature rejections')
