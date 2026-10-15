/* SPDX-License-Identifier: GPL-3.0-only */
/* Gate-2 service probe (lane B), row S06b: target of the binding a Service issues from its own context. */
package org.atl.tests.service;

public class SvcLeakTarget extends ProbeService {

    protected String scenario() {
        return "S06";
    }
}