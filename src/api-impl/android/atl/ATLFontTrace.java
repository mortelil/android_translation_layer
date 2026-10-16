// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

/**
 * Opt-in trace of the resource-font resolution chain (Typeface / Font$Builder /
 * Resources.openRawResource*), enabled by setting ATL_DEBUG_FONT.
 *
 * Every framework entry point an app can use to obtain a Typeface from a bundled font
 * resource logs the arguments it received and the native handle it produced (handle 0
 * means the caller receives a fallback/default typeface). That makes a silent
 * degradation to {@link android.graphics.Typeface#DEFAULT} visible instead of having to
 * guess which API was taken. The trace is inert - no output, no environment lookup in
 * the hot path beyond one cached static field - when ATL_DEBUG_FONT is unset.
 */
public final class ATLFontTrace {
	private static final boolean ENABLED = System.getenv("ATL_DEBUG_FONT") != null;

	private ATLFontTrace() {}

	/** @return whether the font trace is enabled for this process */
	public static boolean enabled() {
		return ENABLED;
	}

	/** Prints one trace line to stderr when ATL_DEBUG_FONT is set. */
	public static void log(String message) {
		if (ENABLED) System.err.println("ATL font: " + message);
	}
}
