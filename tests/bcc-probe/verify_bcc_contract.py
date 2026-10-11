#!/usr/bin/env python3
"""verify_bcc_contract.py - fail-closed comparator for B-CALLBACKS evidence.

Compares one Android reference document and one ATL candidate document produced
by the SAME frozen probe APK (apk.sha256 must match). Exit codes:

  0  accept   -> prints an ``OK B-CALLBACKS ...`` line
  1  reject   -> prints ``REJECT <CODE> <reason>``
  2  usage / internal error

Exact (spec-pinned) surfaces: the type contract, the seven TRIM_MEMORY_*
constant values, the registration surface, and the controlled DIRECT dispatch
scenario S1 (ordered trace, callback thread). The non-deterministic platform
trigger is compared as a state: DISPATCH_OBSERVED vs NOT_TRIGGERED. A side that
emits NOT_TRIGGERED must have an empty trace (no fabricated callbacks); if BOTH
sides observe dispatch their traces must agree. The trigger state itself is
allowed to differ (a real Linux-side event may not exist) and is reported as
dispatch coverage, not as a pass.
"""
import json
import os
import sys

BEGIN = "BCC_JSON_BEGIN"
END = "BCC_JSON_END"
DONE_MARKER = "BCC_DONE"
READY_MARKER = "BCC_READY"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "B-CALLBACKS"
TRIGGER = "uimode_night"

TRIM_CONSTANTS = {
    "TRIM_MEMORY_COMPLETE": 80,
    "TRIM_MEMORY_MODERATE": 60,
    "TRIM_MEMORY_BACKGROUND": 40,
    "TRIM_MEMORY_UI_HIDDEN": 20,
    "TRIM_MEMORY_RUNNING_CRITICAL": 15,
    "TRIM_MEMORY_RUNNING_LOW": 10,
    "TRIM_MEMORY_RUNNING_MODERATE": 5,
}

TYPE_CONTRACT_BOOLS = {
    "activityInstanceofCC2": True,
    "applicationInstanceofCC2": True,
    "applicationContextInstanceofCC2": True,
    "applicationClassAssignableFromCC2": True,
    "cc2ExtendsCC": True,
    "ccIsCC2": False,
}

TYPE_CONTRACT_METHODS = {
    "cc.onConfigurationChanged": True,
    "cc.onLowMemory": True,
    "cc2.onConfigurationChanged": True,
    "cc2.onLowMemory": True,
    "cc2.onTrimMemory": True,
}

REGISTRATION_BOOLS = {
    "contextRegister": True,
    "contextUnregister": True,
    "contextWrapperRegister": True,
    "contextWrapperUnregister": True,
    "multipleRegisterThrew": False,
    "unregisterThrew": False,
}

DIRECT_SCENARIO = "S1"
DIRECT_THREADS = ["main"]

DIRECT_TRACE = [
    "@register:L1,L2,L3",
    "@onTrimMemory:40",
    "L1:onTrimMemory:40", "L2:onTrimMemory:40", "L3:onTrimMemory:40",
    "@onTrimMemory:10",
    "L1:onTrimMemory:10", "L2:onTrimMemory:10", "L3:onTrimMemory:10",
    "@onLowMemory",
    "L1:onLowMemory", "L2:onLowMemory", "L3:onLowMemory",
    "@onConfigurationChanged:orientation=2:uiMode=32",
    "L1:onConfigurationChanged:orientation=2:uiMode=32",
    "L2:onConfigurationChanged:orientation=2:uiMode=32",
    "L3:onConfigurationChanged:orientation=2:uiMode=32",
    "@unregister:L2",
    "@onTrimMemory:60",
    "L1:onTrimMemory:60", "L3:onTrimMemory:60",
    "@unregister:L1,L3",
    "@onTrimMemory:80",
]

PLATFORM_STATES = ("DISPATCH_OBSERVED", "NOT_TRIGGERED")


class Reject(Exception):
    def __init__(self, code, reason):
        super().__init__("%s %s" % (code, reason))
        self.code = code
        self.reason = reason


