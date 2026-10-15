/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.procprobe;

import android.os.Process;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Cross-class state and markers of the process probe. Every marker line is
 * written to stdout, which a spawned child inherits from the loader, so the
 * loader's captured log carries both sides of a spawn.
 */
public final class ProbeState {
	/** Set by a provider's onCreate in the process that created it. */
	public static volatile boolean providerOnCreate;
	public static volatile int providersCreated;

	private ProbeState() {}

	/** ATL_SERVICE_PROCESS in a service child, null in the loader. */
	public static String processName() {
		return System.getenv("ATL_SERVICE_PROCESS");
	}

	/** The manifest process of this process: the child's own name, else "default". */
	public static String label() {
		String name = processName();
		return name == null ? "default" : name;
	}

	public static void marker(String marker) {
		System.out.println(marker + " pid=" + Process.myPid() + " process=" + label());
		System.out.flush();
	}

	/** Reads a (small) file as ISO-8859-1 so NUL-separated /proc files survive. */
	public static String readFile(String path) {
		try {
			FileInputStream in = new FileInputStream(path);
			try {
				byte[] data = new byte[16384];
				int n = in.read(data);
				return new String(data, 0, n < 0 ? 0 : n, "ISO-8859-1");
			} finally {
				in.close();
			}
		} catch (IOException e) {
			return null;
		}
	}
}
