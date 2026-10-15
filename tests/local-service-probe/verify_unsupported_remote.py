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
          onServiceConnected carrying a binder, stopService -> true), and the
          placement is PROVED to be served by a different process: the client
          calls the service's identity transaction over the delivered binder
          while the binding is live, and the reported serving pid must differ
          from the client's own pid. The absence of the component's own
          callbacks in the caller's log is NOT accepted as this proof (an
          unisolated instance in the caller produces exactly that absence), so
          it is kept only as an additional fail-closed condition;
      (b) refused explicitly with java.lang.UnsupportedOperationException
          ("ATL does not support remote or isolated services: ...") on all
          three calls; the client is never connected and no identity reply may
          exist (a refusal cannot be accompanied by served work).

Anything else - a partly placed arm, a missing placement, an unexpected event,
incomplete observations, duplicate sequence numbers, missing/same-pid/
malformed/out-of-order/duplicated separation evidence - is rejected fail-closed
with a `REJECT <CODE> <detail>` line (see the codes below).

Superseding change: commit 9f3d8039 (lane B childstop / hosted-remote
placement) on branch lifecycle/service, frozen into candidate cb5f9f8b.
Gate rows S14a (isolated refused), S14b (remote: hosted child OR explicit
refusal), S14c (child refused with the frozen message) in tests/service define
the same contract for the service-gate probe.  The frozen reference declares 16
scenarios; the SVC-014 shape it records (placed remote, no in-process
callbacks) is arm (a) - without separation evidence, which is why the frozen
reference alone cannot accept the arm: only a run whose identity transaction
answered can (see fixtures/remote and run_unsupported_remote_selftests.sh).
"""
import json
import re
import sys

UNKNOWN = 'org.atl.tests.svc/.SvcUnknown'
REMOTE = 'org.atl.tests.svc/.SvcRemote'
OPS = ('startService', 'bindService', 'stopService')
# Events a hosted Service instance emits through the caller's EventLog singleton.
INSTANCE_EVENTS = ('onCreate', 'onStartCommand', 'onBind', 'onUnbind', 'onRebind', 'onDestroy')
# Connection callbacks the client-side dispatcher emits for the same component.
CLIENT_EVENTS = ('onServiceConnected', 'onServiceDisconnected', 'onBindingDied', 'onNullBinding')
# The client-side separation proof: one transaction the SERVICE answered.
EVIDENCE_EVENTS = ('remoteIdentity',)
REFUSAL = 'java.lang.UnsupportedOperationException'
UNKNOWN_EXPECTED = {'startService': 'null', 'bindService': 'false', 'stopService': 'false'}

EXIT_OK = 0
EXIT_REJECT = 1

# Codes carried by the rejection line, so a caller (and the self-test driver
# below) can assert WHICH contract clause rejected the evidence.
CODE_IDENTITY_MISSING = 'IDENTITY_MISSING'
CODE_IDENTITY_DUPLICATE = 'IDENTITY_DUPLICATE'
CODE_IDENTITY_ORDER = 'IDENTITY_ORDER'
CODE_IDENTITY_SAME_PROCESS = 'IDENTITY_SAME_PROCESS'
CODE_IDENTITY_MALFORMED = 'IDENTITY_MALFORMED'
CODE_REFUSAL_EVIDENCE = 'REFUSAL_EVIDENCE'
CODE_UNISOLATED = 'UNISOLATED_INSTANCE'
CODE_PLACEMENT = 'PLACEMENT'
CODE_EVIDENCE = 'EVIDENCE'

# `pid=<int> client_pid=<int> process=<name>` - the whole remoteIdentity payload.
IDENTITY_RE = re.compile(r'^pid=(?P<pid>-?\d+) client_pid=(?P<client_pid>-?\d+) process=(?P<process>\S+)$')


class Reject(Exception):
    def __init__(self, code, detail):
        Exception.__init__(self, '%s %s' % (code, detail))
        self.code = code
        self.detail = detail


def reject(code, detail):
    raise Reject(code, detail)


def _placement(events, component):
    return [e for e in events if e['component'] == component]


def _check_shape(rows, component):
    """Enforce the probe's op/barrier shape so partial evidence cannot pass."""
    names = [e['event'] for e in rows]
    other = [n for n in names if n not in OPS and n != 'barrier'
             and n not in INSTANCE_EVENTS and n not in CLIENT_EVENTS
             and n not in EVIDENCE_EVENTS]
    if other:
        reject(CODE_EVIDENCE, '%s: unexpected event(s) %s (evidence not understood)'
               % (component, other))
    ops = [n for n in names if n in OPS]
    if ops != list(OPS):
        reject(CODE_PLACEMENT, '%s: expected ops %s in order, got %s'
               % (component, list(OPS), ops))
    # The real probe adds a pump step for remoteIdentity, with its own barrier.
    # Historical fixtures insert identity into the bind step and have three.
    # Permit the fourth only immediately after identity; retain all API barriers.
    if names.count('barrier') == 4 and names.count('remoteIdentity') == 1:
        identity = names.index('remoteIdentity')
        if identity + 1 >= len(names) or names[identity + 1] != 'barrier':
            reject(CODE_EVIDENCE, '%s: extra barrier is not the identity barrier' % component)
        names = names[:identity + 1] + names[identity + 2:]
    barriers = [i for i, n in enumerate(names) if n == 'barrier']
    if len(barriers) != len(OPS):
        reject(CODE_EVIDENCE, '%s: %d barrier(s), want %d'
               % (component, len(barriers), len(OPS)))
    for i, op in enumerate(OPS):
        if barriers[i] <= names.index(op) or (i + 1 < len(OPS) and barriers[i] >= names.index(OPS[i + 1])):
            reject(CODE_EVIDENCE, '%s: %s has no barrier (incomplete evidence)' % (component, op))