class UsageError(Exception):
    pass


def reject(code, reason):
    raise Reject(code, reason)


def usage(message):
    raise UsageError(message)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def is_bool(value):
    return isinstance(value, bool)


def is_str(value):
    return isinstance(value, str) and value != ""


# --- loading --------------------------------------------------------------

def load_side(path, label, allow_bare):
    if not isinstance(path, str) or not path:
        reject("MISSING_EVIDENCE", "%s evidence path is missing" % label)
    if not os.path.isfile(path):
        reject("MISSING_EVIDENCE", "%s evidence file not found: %s" % (label, path))
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except Exception as exc:  # noqa: BLE001
        reject("UNREADABLE_EVIDENCE", "%s evidence unreadable: %s" % (label, exc))
    if not text.strip():
        reject("EMPTY_EVIDENCE", "%s evidence is empty" % label)

    lines = text.splitlines()
    begin_lines = [i for i, line in enumerate(lines) if line.strip() == BEGIN]
    end_lines = [i for i, line in enumerate(lines) if line.strip() == END]
    sentinel_present = bool(begin_lines or end_lines)
    if sentinel_present:
        if len(begin_lines) != 1 or len(end_lines) != 1:
            reject("SENTINEL_INVALID",
                   "%s evidence has %d BEGIN and %d END sentinel lines"
                   % (label, len(begin_lines), len(end_lines)))
        if begin_lines[0] > end_lines[0]:
            reject("SENTINEL_INVALID", "%s sentinel END precedes BEGIN" % label)
        body = "\n".join(lines[begin_lines[0] + 1:end_lines[0]])
    else:
        if not allow_bare:
            reject("MISSING_SENTINEL",
                   "%s evidence is not sentinel-wrapped (%s/%s absent)"
                   % (label, BEGIN, END))
        body = text

    body = body.strip()
    if not body:
        reject("EMPTY_EVIDENCE", "%s evidence body is empty" % label)
    try:
        doc = json.loads(body)
    except Exception as exc:  # noqa: BLE001
        reject("JSON_PARSE", "%s evidence body is not valid JSON: %s" % (label, exc))
    if not isinstance(doc, dict):
        reject("NOT_AN_OBJECT", "%s evidence JSON is not an object" % label)
    return doc


def require_key(doc, key, label):
    if key not in doc:
        reject("MISSING_FIELD", "%s evidence missing top-level key %r" % (label, key))
    return doc[key]


def require_object(doc, key, label):
    value = require_key(doc, key, label)
    if not isinstance(value, dict):
        reject("MISSING_FIELD", "%s %s is not an object" % (label, key))
    return value


def require_bool(container, key, label, where):
    if key not in container:
        reject("MISSING_FIELD", "%s %s missing %s" % (label, where, key))
    value = container[key]
    if not is_bool(value):
        reject("INVALID_NUMBER", "%s %s.%s is not a boolean: %r"
               % (label, where, key, value))
    return value


def require_int(container, key, label, where):
    if key not in container:
        reject("MISSING_FIELD", "%s %s missing %s" % (label, where, key))
    value = container[key]
    if not is_int(value):
        reject("INVALID_NUMBER", "%s %s.%s is not an int: %r"
               % (label, where, key, value))
    return value


def require_str(container, key, label, where):
    if key not in container:
        reject("MISSING_FIELD", "%s %s missing %s" % (label, where, key))
    value = container[key]
    if not is_str(value):
        reject("MISSING_FIELD", "%s %s.%s is not a non-empty string: %r"
               % (label, where, key, value))
    return value


def require_str_list(container, key, label, where):
    if key not in container:
        reject("MISSING_FIELD", "%s %s missing %s" % (label, where, key))
    value = container[key]
    if not isinstance(value, list):
        reject("MISSING_FIELD", "%s %s.%s is not a list" % (label, where, key))
    for item in value:
        if not is_str(item):
            reject("INVALID_NUMBER",
                   "%s %s.%s has a non-string entry: %r" % (label, where, key, item))
    return value


