#!/usr/bin/env python3
"""verify_uid_semantics.py - fail-closed comparator for the J-UID evidence.

Usage:
    verify_uid_semantics.py <android_json> <candidate_json> [--allow-bare]

Each input is the captured J-UID probe output for one runtime. Captured stdout
is sentinel-wrapped::

    JUID_JSON_BEGIN
    { ...single JSON object... }
    JUID_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too. Default is the strict
captured-stdout form, i.e. a sentinel is required.)

The first positional argument is the Android emulator reference, the second the
ATL candidate. Both documents must be complete and identified before any
semantic comparison:

* non-empty, sentinel-wrapped (exactly one BEGIN/END pair), one JSON object;
* frozen task id ``J-UID`` and a non-empty, *distinct* ``run_id`` per side
  (identical run ids mean the same capture was reused for both roles);
* ``thread`` == ``main`` (observations taken on the probe's main thread);
* ``status`` OK with an empty ``exceptions`` list;
* ``config.valueSet`` equal to the frozen value set, in order;
* ``apk.sha256`` present, equal on both sides (same frozen APK);
* ``constants.firstApplicationUid`` = 10000, ``.lastApplicationUid`` = 19999;
* exactly one ``predicates`` entry per frozen uid, in order, no duplicates, and
  each of ``isApplicationUid`` / ``isApp`` / ``isIsolated`` a real boolean.

Comparison rules (see CONTRACT.md):

* ``predicates``: candidate and reference compared EXACTLY, row by row; each row
  is also checked against the AOSP-derived expected matrix (so two identically
  wrong sides cannot pass).
* ``identity``: ATL reports a logical application UID, Android a real sandbox
  UID, so the raw ``myUid`` is NOT compared across sides. Instead each side must
  be self-consistent (structural rule) and the candidate must additionally
  report the pinned ATL value ``myUid == 10000`` / ``appId == 10000`` /
  ``userId == 0``.

Exit status:
    0  accepted: valid, identified, complete, matching pair
    1  rejected: invalid/incomplete/mismatching evidence (``REJECT <CODE> <reason>``)
    2  usage / internal error (never surfaced as a semantic rejection)

Rejection lines are machine-readable: ``REJECT <CODE> <reason>``.
"""
import json
import os
import sys

BEGIN = "JUID_JSON_BEGIN"
END = "JUID_JSON_END"
DONE_MARKER = "JUID_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "J-UID"
PER_USER_RANGE = 100000
FIRST_APPLICATION_UID = 10000
LAST_APPLICATION_UID = 19999
FIRST_ISOLATED_UID = 99000
LAST_ISOLATED_UID = 99999
FIRST_APP_ZYGOTE_ISOLATED_UID = 90000
LAST_APP_ZYGOTE_ISOLATED_UID = 98999

VALUE_SET = [0, 1000, 1001, 2000, 9999, 10000, 19999, 20000,
             90000, 98999, 99000, 99999, 100000, -1, 2147483647]

# AOSP-derived expected (isApplicationUid, isApp, isIsolated) for VALUE_SET.
def _app(uid):
    if uid <= 0:
        return False
    app_id = uid % PER_USER_RANGE
    return FIRST_APPLICATION_UID <= app_id <= LAST_APPLICATION_UID


def _isolated(uid):
    if uid <= 0:
        return False
    app_id = uid % PER_USER_RANGE
    return (FIRST_ISOLATED_UID <= app_id <= LAST_ISOLATED_UID
            or FIRST_APP_ZYGOTE_ISOLATED_UID <= app_id <= LAST_APP_ZYGOTE_ISOLATED_UID)


EXPECTED_MATRIX = {
    uid: {"isApplicationUid": _app(uid), "isApp": _app(uid), "isIsolated": _isolated(uid)}
    for uid in VALUE_SET
}

BOOL_FIELDS = ("isApplicationUid", "isApp", "isIsolated")


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


def load_side(path, label, allow_bare):
    """Return (doc, role). Fail closed on any structural problem."""
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


