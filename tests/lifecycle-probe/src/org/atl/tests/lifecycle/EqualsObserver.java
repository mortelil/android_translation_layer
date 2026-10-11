/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.lifecycle;

/**
 * Observer with a non-identity {@code equals}: two distinct instances with the
 * same key are equal. Used to test that
 * {@code Application.unregisterActivityLifecycleCallbacks} removes the first
 * list element equal to the argument (java.util.ArrayList.remove uses
 * {@code argument.equals(element)}), not merely the identical instance.
 */
final class EqualsObserver extends Observer {
    private final String key;

    EqualsObserver(String id, String key) {
        super(id);
        this.key = key;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof EqualsObserver) && key.equals(((EqualsObserver) o).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}