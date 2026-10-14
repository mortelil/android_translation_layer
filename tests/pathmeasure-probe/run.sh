#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
[ "$#" -eq 2 ] || { echo "Usage: $0 SIGNED_APK OUTPUT_DIR" >&2; exit 2; }
: "${ATL_PROBE_RUNNER:?Set the host probe runner path}"
: "${ATL_PROBE_SOURCE_DIR:?Set the checkout under test}"
: "${ATL_PROBE_BUILD_DIR:?Set its build directory}"
: "${ATL_PROBE_PREFIX:?Set its dependency prefix}"
: "${ATL_PROBE_CORE_JAR:?Set its ART core jar}"
sh "$ATL_PROBE_RUNNER" "$1" "$2" PATHTEST_JSON_END 30
python3 - "$2/app-results.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
assert r['failures'] == 0 and r['cases'] and all(c['ok'] for c in r['cases']), r
print('PASS:', len(r['cases']), 'PathMeasure assertions')
PY