def validate_doc(doc, label):
    task_id = require_key(doc, "task_id", label)
    if not isinstance(task_id, str) or not task_id:
        reject("MISSING_FIELD", "%s task_id is not a non-empty string" % label)
    if task_id != TASK_ID:
        reject("WRONG_TASK", "%s task_id is %r, expected %r" % (label, task_id, TASK_ID))

    run_id = require_key(doc, "run_id", label)
    if not isinstance(run_id, str) or not run_id:
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

    config = require_key(doc, "config", label)
    if not isinstance(config, dict):
        reject("MISSING_FIELD", "%s config is not an object" % label)
    value_set = config.get("valueSet")
    if not isinstance(value_set, list) or not all(is_int(v) for v in value_set):
        reject("INVALID_NUMBER", "%s config.valueSet is not an integer list" % label)
    if value_set != VALUE_SET:
        reject("CONFIG_MISMATCH",
               "%s config.valueSet does not match the frozen value set: %r"
               % (label, value_set))

    apk = require_key(doc, "apk", label)
    if not isinstance(apk, dict) or not isinstance(apk.get("sha256"), str) \
            or not apk.get("sha256"):
        reject("MISSING_FIELD", "%s apk.sha256 is missing" % label)

    constants = require_key(doc, "constants", label)
    if not isinstance(constants, dict):
        reject("MISSING_FIELD", "%s constants is not an object" % label)
    for key, expected in (("firstApplicationUid", FIRST_APPLICATION_UID),
                          ("lastApplicationUid", LAST_APPLICATION_UID)):
        value = constants.get(key)
        if not is_int(value):
            reject("MISSING_FIELD", "%s constants.%s is missing/not an int" % (label, key))
        if value != expected:
            reject("CONSTANT_MISMATCH",
                   "%s constants.%s is %r, expected %d" % (label, key, value, expected))
    for key, expected in (("firstIsolatedUid", FIRST_ISOLATED_UID),
                          ("lastIsolatedUid", LAST_ISOLATED_UID),
                          ("firstAppZygoteIsolatedUid", FIRST_APP_ZYGOTE_ISOLATED_UID),
                          ("lastAppZygoteIsolatedUid", LAST_APP_ZYGOTE_ISOLATED_UID),
                          ("perUserRange", PER_USER_RANGE)):
        value = constants.get(key)
        if value is None:
            continue  # genuinely hidden on this side; documented, not fabricated
        if not is_int(value):
            reject("INVALID_NUMBER", "%s constants.%s is not an int" % (label, key))
        if value != expected:
            reject("CONSTANT_MISMATCH",
                   "%s constants.%s is %r, expected %d" % (label, key, value, expected))

    identity = require_key(doc, "identity", label)
    if not isinstance(identity, dict):
        reject("IDENTITY_MISSING", "%s identity is not an object" % label)
    for key in ("myUid", "userId", "appId"):
        if not is_int(identity.get(key)):
            reject("IDENTITY_MISSING", "%s identity.%s is missing/not an int" % (label, key))
    for key in ("isApplicationUid_myUid", "isApp_myUid", "isIsolated_noarg"):
        if not is_bool(identity.get(key)):
            reject("IDENTITY_MISSING",
                   "%s identity.%s is missing/not a boolean" % (label, key))
    return doc


def validate_predicates(doc, label):
    preds = require_key(doc, "predicates", label)
    if not isinstance(preds, list) or not preds:
        reject("MISSING_INPUT", "%s predicates is missing/empty" % label)
    seen = []
    for row in preds:
        if not isinstance(row, dict):
            reject("MISSING_FIELD", "%s predicates row is not an object" % label)
        uid = row.get("uid")
        if not is_int(uid):
            reject("INVALID_NUMBER", "%s predicates row has a non-integer uid: %r"
                   % (label, uid))
        seen.append(uid)
        for field in BOOL_FIELDS:
            if field not in row:
                reject("MISSING_FIELD",
                       "%s predicates row uid=%d missing %s" % (label, uid, field))
            value = row[field]
            if value is None:
                reject("UNREACHABLE_SURFACE",
                       "%s predicates uid=%d %s is unreachable (null)"
                       % (label, uid, field))
            if not is_bool(value):
                reject("INVALID_NUMBER",
                       "%s predicates uid=%d %s is not a boolean: %r"
                       % (label, uid, field, value))
    expected = list(VALUE_SET)
    if len(seen) != len(set(seen)):
        dupes = sorted({u for u in seen if seen.count(u) > 1})
        reject("DUPLICATE_INPUT", "%s predicates has duplicate uid(s): %r" % (label, dupes))
    if set(seen) != set(expected):
        missing = sorted(set(expected) - set(seen))
        extra = sorted(set(seen) - set(expected))
        reject("MISSING_INPUT",
               "%s predicates value set wrong (missing %r, extra %r)"
               % (label, missing, extra))
    if seen != expected:
        reject("REORDERED_INPUT",
               "%s predicates are out of the frozen order" % label)
    return {row["uid"]: row for row in preds}


def check_spec_matrix(rows, label):
    for uid in VALUE_SET:
        row = rows[uid]
        want = EXPECTED_MATRIX[uid]
        for field in BOOL_FIELDS:
            if row[field] != want[field]:
                reject("SPEC_MISMATCH",
                       "%s uid=%d %s=%r, AOSP-derived expectation is %r"
                       % (label, uid, field, row[field], want[field]))


