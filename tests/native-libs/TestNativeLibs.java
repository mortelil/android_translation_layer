// SPDX-License-Identifier: GPL-3.0-only
import android.atl.ATLNativeLibs;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host-JVM test of {@link ATLNativeLibs}: the install-time native-library
 * extraction ATL performs for a package it loads at runtime (Android does this at
 * package install, {@code NativeLibraryHelper.createNativeLibrarySubdir}).
 *
 * <p>The class under test has no Android dependencies, so this runs on a plain JVM
 * with javac/java - no emulator, no ATL runtime. It is compiled from this
 * checkout's source in the same command that runs it (see {@code run.sh}, which
 * passes the source path and its sha256 in; {@link #sourceIsThisCheckout()} checks
 * them). Every case builds its own apk and library directory in a temp tree.
 *
 * <p>Ordering between threads and between processes uses barriers - a
 * {@link CyclicBarrier} in-JVM, marker files polled with a deadline across
 * processes - never a fixed sleep that assumes timing.
 *
 * <p>Concurrency cases:
 * <ul>
 * <li>{@code concurrentThreadsPublishOneSet}: 8 threads of one JVM release
 *     together, twice, against the same directory.
 * <li>{@code concurrentProcessesPublishOneSet}: 3 separate JVMs
 *     ({@code --concurrent-publish} child mode) release together, twice, against
 *     the same directory; the second round publishes a different apk, so the
 *     stale-library removal runs while the processes contend.
 * </ul>
 */
public final class TestNativeLibs {
	private static final String ABI = "x86_64";
	private static final String OTHER_ABI = "arm64-v8a";
	private static final String STAMP_NAME = ".atl-native.stamp";
	private static int checks;

	private static void check(boolean condition, String message) {
		checks++;
		if (!condition) throw new AssertionError(message);
	}

	private static void caseDone(String name) {
		System.out.println("case " + name + " ok checks=" + checks);
	}

	private static File tempDir() throws IOException {
		File dir = Files.createTempDirectory("atl-native-libs").toFile();
		dir.deleteOnExit();
		return dir;
	}

