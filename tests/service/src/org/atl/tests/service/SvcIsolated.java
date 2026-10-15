/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Gate-2 service probe (lane B), row S14a: android:isolatedProcess="true" component.
 *
 * It must never run: ATLServiceController.resolvePlacement refuses it
 * synchronously with UnsupportedOperationException("ATL does not support remote or
 * isolated services: " + className) before any record/instance exists, and the
 * probe asserts that exact type and message plus the absence of every callback
 * of this class. Nothing here is expected to be executed.
 */
package org.atl.tests.service;

public class SvcIsolated extends ProbeService {

    protected String scenario() {
        return "S14a";
    }
}