def check_identity(doc, label, pin):
    ident = doc["identity"]
    my_uid = ident["myUid"]
    user_id = ident["userId"]
    app_id = ident["appId"]

    def structural(ok, message):
        if not ok:
            reject("IDENTITY_STRUCTURE", "%s identity: %s" % (label, message))

    structural(my_uid > 0, "myUid must be > 0 (got %r)" % my_uid)
    structural(ident["isApplicationUid_myUid"] is True,
               "isApplicationUid(myUid) must be true")
    structural(ident["isApp_myUid"] == ident["isApplicationUid_myUid"],
               "isApp(myUid) must equal isApplicationUid(myUid)")
    structural(ident["isIsolated_noarg"] is False,
               "Process.isIsolated() must be false")
    if ident.get("isIsolatedUid_myUid") is not None:
        structural(ident["isIsolatedUid_myUid"] is False,
                   "isIsolatedUid(myUid) must be false")
    if ident.get("isIsolated_int_myUid") is not None:
        structural(ident["isIsolated_int_myUid"] is False,
                   "Process.isIsolated(myUid) must be false")
    if ident.get("isIsolated_uh_myUid") is not None:
        structural(ident["isIsolated_uh_myUid"] is False,
                   "UserHandle.isIsolated(myUid) must be false")
    if ident.get("isCore_myUid") is not None:
        structural(ident["isCore_myUid"] is False,
                   "UserHandle.isCore(myUid) must be false")
    structural(user_id == 0, "getUserId(myUid) must be 0 (got %r)" % user_id)
    structural(app_id == my_uid % PER_USER_RANGE,
               "getAppId(myUid) must be myUid %% %d (got %r for myUid=%r)"
               % (PER_USER_RANGE, app_id, my_uid))
    structural(FIRST_APPLICATION_UID <= app_id <= LAST_APPLICATION_UID,
               "getAppId(myUid) must be in [%d,%d] (got %r)"
               % (FIRST_APPLICATION_UID, LAST_APPLICATION_UID, app_id))

    if pin:
        if my_uid != FIRST_APPLICATION_UID:
            reject("IDENTITY_CANDIDATE_PIN",
                   "%s identity: pinned ATL myUid must be %d (got %r)"
                   % (label, FIRST_APPLICATION_UID, my_uid))
        if app_id != FIRST_APPLICATION_UID:
            reject("IDENTITY_CANDIDATE_PIN",
                   "%s identity: pinned ATL appId must be %d (got %r)"
                   % (label, FIRST_APPLICATION_UID, app_id))
        if user_id != 0:
            reject("IDENTITY_CANDIDATE_PIN",
                   "%s identity: pinned ATL userId must be 0 (got %r)" % (label, user_id))


def main(argv):
    if len(argv) < 3:
        usage("usage: verify_uid_semantics.py <android_json> <candidate_json> "
              "[--allow-bare]")
    args = argv[1:]
    allow_bare = False
    paths = []
    for arg in args:
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

    a_preds = validate_predicates(android, "android")
    c_preds = validate_predicates(candidate, "candidate")

    # Cross-side identity of the evidence itself.
    if android["apk"]["sha256"] != candidate["apk"]["sha256"]:
        reject("APK_MISMATCH",
               "android and candidate were not produced by the same frozen APK "
               "(%s vs %s)" % (android["apk"]["sha256"], candidate["apk"]["sha256"]))
    if android["run_id"] == candidate["run_id"]:
        reject("WRONG_OWNER",
               "android and candidate share run_id %r: the same capture was not "
               "independently produced per role" % android["run_id"])

    for key in ("firstApplicationUid", "lastApplicationUid", "firstIsolatedUid",
                "lastIsolatedUid", "firstAppZygoteIsolatedUid",
                "lastAppZygoteIsolatedUid", "perUserRange"):
        a = android["constants"].get(key)
        c = candidate["constants"].get(key)
        if a is not None and c is not None and a != c:
            reject("CONSTANT_MISMATCH",
                   "constants.%s differs: android=%r candidate=%r" % (key, a, c))

    # Cross-side predicate equality (the primary contract).
    for uid in VALUE_SET:
        for field in BOOL_FIELDS:
            if a_preds[uid][field] != c_preds[uid][field]:
                reject("PREDICATE_MISMATCH",
                       "uid=%d %s: android=%r candidate=%r"
                       % (uid, field, a_preds[uid][field], c_preds[uid][field]))

    # Both sides must match the AOSP-derived matrix (guards against two equally
    # wrong sides and pins the reference itself).
    check_spec_matrix(a_preds, "android")
    check_spec_matrix(c_preds, "candidate")

    check_identity(android, "android", pin=False)
    check_identity(candidate, "candidate", pin=True)

    print("OK J-UID: android and candidate agree on %d inputs x %d predicates; "
          "identity structural rule satisfied (android myUid=%s, candidate myUid=%s)"
          % (len(VALUE_SET), len(BOOL_FIELDS),
             android["identity"]["myUid"], candidate["identity"]["myUid"]))
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