# --- validation -----------------------------------------------------------

def validate_doc(doc, label):
    task_id = require_key(doc, "task_id", label)
    if not is_str(task_id):
        reject("MISSING_FIELD", "%s task_id is not a non-empty string" % label)
    if task_id != TASK_ID:
        reject("WRONG_TASK", "%s task_id is %r, expected %r" % (label, task_id, TASK_ID))

    run_id = require_key(doc, "run_id", label)
    if not is_str(run_id):
        reject("MISSING_RUN_ID", "%s run_id is not a non-empty string" % label)

    thread = require_key(doc, "thread", label)
    if thread != "main":
        reject("WRONG_THREAD",
               "%s thread is %r, expected the probe main thread" % (label, thread))

    status = require_key(doc, "status", label)
    if status != "OK":
        reject("STATUS_NOT_OK", "%s status is %r" % (label, status))

    exceptions = require_key(doc, "exceptions", label)
    if not isinstance(exceptions, list):
        reject("MISSING_FIELD", "%s exceptions is not a list" % label)
    if exceptions:
        reject("EXCEPTIONS_PRESENT",
               "%s reported probe exceptions: %r" % (label, exceptions))

    completion = require_object(doc, "completion", label)
    if completion.get("marker") != DONE_MARKER:
        reject("MISSING_FIELD", "%s completion.marker is not %r" % (label, DONE_MARKER))

    config = require_object(doc, "config", label)
    trigger = require_str(config, "trigger", label, "config")
    if trigger != TRIGGER:
        reject("CONFIG_MISMATCH",
               "%s config.trigger is %r, expected %r" % (label, trigger, TRIGGER))
    wait_ms = require_int(config, "waitMs", label, "config")
    if wait_ms <= 0:
        reject("INVALID_NUMBER", "%s config.waitMs must be > 0 (got %r)"
               % (label, wait_ms))
    require_int(config, "targetSdk", label, "config")
    require_int(config, "minSdk", label, "config")

    apk = require_object(doc, "apk", label)
    if not is_str(apk.get("sha256")):
        reject("MISSING_FIELD", "%s apk.sha256 is missing" % label)

    type_contract = require_object(doc, "type_contract", label)
    for key in TYPE_CONTRACT_BOOLS:
        require_bool(type_contract, key, label, "type_contract")
    methods = type_contract.get("methods")
    if not isinstance(methods, dict):
        reject("MISSING_FIELD", "%s type_contract.methods is not an object" % label)
    for key in TYPE_CONTRACT_METHODS:
        require_bool(methods, key, label, "type_contract.methods")

    constants = require_object(doc, "constants", label)
    for key in TRIM_CONSTANTS:
        require_int(constants, key, label, "constants")

    registration = require_object(doc, "registration", label)
    for key in REGISTRATION_BOOLS:
        require_bool(registration, key, label, "registration")
    require_str(registration, "registerTarget", label, "registration")

    direct = require_object(doc, "direct_dispatch", label)
    require_str(direct, "state", label, "direct_dispatch")
    require_str(direct, "scenario", label, "direct_dispatch")
    require_str_list(direct, "threads", label, "direct_dispatch")
    require_str_list(direct, "trace", label, "direct_dispatch")

    platform = require_object(doc, "platform_trigger", label)
    state = require_str(platform, "state", label, "platform_trigger")
    if state not in PLATFORM_STATES:
        reject("PLATFORM_STATE_INVALID",
               "%s platform_trigger.state is %r, expected one of %r"
               % (label, state, list(PLATFORM_STATES)))
    ptrigger = require_str(platform, "trigger", label, "platform_trigger")
    if ptrigger != TRIGGER:
        reject("PLATFORM_TRIGGER_MISMATCH",
               "%s platform_trigger.trigger is %r, expected %r"
               % (label, ptrigger, TRIGGER))
    require_int(platform, "waitedMs", label, "platform_trigger")
    require_str_list(platform, "trace", label, "platform_trigger")
    return doc


# --- spec + cross-side checks --------------------------------------------

