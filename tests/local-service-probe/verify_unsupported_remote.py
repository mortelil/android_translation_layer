#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check explicit unsupported-process behavior in a completed SVC-014 run.

This is NOT Android parity. Run the normal oracle for the supported scenarios.
The frozen probe requests a declared :remote service plus an unknown component.
"""
import json
import sys


def verify(doc):
    scenarios = [s for s in doc['scenarios'] if s['scenario'] == 'SVC-014']
    assert len(scenarios) == 1, 'missing or duplicate SVC-014'
    events = scenarios[0]['events']
    expected = []
    for component, results in [('SvcUnknown', ['null', 'false', 'false']),
                               ('SvcRemote', ['exception:java.lang.UnsupportedOperationException',
                                              'exception:java.lang.UnsupportedOperationException', 'false'])]:
        for operation, result in zip(['startService', 'bindService', 'stopService'], results):
            expected.append((operation, 'org.atl.tests.svc/.' + component, result))
            expected.append(('barrier', 'org.atl.tests.svc/.' + component, None))
    actual = [(e['event'], e['component'], e['ret']) for e in events]
    assert actual == expected, 'remote was executed, silently accepted, or evidence incomplete'
    assert len({e['seq'] for e in events}) == len(events), 'duplicate sequence'
    for e in events:
        if e.get('ret', '') and str(e['ret']).startswith('exception:'):
            assert e.get('exception') == 'java.lang.UnsupportedOperationException'


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: verify_unsupported_remote.py results.json')
        with open(sys.argv[1]) as stream:
            verify(json.load(stream))
    except (AssertionError, ValueError, KeyError, TypeError, OSError) as error:
        print('REJECT unsupported remote:', error)
        sys.exit(1)
    print('OK explicit remote rejection (NOT Android remote-service support)')
