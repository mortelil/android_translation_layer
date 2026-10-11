/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.split;

/**
 * APP-COMPAT case F (splits/APKM) probe helper.
 *
 * This class lives in the BASE dex only. Its static initializer prints a
 * deterministic line so that "the base class was loaded / the base dex was
 * compiled" is observable independently of the native library. The native
 * function it declares is provided by libsplitprobe.so, which is shipped only
 * inside the ABI-matching config split; loading it therefore proves native
 * extraction from that split.
 */
public final class ProbeLoader {
    /** Emitted once, when this (base-dex) class is first initialized. */
    public static final String STATIC_INIT_MARKER = "SPLITPROBE_LOADER_STATIC_INIT";

    private static volatile boolean initialized;

    static {
        System.out.println(STATIC_INIT_MARKER);
        System.out.flush();
        initialized = true;
    }

    private ProbeLoader() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Loads libsplitprobe.so; never throws, records the failure reason. */
    public static String loadLibrary() {
        try {
            System.loadLibrary("splitprobe");
            return null;
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    /** Implemented by libsplitprobe.so shipped in the x86_64 config split. */
    public static native int splitprobeValue();
}