def _identity(row):
    """Parse one remoteIdentity row; the payload must be complete and plausible."""
    if str(row.get('ret', '')).startswith('exception:'):
        reject(CODE_IDENTITY_MALFORMED,
               'remoteIdentity: the identity transaction failed (%s), the serving process is unproven'
               % row.get('ret'))
    match = IDENTITY_RE.match(str(row.get('ret', '')))
    if not match:
        reject(CODE_IDENTITY_MALFORMED,
               'remoteIdentity: ret=%r, want "pid=<int> client_pid=<int> process=<name>"'
               % row.get('ret'))
    pid = int(match.group('pid'))
    client_pid = int(match.group('client_pid'))
    process = match.group('process')
    if pid <= 0 or client_pid <= 0:
        reject(CODE_IDENTITY_MALFORMED,
               'remoteIdentity: pid=%d client_pid=%d, want positive pids' % (pid, client_pid))
    if process != process.strip() or len(process) > 256:
        reject(CODE_IDENTITY_MALFORMED, 'remoteIdentity: implausible process name %r' % process)
    return pid, client_pid, process


def verify(doc):
    scenarios = [s for s in doc['scenarios'] if s['scenario'] == 'SVC-014']
    if len(scenarios) != 1:
        reject(CODE_EVIDENCE, 'missing or duplicate SVC-014')
    events = scenarios[0]['events']
    if len({e['seq'] for e in events}) != len(events):
        reject(CODE_EVIDENCE, 'duplicate sequence')
    if not events:
        reject(CODE_EVIDENCE, 'SVC-014 evidence empty (aborted scenario)')

    observations = doc.get('observations') or {}
    completed = observations.get('scenarios_completed')
    if not completed or 'SVC-014' not in completed:
        reject(CODE_EVIDENCE, 'SVC-014 not completed (incomplete evidence)')
    if completed != observations.get('scenarios_observed'):
        reject(CODE_EVIDENCE, 'scenario evidence incomplete')

    # ---- SVC-014a: the unplaceable component must not run anywhere ---------
    unknown_rows = _placement(events, UNKNOWN)
    if not unknown_rows:
        reject(CODE_EVIDENCE, 'SVC-014a (.SvcUnknown) evidence missing')
    _check_shape(unknown_rows, UNKNOWN)
    if [e for e in unknown_rows if e['event'] in INSTANCE_EVENTS]:
        reject(CODE_UNISOLATED, 'SVC-014a: the unplaceable component ran in the caller (unisolated local instance)')
    for row in unknown_rows:
        if row['event'] in UNKNOWN_EXPECTED and row.get('ret') != UNKNOWN_EXPECTED[row['event']]:
            reject(CODE_PLACEMENT, 'SVC-014a %s: ret=%r, want %r'
                   % (row['event'], row.get('ret'), UNKNOWN_EXPECTED[row['event']]))

    # ---- SVC-014b: hosted child or explicit refusal, never local ----------
    remote_rows = _placement(events, REMOTE)
    if not remote_rows:
        reject(CODE_EVIDENCE, 'SVC-014b (.SvcRemote) evidence missing')
    _check_shape(remote_rows, REMOTE)
    names = [e['event'] for e in remote_rows]
    ran_local = [e['event'] for e in remote_rows if e['event'] in INSTANCE_EVENTS]
    connects = [e for e in remote_rows if e['event'] == 'onServiceConnected']
    identity_rows = [e for e in remote_rows if e['event'] in EVIDENCE_EVENTS]
    ops = {e['event']: e for e in remote_rows if e['event'] in OPS}
    refused = all(_refused(ops[name]) for name in OPS)

    if refused:
        # arm (b): the placement is refused explicitly and nothing started.
        if ran_local:
            reject(CODE_UNISOLATED, 'SVC-014b: refused remote placement still ran (%s)' % ran_local)
        if connects:
            reject(CODE_REFUSAL_EVIDENCE, 'SVC-014b: refused remote placement still connected the client')
        if identity_rows:
            reject(CODE_REFUSAL_EVIDENCE,
                   'SVC-014b: refused remote placement still answered the identity transaction')
        return

    # arm (a): served by a spawned child.
    if ran_local:
        reject(CODE_UNISOLATED, 'SVC-014b: the :remote component ran in the caller (%s) - '
               'no spawned child and no explicit refusal (silent local instance)' % ran_local)
    start = ops['startService'].get('ret')
    if not (_placed(start) and str(start).endswith('SvcRemote')):
        reject(CODE_PLACEMENT, 'SVC-014b startService: ret=%r, want the .SvcRemote ComponentName '
               'or an explicit refusal' % start)
    if ops['bindService'].get('ret') != 'true':
        reject(CODE_PLACEMENT, 'SVC-014b bindService: ret=%r, want "true" or an explicit refusal'
               % ops['bindService'].get('ret'))
    if ops['stopService'].get('ret') != 'true':
        reject(CODE_PLACEMENT, 'SVC-014b stopService: ret=%r, want "true" or an explicit refusal'
               % ops['stopService'].get('ret'))
    if not (connects and all(e.get('binder') for e in connects)):
        reject(CODE_PLACEMENT, 'SVC-014b: no binder-bearing onServiceConnected for the hosted remote')

    # The separation proof itself: the service answered with its own identity,
    # while the binding was live, and it is a different process than the caller.
    if not identity_rows:
        reject(CODE_IDENTITY_MISSING,
               'SVC-014b: no remote-execution evidence - the service never answered the identity '
               'transaction (remote callbacks absent only proves it did not run in the caller\'s '
               'log, not that another process served the placement)')
    if len(identity_rows) > 1:
        reject(CODE_IDENTITY_DUPLICATE, 'SVC-014b: %d identity replies, want exactly 1' % len(identity_rows))
    first_identity = names.index('remoteIdentity')
    if not (names.index('bindService') < first_identity < names.index('stopService')):
        reject(CODE_IDENTITY_ORDER, 'SVC-014b: the identity reply is outside the live binding '
               '(bindService .. stopService)')
    pid, client_pid, process = _identity(identity_rows[0])
    if pid == client_pid:
        reject(CODE_IDENTITY_SAME_PROCESS,
               'SVC-014b: the placement was served by pid %d - the caller\'s own process (pid %d); '
               'it is not isolated' % (pid, client_pid))
    if process == 'unknown':
        reject(CODE_IDENTITY_MALFORMED,
               'SVC-014b: the serving process name is "unknown" (pid %d); the process name is part '
               'of the required evidence' % pid)


def _placed(ret):
    return bool(ret) and not str(ret).startswith('exception:')


def _refused(event):
    return str(event.get('ret', '')).startswith('exception:') and event.get('exception') == REFUSAL


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: verify_unsupported_remote.py results.json')
        with open(sys.argv[1]) as stream:
            verify(json.load(stream))
    except Reject as error:
        print('REJECT %s %s' % (error.code, error.detail))
        sys.exit(EXIT_REJECT)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print('REJECT %s unsupported remote: %s' % (CODE_EVIDENCE, error))
        sys.exit(EXIT_REJECT)
    print('OK remote placement contract (spawned child proved by pid/process separation, or explicit '
          'refusal; NOT Android remote-service support)')
