#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the real probe's four-step shape, independent of stored verdicts."""
import copy
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent / 'fixtures'))
import make_unsupported_remote_fixtures as fixtures
import verify_unsupported_remote as verifier

def run():
    doc = fixtures.make_positive(fixtures.load())
    events = fixtures.remote_events(doc)
    identity = next(e for e in events if e['event'] == 'remoteIdentity')
    events.remove(identity)
    stop = next(i for i, e in enumerate(events) if e['component'] == verifier.REMOTE and e['event'] == 'stopService')
    barrier = copy.deepcopy(events[stop - 1])
    assert barrier['event'] == 'barrier'
    events[stop:stop] = [identity, barrier]
    fixtures.stamp(doc)
    verifier.verify(doc)
    def reject(mutant):
        fixtures.stamp(mutant)
        try: verifier.verify(mutant)
        except verifier.Reject: return
        raise AssertionError('bad evidence was accepted')
    missing = copy.deepcopy(doc)
    ev = fixtures.remote_events(missing)
    ev.pop(next(i for i, e in enumerate(ev) if e['component'] == verifier.REMOTE and e['event'] == 'barrier'))
    reject(missing)
    extra = copy.deepcopy(doc)
    fixtures.remote_events(extra).append(copy.deepcopy(barrier))
    reject(extra)
    wrong = copy.deepcopy(doc)
    ev = fixtures.remote_events(wrong)
    idx = next(i for i,e in enumerate(ev) if e['event'] == 'remoteIdentity')
    ev[idx + 1], ev[idx + 2] = ev[idx + 2], ev[idx + 1]
    reject(wrong)
    print('REMOTE BARRIER TESTS PASS: real shape accepted, missing/extra/misplaced rejected')

if __name__ == '__main__': run()
