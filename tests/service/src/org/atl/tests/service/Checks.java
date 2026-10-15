/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B): assertions of one acceptance row.
 *
 * A row collects problems while its steps run and is verdicted once, at its last
 * step, by ControlActivity.verdict(): a row with no problem prints
 * `SVC <id> PASS`, otherwise `SVC <id> FAIL <problems>`. Every message names the
 * expected and observed value or sequence, so a FAIL is self-describing
 * evidence (the ticket requires the production path's actual output).
 */
package org.atl.tests.service;

import java.util.ArrayList;

final class Checks {

    private final String id;
    private final ArrayList<String> problems = new ArrayList<String>();

    Checks(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            problems.add(what + " expected[" + expected + "] observed[" + actual + "]");
        }
    }

    void eq(String what, long expected, long actual) {
        if (expected != actual) {
            problems.add(what + " expected[" + expected + "] observed[" + actual + "]");
        }
    }

    void ok(String what, boolean condition) {
        if (!condition) {
            problems.add(what);
        }
    }

    void fail(String problem) {
        problems.add(problem);
    }

    boolean passed() {
        return problems.isEmpty();
    }

    String report() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(problems.get(i));
        }
        return sb.toString();
    }
}