	/** Writes a zip with the given lib/ entries; content is derived from the name. */
	private static File apk(File dir, String name, String[] entries) throws IOException {
		File apk = new File(dir, name);
		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
			for (String entry : entries) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.write(contentOf(entry));
				zip.closeEntry();
			}
		}
		return apk;
	}

	private static byte[] contentOf(String entry) {
		// Distinct, stable bytes per path so a wrong entry is visible in the check.
		byte[] bytes = new byte[256 + entry.length()];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)(entry.hashCode() + i);
		return bytes;
	}

	private static byte[] read(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}

	private static void sameBytes(File file, String entry, String context) throws IOException {
		byte[] expected = contentOf(entry);
		byte[] actual = read(file);
		check(expected.length == actual.length, context + ": length of " + file);
		for (int i = 0; i < expected.length; i++)
			if (expected[i] != actual[i]) throw new AssertionError(context + ": content of " + file + " differs at " + i);
	}

	/** Library files in a published directory (bookkeeping files excluded). */
	private static ArrayList<String> publishedNames(File lib) {
		ArrayList<String> names = new ArrayList<>();
		String[] entries = lib.list();
		if (entries != null)
			for (String entry : entries)
				if (!entry.equals(STAMP_NAME) && !entry.equals(".atl-native.lock") && !entry.startsWith("."))
					names.add(entry);
		java.util.Collections.sort(names);
		return names;
	}

	private static ArrayList<String> leftovers(File lib) {
		ArrayList<String> names = new ArrayList<>();
		String[] entries = lib.list();
		if (entries != null)
			for (String entry : entries)
				if (entry.endsWith(".tmp") || entry.startsWith(".atl-native-stage-"))
					names.add(entry);
		java.util.Collections.sort(names);
		return names;
	}

	private static String sha256(File file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream in = new FileInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
		}
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest()) hex.append(String.format("%02x", b));
		return hex.toString();
	}

	/**
	 * The tested class must be the one this checkout's source compiles to: the
	 * runner passes the source path it compiled and that file's sha256.
	 */
	private static void sourceIsThisCheckout() throws Exception {
		String path = System.getProperty("atl.test.source");
		String expected = System.getProperty("atl.test.source.sha256");
		check(path != null && expected != null, "run.sh must pass -Datl.test.source and -Datl.test.source.sha256");
		File source = new File(path);
		check(source.isFile(), "source under test exists: " + path);
		String actual = sha256(source);
		check(expected.equals(actual), "sha256 of " + path + " is " + expected + ", found " + actual);
		String location = ATLNativeLibs.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		check(new File(location, "android/atl/ATLNativeLibs.class").isFile(),
		      "the class under test was compiled into this run's output: " + location + " (source " + path + ")");
		ATLNativeLibs.class.getMethod("publishOrFail", File.class, File.class, String.class);
		System.out.println("NATIVE_LIBS_SOURCE " + path + " sha256=" + actual + " classes=" + location);
		caseDone("source");
	}

	private static void freshExtraction() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {
		    "lib/" + ABI + "/libprobe.so", "lib/" + OTHER_ABI + "/libother.so", "classes.dex"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "fresh publish");
		File probe = new File(lib, "libprobe.so");
		check(probe.isFile(), "extracted the host-ABI library");
		sameBytes(probe, "lib/" + ABI + "/libprobe.so", "fresh content");
		check(probe.canExecute(), "extracted library is executable (NativeLibraryHelper installs 0755)");
		check(!new File(lib, "libother.so").exists(), "a foreign ABI's library is not extracted");
		check(!new File(lib, "classes.dex").exists(), "non-library entries are not extracted");
		check(ATLNativeLibs.publishOrFail(apk, lib, ABI).equals(lib), "publishOrFail returns the directory");
		caseDone("fresh-extraction");
	}

	private static void abiSelectionIsExplicit() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {
		    "lib/" + ABI + "/libhostabi.so", "lib/" + OTHER_ABI + "/libotherabi.so"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "publish host abi");
		check(publishedNames(lib).equals(java.util.Arrays.asList("libhostabi.so")),
		      "only the requested abi is published: " + publishedNames(lib));
		// Switching the abi must replace the set, not add to it.
		check(ATLNativeLibs.publish(apk, lib, OTHER_ABI) == ATLNativeLibs.Result.PUBLISHED, "publish other abi");
		check(publishedNames(lib).equals(java.util.Arrays.asList("libotherabi.so")),
		      "the abi switch replaces the published set: " + publishedNames(lib));
		check(ATLNativeLibs.publish(apk, lib, "mips") == ATLNativeLibs.Result.NO_LIBRARIES,
		      "an abi the apk does not have publishes nothing");
		check(publishedNames(lib).isEmpty(), "a foreign abi's libraries are removed: " + publishedNames(lib));
		for (String bad : new String[] {null, ""}) {
			boolean threw = false;
			try {
				ATLNativeLibs.publish(apk, lib, bad);
			} catch (IOException e) {
				threw = true;
			}
			check(threw, "a missing abi must fail instead of publishing anything");
		}
		caseDone("abi-selection");
	}

	private static void noLibrariesIsNotFailure() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {"classes.dex"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.NO_LIBRARIES,
		      "an apk without libraries is not a failure");
		check(lib.isDirectory(), "the published directory exists even for an apk without libraries");
		check(publishedNames(lib).isEmpty(), "no library is published: " + publishedNames(lib));
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.NO_LIBRARIES,
		      "reloading an apk without libraries stays NO_LIBRARIES (not a failure)");
		// Only foreign-abi libraries: the host abi still has none, and the foreign
		// ones must never be published for it.
		File foreign = apk(dir, "foreign.apk", new String[] {"lib/" + OTHER_ABI + "/libotherabi.so"});
		check(ATLNativeLibs.publish(foreign, lib, ABI) == ATLNativeLibs.Result.NO_LIBRARIES,
		      "an apk with only a foreign abi has no library for the host abi");
		check(publishedNames(lib).isEmpty(), "the foreign library is not published: " + publishedNames(lib));
		caseDone("no-libraries");
	}

	private static void repeatedLoadReuses() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "first publish");
		File probe = new File(lib, "libprobe.so");
		long stamp = probe.lastModified();
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.REUSED, "second publish reuses");
		check(probe.lastModified() == stamp, "a reused library is not rewritten");
		sameBytes(probe, "lib/" + ABI + "/libprobe.so", "reuse content");
		caseDone("repeated-load");
	}

	private static void apkReplacementIsDetected() throws IOException {
		File dir = tempDir();
		File lib = new File(dir, "lib");
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "old apk publish");
		// Same entry name and same length, different bytes: only a content identity
		// (the zip entry CRC) can tell these two apks apart.
		byte[] different = new byte[256 + ("lib/" + ABI + "/libprobe.so").length()];
		for (int i = 0; i < different.length; i++) different[i] = (byte)(i * 7 + 1);
		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
			zip.putNextEntry(new ZipEntry("lib/" + ABI + "/libprobe.so"));
			zip.write(different);
			zip.closeEntry();
		}
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "replaced apk republishes");
		check(java.util.Arrays.equals(read(new File(lib, "libprobe.so")), different), "replaced apk's bytes are published");
		caseDone("apk-replacement");
	}

	private static void staleLibrariesAreRemoved() throws IOException {
		File dir = tempDir();
		File lib = new File(dir, "lib");
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libold.so", "lib/" + ABI + "/libkeep.so"});
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "publish with two libs");
		check(new File(lib, "libold.so").isFile(), "first apk's library exists");
		File newer = apk(dir, "newer.apk", new String[] {"lib/" + ABI + "/libkeep.so"});
		check(ATLNativeLibs.publish(newer, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "publish updated apk");
		check(!new File(lib, "libold.so").exists(), "a library absent from the updated apk is removed");
		check(new File(lib, "libkeep.so").isFile(), "the remaining library stays");
		check(publishedNames(lib).equals(java.util.Arrays.asList("libkeep.so")),
		      "exactly the updated apk's set is published: " + publishedNames(lib));
		caseDone("stale-removal");
	}

	private static void corruptedLibraryIsRepaired() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "publish");
		File probe = new File(lib, "libprobe.so");
		// Right size, wrong content: a length-only reuse check would accept this.
		byte[] broken = read(probe);
		broken[0] ^= 0x5a;
		try (OutputStream out = new FileOutputStream(probe)) { out.write(broken); }
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "corrupt library republished");
		sameBytes(probe, "lib/" + ABI + "/libprobe.so", "repaired content");
		caseDone("corrupt-library-repair");
	}

	/** A crashed publication leaves private temporaries; the next one must clean up. */
	private static void interruptedExtractionIsNotPublished() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		File lib = new File(dir, "lib");
		check(lib.mkdirs(), "lib dir");
		File temp = new File(lib, ".libprobe.so.deadbeef-1.tmp");
		try (OutputStream out = new FileOutputStream(temp)) { out.write(new byte[] {1, 2, 3}); }
		File stage = new File(lib, ".atl-native-stage-deadbeef-2");
		check(stage.mkdir(), "stale staging dir");
		try (OutputStream out = new FileOutputStream(new File(stage, "libprobe.so"))) { out.write(new byte[] {4}); }
		// Stamp of an interrupted publication: names another abi and no library, so
		// the directory must not be treated as current.
		try (OutputStream out = new FileOutputStream(new File(lib, STAMP_NAME))) {
			out.write((OTHER_ABI + "\nstale\t1\t2\n").getBytes("UTF-8"));
		}
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "publish after a crash");
		sameBytes(new File(lib, "libprobe.so"), "lib/" + ABI + "/libprobe.so", "content after crash");
		check(leftovers(lib).isEmpty(), "the interrupted extraction's leftovers are removed: " + leftovers(lib));
		check(!stage.exists(), "the stale staging directory is removed");
		caseDone("interrupted-extraction");
	}

	private static void failureIsReported() throws IOException {
		File dir = tempDir();
		File apk = apk(dir, "pkg.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		File lib = new File(dir, "lib");
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "valid publication");
		File stamp = new File(lib, STAMP_NAME);
		check(stamp.isFile(), "valid publication has a stamp");

		// A truncated apk cannot be read as a zip at all.
		File broken = apk(dir, "broken.apk", new String[] {"lib/" + ABI + "/libbroken.so"});
		try (RandomAccessFile raf = new RandomAccessFile(broken, "rw")) { raf.setLength(broken.length() - 30); }
		IOException failure = null;
		try {
			ATLNativeLibs.publish(broken, lib, ABI);
		} catch (IOException e) {
			failure = e;
		}
		check(failure != null, "an unreadable apk must fail instead of reporting a published directory");
		check(failure.getMessage().contains(broken.getPath()) && failure.getMessage().contains(ABI),
		      "the failure names the apk and the abi: " + failure.getMessage());
		check(!stamp.exists(), "a failed publication leaves no stamp (the directory is not current)");
		check(leftovers(lib).isEmpty(), "a failed publication leaves no temporaries: " + leftovers(lib));
		check(!new File(lib, "libbroken.so").exists(), "the failed apk's library is not published");
		check(publishedNames(lib).equals(java.util.Arrays.asList("libprobe.so")),
		      "the previously published set is not mixed with the failed one: " + publishedNames(lib));
		// The next load of a good apk repairs the directory.
		check(ATLNativeLibs.publish(apk, lib, ABI) == ATLNativeLibs.Result.PUBLISHED, "recovery after failure");
		sameBytes(new File(lib, "libprobe.so"), "lib/" + ABI + "/libprobe.so", "recovered content");

		// An unusable library directory fails instead of looking successful.
		File notADir = new File(dir, "file-not-dir");
		try (OutputStream out = new FileOutputStream(notADir)) { out.write(1); }
		boolean threw = false;
		try {
			ATLNativeLibs.publish(apk, notADir, ABI);
		} catch (IOException e) {
			threw = true;
		}
		check(threw, "an unusable library directory fails instead of looking successful");
		caseDone("failure-reporting");
	}

	/**
	 * 8 threads of this JVM released together by a barrier, twice: the first apk,
	 * then a replacement apk. Exactly one of them may extract per round; the others
	 * must reuse, and no thread may see an error or a partially published file.
	 */
	private static void concurrentThreadsPublishOneSet() throws Exception {
		File dir = tempDir();
		File lib = new File(dir, "lib");
		File first = apk(dir, "first.apk", new String[] {
		    "lib/" + ABI + "/libprobe.so", "lib/" + ABI + "/libsecond.so"});
		File second = apk(dir, "second.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		for (File apk : new File[] {first, second}) {
			final int threads = 8;
			final CyclicBarrier barrier = new CyclicBarrier(threads);
			final AtomicInteger published = new AtomicInteger();
			final AtomicInteger reused = new AtomicInteger();
			final AtomicInteger noLibraries = new AtomicInteger();
			final Throwable[] failures = new Throwable[threads];
			Thread[] workers = new Thread[threads];
			for (int i = 0; i < threads; i++) {
				final int index = i;
				workers[i] = new Thread(new Runnable() { public void run() {
					try {
						barrier.await();
						ATLNativeLibs.Result result = ATLNativeLibs.publish(apk, lib, ABI);
						if (result == ATLNativeLibs.Result.PUBLISHED) published.incrementAndGet();
						else if (result == ATLNativeLibs.Result.REUSED) reused.incrementAndGet();
						else noLibraries.incrementAndGet();
					} catch (Throwable t) {
						failures[index] = t;
					}
				}}, "publish-" + i);
				workers[i].start();
			}
			for (Thread worker : workers) worker.join(120000);
			for (Throwable t : failures) check(t == null, "concurrent publisher failed: " + t);
			check(published.get() == 1, "exactly one publisher extracts " + apk.getName()
			      + " (published=" + published.get() + " reused=" + reused.get() + ")");
			check(reused.get() == threads - 1, "every other publisher reuses " + apk.getName()
			      + " (reused=" + reused.get() + ")");
			check(noLibraries.get() == 0, "no publisher reports missing libraries");
		}
		check(publishedNames(lib).equals(java.util.Arrays.asList("libprobe.so")),
		      "the raced directory holds exactly the replacement apk's set: " + publishedNames(lib));
		sameBytes(new File(lib, "libprobe.so"), "lib/" + ABI + "/libprobe.so", "raced content");
		check(leftovers(lib).isEmpty(), "no temporaries are left behind: " + leftovers(lib));
		check(ATLNativeLibs.publish(second, lib, ABI) == ATLNativeLibs.Result.REUSED,
		      "the raced directory is current for the replacement apk");
		caseDone("concurrent-threads");

		// Different directories do not serialize against each other.
		File otherDir = tempDir();
		File otherLib = new File(otherDir, "lib");
		File otherApk = apk(otherDir, "other.apk", new String[] {"lib/" + ABI + "/libother.so"});
		final CyclicBarrier pair = new CyclicBarrier(2);
		final ATLNativeLibs.Result[] results = new ATLNativeLibs.Result[2];
		final Throwable[] failures = new Throwable[2];
		Thread[] workers = new Thread[2];
		for (int i = 0; i < 2; i++) {
			final int index = i;
			final File apk = index == 0 ? second : otherApk;
			final File target = index == 0 ? lib : otherLib;
			workers[i] = new Thread(new Runnable() { public void run() {
				try {
					pair.await();
					results[index] = ATLNativeLibs.publish(apk, target, ABI);
				} catch (Throwable t) {
					failures[index] = t;
				}
			}}, "independent-" + i);
			workers[i].start();
		}
		for (Thread worker : workers) worker.join(120000);
		for (Throwable t : failures) check(t == null, "independent publisher failed: " + t);
		check(results[0] == ATLNativeLibs.Result.REUSED && results[1] == ATLNativeLibs.Result.PUBLISHED,
		      "publishers of different directories do not serialize: " + results[0] + " " + results[1]);
		caseDone("concurrent-independent-directories");
	}

	/**
	 * 3 separate JVMs released together by marker files, twice (see the child mode
	 * in {@link #main}). The lock is the only thing between them: without it the
	 * processes share temporary names and remove each other's files.
	 */
	private static void concurrentProcessesPublishOneSet() throws Exception {
		File dir = tempDir();
		File lib = new File(dir, "lib");
		File first = apk(dir, "first.apk", new String[] {
		    "lib/" + ABI + "/libprobe.so", "lib/" + ABI + "/libsecond.so"});
		File second = apk(dir, "second.apk", new String[] {"lib/" + ABI + "/libprobe.so"});
		int processes = 3;
		File markers = new File(dir, "markers");
		check(markers.mkdir(), "marker dir");
		String java_bin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");
		java.util.List<Process> children = new ArrayList<>();
		java.util.List<File> results = new ArrayList<>();
		for (int i = 0; i < processes; i++) {
			File result = new File(dir, "result-" + i + ".txt");
			results.add(result);
			children.add(new ProcessBuilder(java_bin, "-cp", classpath, "TestNativeLibs",
			                                "--concurrent-publish", lib.getPath(), first.getPath(),
			                                second.getPath(), result.getPath(), markers.getPath(),
			                                String.valueOf(i), String.valueOf(processes))
			                 .redirectErrorStream(true)
			                 .redirectOutput(new File(dir, "child-" + i + ".log"))
			                 .start());
		}
		for (int i = 0; i < processes; i++) {
			Process child = children.get(i);
			check(child.waitFor(180, java.util.concurrent.TimeUnit.SECONDS),
			      "child " + i + " finished (log " + new File(dir, "child-" + i + ".log") + ")");
			check(child.exitValue() == 0, "child " + i + " exit=" + child.exitValue() + " (log "
			      + new File(dir, "child-" + i + ".log") + ")");
		}
		for (int round = 0; round < 2; round++) {
			int published = 0, reused = 0;
			for (File result : results) {
				String line = null;
				for (String candidate : Files.readAllLines(result.toPath(), java.nio.charset.Charset.forName("UTF-8")))
					if (candidate.startsWith("round " + round + ": ")) line = candidate;
				check(line != null, "child reported round " + round + " in " + result);
				String outcome = line.substring(("round " + round + ": ").length());
				check(!outcome.startsWith("EXCEPTION"), "a concurrent publisher failed: " + line);
				if (outcome.equals("PUBLISHED")) published++;
				else if (outcome.equals("REUSED")) reused++;
				else throw new AssertionError("unexpected outcome " + line);
			}
			check(published == 1, "exactly one process extracts in round " + round + " (published=" + published + ")");
			check(reused == processes - 1, "every other process reuses in round " + round + " (reused=" + reused + ")");
		}
		check(publishedNames(lib).equals(java.util.Arrays.asList("libprobe.so")),
		      "the cross-process directory holds exactly the second apk's set: " + publishedNames(lib));
		sameBytes(new File(lib, "libprobe.so"), "lib/" + ABI + "/libprobe.so", "cross-process content");
		check(leftovers(lib).isEmpty(), "no cross-process temporaries are left behind: " + leftovers(lib));
		check(ATLNativeLibs.publish(second, lib, ABI) == ATLNativeLibs.Result.REUSED,
		      "the cross-process directory is current for the second apk");
		caseDone("concurrent-processes");
	}

	/** Child mode of {@link #concurrentProcessesPublishOneSet}. */
	private static void concurrentPublisher(String[] args) throws Exception {
		File lib = new File(args[1]);
		File[] apks = {new File(args[2]), new File(args[3])};
		File result = new File(args[4]);
		File markers = new File(args[5]);
		int index = Integer.parseInt(args[6]);
		int peers = Integer.parseInt(args[7]);
		StringBuilder out = new StringBuilder();
		for (int round = 0; round < apks.length; round++) {
			// Barrier: announce this round, then wait for every peer to announce it.
			File marker = new File(markers, "round-" + round + "-" + index);
			check(marker.createNewFile() || marker.isFile(), "marker " + marker);
			long deadline = System.nanoTime() + 180L * 1000 * 1000 * 1000;
			for (int peer = 0; peer < peers; peer++) {
				File peer_marker = new File(markers, "round-" + round + "-" + peer);
				while (!peer_marker.isFile()) {
					if (System.nanoTime() > deadline)
						throw new AssertionError("barrier timeout waiting for " + peer_marker);
					Thread.sleep(10);
				}
			}
			String outcome;
			try {
				outcome = ATLNativeLibs.publish(apks[round], lib, ABI).name();
			} catch (IOException e) {
				outcome = "EXCEPTION " + e.getMessage();
			}
			out.append("round ").append(round).append(": ").append(outcome).append('\n');
			try (OutputStream stream = new FileOutputStream(result)) {
				stream.write(out.toString().getBytes("UTF-8"));
			}
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && args[0].equals("--concurrent-publish")) {
			concurrentPublisher(args);
			return;
		}
		sourceIsThisCheckout();
		freshExtraction();
		abiSelectionIsExplicit();
		noLibrariesIsNotFailure();
		repeatedLoadReuses();
		apkReplacementIsDetected();
		staleLibrariesAreRemoved();
		corruptedLibraryIsRepaired();
		interruptedExtractionIsNotPublished();
		failureIsReported();
		concurrentThreadsPublishOneSet();
		concurrentProcessesPublishOneSet();
		System.out.println("NATIVE_LIBS_PASS checks=" + checks);
	}
}
