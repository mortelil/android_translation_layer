#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check the SVC-014 remote/unsupported placement contract in a completed probe run.

This is NOT Android parity (run verify_services.py for the supported scenarios).
It verifies the SVC-014 remote/unsupported rows against the contract that
supersedes the pre-repair blanket claim ("remote was executed => REJECT"):

  * SVC-014a, unplaceable component (undeclared .SvcUnknown) - it must NEVER
    run: startService -> null, bindService -> false, stopService -> false and
    no .SvcUnknown callback may appear in the caller's event log.
  * SVC-014b, declared android:process=":remote" component (.SvcRemote) - one
    of two arms, never a silent local instance:
      (a) served by a SPAWNED CHILD: the client side is really placed
          (startService -> the ComponentName, bindService -> true, an
          onServiceConnected carrying a binder, stopService -> true) and the
          component's own callbacks do NOT appear in the caller's log, since
          SvcRemote only logs through EventLog.get() when it shares the
          caller's process - the presence of those callbacks is the observable
          signature of an in-loader (unisolated) instance;
      (b) refused explicitly with java.lang.UnsupportedOperationException
          ("ATL does not support remote or isolated services: ...") on all
          three calls, and the client is never connected.

Anything else - a partly placed arm, a missing placement, an unexpected event,
incomplete observations, duplicate sequence numbers - is rejected fail-closed.

Superseding change: commit 9f3d8039 (lane B childstop / hosted-remote
placement) on branch lifecycle/service, frozen into candidate cb5f9f8b.
Gate rows S14a (isolated refused), S14b (remote: hosted child OR explicit
refusal), S14c (child refused with the frozen message) in tests/service define
the same contract for the service-gate probe.  The frozen reference declares 16
scenarios; the SVC-014 shape it records (placed remote, no in-process
callbacks) is arm (a).
"""
import json
import sys

UNKNOWN = 'org.atl.tests.svc/.SvcUnknown'
REMOTE = 'org.atl.tests.svc/.SvcRemote'
OPS = ('startService', 'bindService', 'stopService')
# Events a hosted Service instance emits through the caller's EventLog singleton.
INSTANCE_EVENTS = ('onCreate', 'onStartCommand', 'onBind', 'onUnbind', 'onRebind', 'onDestroy')
# Connection callbacks the client-side dispatcher emits for the same component.
CLIENT_EVENTS = ('onServiceConnected', 'onServiceDisconnected', 'onBindingDied', 'onNullBinding')
REFUSAL = 'java.lang.UnsupportedOperationException'
UNKNOWN_EXPECTED = {'startService': 'null', 'bindService': 'false', 'stopService': 'false'}


def _placement(events, component):
    return [e for e in events if e['component'] == component]


def _check_shape(rows, component):
    """Enforce the probe's op/barrier shape so partial evidence cannot pass."""
    names = [e['event'] for e in rows]
    other = [n for n in names if n not in OPS and n != 'barrier'
             and n not in INSTANCE_EVENTS and n not in CLIENT_EVENTS]
    assert not other, '%s: unexpected event(s) %s (evidence not understood)' % (component, other)
    ops = [n for n in names if n in OPS]
    assert ops == list(OPS), '%s: expected ops %s in order, got %s' % (component, list(OPS), ops)
    barriers = [i for i, n in enumerate(names) if n == 'barrier']
    assert len(barriers) == len(OPS), '%s: %d barrier(s), want %d' % (
        component, len(barriers), len(OPS))
    for i, op in enumerate(OPS):
        assert barriers[i] > names.index(op), '%s: %s has no barrier (incomplete evidence)' % (
            component, op)


def _placed(ret):
    return bool(ret) and not str(ret).startswith('exception:')


def _refused(event):
    return str(event.get('ret', '')).startswith('exception:') and event.get('exception') == REFUSAL


def verify(doc):
    scenarios = [s for s in doc['scenarios'] if s['scenario'] == 'SVC-014']
    assert len(scenarios) == 1, 'missing or duplicate SVC-014'
    events = scenarios[0]['events']
    assert len({e['seq'] for e in events}) == len(events), 'duplicate sequence'

    observations = doc.get('observations') or {}
    completed = observations.get('scenarios_completed')
    assert completed and 'SVC-014' in completed, 'SVC-014 not completed (incomplete evidence)'
    assert completed == observations.get('scenarios_observed'), 'scenario evidence incomplete'

    # ---- SVC-014a: the unplaceable component must not run anywhere ---------
    unknown_rows = _placement(events, UNKNOWN)
    assert unknown_rows, 'SVC-014a (.SvcUnknown) evidence missing'
    _check_shape(unknown_rows, UNKNOWN)
    assert not [e for e in unknown_rows if e['event'] in INSTANCE_EVENTS], \
        'SVC-014a: the unplaceable component ran in the caller (unisolated local instance)'
    for row in unknown_rows:
        if row['event'] in UNKNOWN_EXPECTED:
            assert row.get('ret') == UNKNOWN_EXPECTED[row['event']], \
                'SVC-014a %s: ret=%r, want %r' % (
                    row['event'], row.get('ret'), UNKNOWN_EXPECTED[row['event']])

    # ---- SVC-014b: hosted child or explicit refusal, never local ----------
    remote_rows = _placement(events, REMOTE)
    assert remote_rows, 'SVC-014b (.SvcRemote) evidence missing'
    _check_shape(remote_rows, REMOTE)
    ran_local = [e['event'] for e in remote_rows if e['event'] in INSTANCE_EVENTS]
    connects = [e for e in remote_rows if e['event'] == 'onServiceConnected']
    ops = {e['event']: e for e in remote_rows if e['event'] in OPS}
    refused = all(_refused(ops[name]) for name in OPS)

    if refused:
        # arm (b): the placement is refused explicitly and nothing started.
        assert not ran_local, 'SVC-014b: refused remote placement still ran (%s)' % ran_local
        assert not connects, 'SVC-014b: refused remote placement still connected the client'
    else:
        # arm (a): the placement is served by a spawned child; the component
        # must not appear in the caller's log (that would be the local instance).
        assert not ran_local, ('SVC-014b: the :remote component ran in the caller '
                               '(%s) - no spawned child and no explicit refusal '
                               '(silent local instance)' % ran_local)
        start = ops['startService'].get('ret')
        assert _placed(start) and str(start).endswith('SvcRemote'), \
            'SVC-014b startService: ret=%r, want the .SvcRemote ComponentName or an explicit refusal' % start
        assert ops['bindService'].get('ret') == 'true', \
            'SVC-014b bindService: ret=%r, want "true" or an explicit refusal' % ops['bindService'].get('ret')
        assert ops['stopService'].get('ret') == 'true', \
            'SVC-014b stopService: ret=%r, want "true" or an explicit refusal' % ops['stopService'].get('ret')
        assert connects and all(e.get('binder') for e in connects), \
            'SVC-014b: no binder-bearing onServiceConnected for the hosted remote'


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: verify_unsupported_remote.py results.json')
        with open(sys.argv[1]) as stream:
            verify(json.load(stream))
    except (AssertionError, ValueError, KeyError, TypeError, OSError) as error:
        print('REJECT unsupported remote:', error)
        sys.exit(1)
    print('OK remote placement contract (spawned child or explicit refusal; '
          'NOT Android remote-service support)')