def check_type_contract(doc, label):
    tc = doc["type_contract"]
    for key, want in TYPE_CONTRACT_BOOLS.items():
        if tc[key] != want:
            reject("TYPE_CONTRACT_MISMATCH",
                   "%s type_contract.%s=%r, AOSP-derived expectation is %r"
                   % (label, key, tc[key], want))
    for key, want in TYPE_CONTRACT_METHODS.items():
        if tc["methods"][key] != want:
            reject("TYPE_CONTRACT_MISMATCH",
                   "%s type_contract.methods.%s=%r, expectation is %r"
                   % (label, key, tc["methods"][key], want))


def check_constants(doc, label):
    for key, want in TRIM_CONSTANTS.items():
        value = doc["constants"][key]
        if value != want:
            reject("CONSTANT_MISMATCH",
                   "%s constants.%s=%r, expected %d" % (label, key, value, want))


def check_registration(doc, label):
    reg = doc["registration"]
    for key, want in REGISTRATION_BOOLS.items():
        if reg[key] != want:
            reject("REGISTRATION_MISMATCH",
                   "%s registration.%s=%r, expectation is %r" % (label, key, reg[key], want))
    if reg["registerTarget"] != "getApplicationContext":
        reject("REGISTRATION_MISMATCH",
               "%s registration.registerTarget=%r, expected 'getApplicationContext'"
               % (label, reg["registerTarget"]))


def check_direct(doc, label):
    direct = doc["direct_dispatch"]
    if direct["state"] != "DISPATCH_OBSERVED":
        reject("DIRECT_DISPATCH_STATE",
               "%s direct_dispatch.state=%r; a controlled direct dispatch is "
               "deterministic and must be observed" % (label, direct["state"]))
    if direct["scenario"] != DIRECT_SCENARIO:
        reject("DIRECT_SCENARIO",
               "%s direct_dispatch.scenario=%r, expected %r"
               % (label, direct["scenario"], DIRECT_SCENARIO))
    if direct["threads"] != DIRECT_THREADS:
        reject("DIRECT_THREAD_MISMATCH",
               "%s direct_dispatch.threads=%r, expected %r (callbacks must run on "
               "the calling main thread)" % (label, direct["threads"], DIRECT_THREADS))

    trace = direct["trace"]
    if not trace:
        reject("DIRECT_TRACE_MISSING", "%s direct_dispatch.trace is empty" % label)
    if len(set(trace)) != len(trace):
        dupes = sorted({e for e in trace if trace.count(e) > 1})
        reject("DIRECT_TRACE_DUPLICATE",
               "%s direct_dispatch.trace has duplicate event(s): %r" % (label, dupes))
    if trace == list(DIRECT_TRACE):
        return
    if trace == list(DIRECT_TRACE[:len(trace)]) and len(trace) < len(DIRECT_TRACE):
        reject("DIRECT_TRACE_TRUNCATED",
               "%s direct_dispatch.trace is a truncated prefix (%d of %d events)"
               % (label, len(trace), len(DIRECT_TRACE)))
    if sorted(trace) == sorted(DIRECT_TRACE):
        reject("DIRECT_TRACE_REORDERED",
               "%s direct_dispatch.trace has the right events in the wrong order"
               % label)
    reject("DIRECT_TRACE_MISMATCH",
           "%s direct_dispatch.trace does not match the frozen S1 scenario" % label)


def check_platform(doc, label):
    platform = doc["platform_trigger"]
    state = platform["state"]
    trace = platform["trace"]
    if state == "NOT_TRIGGERED" and trace:
        reject("PLATFORM_TRACE_INCONSISTENT",
               "%s platform_trigger is NOT_TRIGGERED but reports %d callback(s): %r"
               % (label, len(trace), trace))
    if state == "DISPATCH_OBSERVED" and not trace:
        reject("PLATFORM_TRACE_INCONSISTENT",
               "%s platform_trigger is DISPATCH_OBSERVED but its trace is empty"
               % label)


def main(argv):
    if len(argv) < 3:
        usage("usage: verify_bcc_contract.py <android_json> <candidate_json> "
              "[--allow-bare]")
    allow_bare = False
    paths = []
    for arg in argv[1:]:
        if arg == "--allow-bare":
            allow_bare = True
        elif arg.startswith("-"):
            usage("unknown option: %s" % arg)
        else:
            paths.append(arg)
    if len(paths) != 2:
        usage("expected exactly two evidence paths (android, candidate)")

    android = load_side(paths[0], "android", allow_bare)
    candidate = load_side(paths[1], "candidate", allow_bare)

    validate_doc(android, "android")
    validate_doc(candidate, "candidate")

    if android["apk"]["sha256"] != candidate["apk"]["sha256"]:
        reject("APK_MISMATCH",
               "android and candidate were not produced by the same frozen APK "
               "(%s vs %s)" % (android["apk"]["sha256"], candidate["apk"]["sha256"]))
    if android["run_id"] == candidate["run_id"]:
        reject("WRONG_OWNER",
               "android and candidate share run_id %r: the same capture was not "
               "independently produced per role" % android["run_id"])

    for doc, label in ((android, "android"), (candidate, "candidate")):
        check_type_contract(doc, label)
        check_constants(doc, label)
        check_registration(doc, label)
        check_direct(doc, label)
        check_platform(doc, label)

    # Cross-side equality of the exact surfaces.
    if android["type_contract"] != candidate["type_contract"]:
        reject("TYPE_CONTRACT_DIVERGENCE",
               "type_contract differs: android=%r candidate=%r"
               % (android["type_contract"], candidate["type_contract"]))
    if android["constants"] != candidate["constants"]:
        reject("CONSTANT_MISMATCH",
               "constants differ: android=%r candidate=%r"
               % (android["constants"], candidate["constants"]))
    if android["registration"] != candidate["registration"]:
        reject("REGISTRATION_MISMATCH",
               "registration differs: android=%r candidate=%r"
               % (android["registration"], candidate["registration"]))
    if android["direct_dispatch"]["trace"] != candidate["direct_dispatch"]["trace"]:
        reject("DIRECT_TRACE_DIVERGENCE",
               "direct_dispatch.trace differs between android and candidate")
    if android["direct_dispatch"]["threads"] != candidate["direct_dispatch"]["threads"]:
        reject("DIRECT_THREAD_MISMATCH",
               "direct_dispatch.threads differs: android=%r candidate=%r"
               % (android["direct_dispatch"]["threads"],
                  candidate["direct_dispatch"]["threads"]))

    a_state = android["platform_trigger"]["state"]
    c_state = candidate["platform_trigger"]["state"]
    if a_state == "DISPATCH_OBSERVED" and c_state == "DISPATCH_OBSERVED":
        if sorted(android["platform_trigger"]["trace"]) != \
                sorted(candidate["platform_trigger"]["trace"]):
            reject("PLATFORM_TRACE_DIVERGENCE",
                   "both sides observed platform dispatch but the traces differ: "
                   "android=%r candidate=%r"
                   % (android["platform_trigger"]["trace"],
                      candidate["platform_trigger"]["trace"]))

    print("OK B-CALLBACKS: same frozen APK %s; type contract + 7 TRIM_MEMORY "
          "constants + registration exact; direct dispatch scenario S1 exact "
          "(%d events, thread %s); platform dispatch coverage: android=%s "
          "candidate=%s"
          % (android["apk"]["sha256"][:12], len(DIRECT_TRACE),
             ",".join(DIRECT_THREADS), a_state, c_state))
    return EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except UsageError as exc:
        print("ERROR internal usage: %s" % exc)
        sys.exit(EXIT_ERROR)
    except Reject as exc:
        print("REJECT %s %s" % (exc.code, exc.reason))
        sys.exit(EXIT_REJECT)
    except Exception as exc:  # noqa: BLE001
        print("ERROR internal %s: %s" % (type(exc).__name__, exc))
        sys.exit(EXIT_ERROR)