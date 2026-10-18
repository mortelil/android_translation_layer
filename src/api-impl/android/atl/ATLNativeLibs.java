// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Install-time native-library extraction for a package ATL only loads at
 * runtime.
 *
 * <p>Android extracts {@code lib/<abi>/} of every installed apk into the
 * directory published as {@link android.content.pm.ApplicationInfo#nativeLibraryDir}
 * ({@code NativeLibraryHelper.createNativeLibrarySubdir} in PackageManagerService
 * install). ATL mirrors that for the package it starts
 * ({@code extract_native_libraries} in main.c) but a companion apk resolved later
 * out of {@code _installed_apks_} had no such directory, so its
 * {@code nativeLibraryDir} was null - a state an installed package never sees.
 *
 * <p>Publication rules, all under one exclusive lock per directory:
 * <ul>
 * <li>Extraction writes into a private staging directory inside the library
 *     directory, so two processes never write or delete the same temporary path.
 * <li>Every staged copy is validated against its zip entry (length and CRC32)
 *     <em>before</em> the first published file is replaced, so a failure can
 *     never publish a mix of two apks' libraries.
 * <li>The stamp naming the apk's library identities is written last, so a
 *     directory is only ever handed out after its whole content is the requested
 *     apk's.
 * <li>A failure propagates as an {@link IOException} and leaves no stamp: the
 *     caller must fail the package load instead of publishing a directory that is
 *     partial, mixed or absent. An apk that legitimately has no library for the
 *     ABI is a different outcome ({@link Result#NO_LIBRARIES}) and publishes an
 *     empty, current directory.
 * </ul>
 *
 * <p>Deliberately pure Java (no android.* types) so the extraction rules are
 * unit-testable on a plain JVM: see {@code tests/native-libs}.
 */
public final class ATLNativeLibs {
	/** Outcome of a successful {@link #publish}. */
	public enum Result {
		/** Libraries were extracted (or re-extracted) and are now current. */
		PUBLISHED,
		/** The directory already holds exactly this apk's libraries. */
		REUSED,
		/** The apk declares no library for this ABI; the directory is current and empty. */
		NO_LIBRARIES
	}

	/** Records which apk's libraries a directory holds (content identity). */
	private static final String STAMP_NAME = ".atl-native.stamp";
	/** Serializes publication of one library directory across processes. */
	private static final String LOCK_NAME = ".atl-native.lock";
	/** Prefix of the private staging directory of one publication. */
	private static final String STAGE_PREFIX = ".atl-native-stage-";
	private static final String TEMP_SUFFIX = ".tmp";

	/** Distinguishes this JVM's temporary names from another process'. */
	private static final String RUN_ID = Integer.toHexString(System.identityHashCode(ATLNativeLibs.class))
	                                      + "-" + Long.toHexString(System.nanoTime());
	private static final AtomicLong sequence = new AtomicLong();

	/* FileLock is held per JVM: a second thread of the same JVM locking the same
	 * file gets OverlappingFileLockException instead of waiting for the first. One
	 * monitor per directory therefore serializes this JVM's publishers, and the
	 * file lock serializes the processes. */
	private static final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();

	private ATLNativeLibs() {}

	/**
	 * Publishes {@code lib/<abi>/} of {@code apk} into {@code libDir}.
	 *
	 * @param apk the installed apk to extract from
	 * @param libDir the directory to publish into (created if absent)
	 * @param abi the host ABI, i.e. the {@code lib/<abi>/} subdirectory
	 * @return {@link Result#PUBLISHED} when libraries were extracted,
	 *         {@link Result#REUSED} when the directory was already current and
	 *         {@link Result#NO_LIBRARIES} when the apk has none for {@code abi}
	 * @throws IOException when nothing usable was published; the directory must not
	 *         be published as {@code nativeLibraryDir} in that case
	 */
	public static Result publish(File apk, File libDir, String abi) throws IOException {
		if (abi == null || abi.isEmpty())
			throw new IOException("no ABI given for the native libraries of " + apk);
		synchronized (monitor(libDir)) {
			try (FileChannel channel = openLock(libDir); FileLock lock = channel.lock()) {
				return publishLocked(apk, libDir, abi);
			}
		}
	}

	/**
	 * {@link #publish} for the package loader: returns the directory to publish as
	 * {@code nativeLibraryDir}, or throws so the load fails. Never returns null,
	 * and never returns a directory whose content is not this apk's complete
	 * library set.
	 */
	public static File publishOrFail(File apk, File libDir, String abi) throws IOException {
		publish(apk, libDir, abi);
		return libDir;
	}

	private static Result publishLocked(File apk, File libDir, String abi) throws IOException {
		File stamp = new File(libDir, STAMP_NAME);
		try (ZipFile zip = new ZipFile(apk)) {
			Map<String, ZipEntry> wanted = select(zip, abi);
			removeLeftovers(libDir);
			if (isCurrent(libDir, stamp, abi, wanted))
				return wanted.isEmpty() ? Result.NO_LIBRARIES : Result.REUSED;
			File stage = new File(libDir, STAGE_PREFIX + RUN_ID + "-" + sequence.incrementAndGet());
			try {
				if (!stage.mkdir() && !stage.isDirectory())
					throw new IOException("Cannot create staging directory " + stage);
				extract(zip, wanted, stage);
				install(stage, libDir, wanted, stamp, abi);
			} finally {
				deleteRecursively(stage);
			}
			return wanted.isEmpty() ? Result.NO_LIBRARIES : Result.PUBLISHED;
		} catch (IOException e) {
			/* Something between the requested apk and the directory did not match:
			 * drop the stamp so no later load mistakes what is on disk for a valid
			 * installation of this apk, and report the failure. */
			stamp.delete();
			removeLeftovers(libDir);
			throw new IOException("Cannot publish the native libraries of " + apk
			                      + " for " + abi + " in " + libDir, e);
		}
	}

	/** The {@code lib/<abi>/<name>} entries of {@code zip}, by library name. */
	private static Map<String, ZipEntry> select(ZipFile zip, String abi) {
		Map<String, ZipEntry> wanted = new TreeMap<>();
		String prefix = "lib/" + abi + "/";
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (entry.isDirectory() || !name.startsWith(prefix))
				continue;
			String file_name = name.substring(prefix.length());
			if (file_name.isEmpty() || file_name.indexOf('/') != -1)
				continue;
			wanted.put(file_name, entry);
		}
		return wanted;
	}

	/**
	 * Extracts every wanted library into the private staging directory and
	 * validates the copy against its zip entry.
	 */
	private static void extract(ZipFile zip, Map<String, ZipEntry> wanted, File stage) throws IOException {
		for (Map.Entry<String, ZipEntry> wanted_entry : wanted.entrySet()) {
			ZipEntry entry = wanted_entry.getValue();
			File target = new File(stage, wanted_entry.getKey());
			CRC32 crc = new CRC32();
			byte[] buffer = new byte[64 * 1024];
			try (InputStream in = zip.getInputStream(entry);
			     OutputStream out = new FileOutputStream(target)) {
				int read;
				while ((read = in.read(buffer)) > 0) {
					out.write(buffer, 0, read);
					crc.update(buffer, 0, read);
				}
			}
			if (entry.getSize() != -1 && target.length() != entry.getSize())
				throw new IOException("Incomplete copy of " + entry.getName() + ": " + target.length()
				                      + " of " + entry.getSize() + " bytes");
			if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc())
				throw new IOException("Corrupt copy of " + entry.getName());
			// NativeLibraryHelper installs the directory and its contents 0755.
			target.setReadable(true, false);
			target.setExecutable(true, false);
		}
	}

	/**
	 * Moves a fully validated staging directory into place: each library is renamed
	 * onto its published name (an atomic replace, so a concurrent reader sees the
	 * old or the new file, never a partial write), entries of a library the apk no
	 * longer has are removed, and the stamp is written last.
	 */
	private static void install(File stage, File libDir, Map<String, ZipEntry> wanted, File stamp, String abi)
			throws IOException {
		for (String file_name : wanted.keySet()) {
			File staged = new File(stage, file_name);
			File target = new File(libDir, file_name);
			if (!staged.renameTo(target)) {
				// Some filesystems refuse to replace an existing file by rename.
				if (!target.delete() || !staged.renameTo(target))
					throw new IOException("Cannot publish " + target);
			}
		}
		for (File existing : listFiles(libDir)) {
			String name = existing.getName();
			if (name.equals(STAMP_NAME) || name.equals(LOCK_NAME) || wanted.containsKey(name))
				continue;
			if (!existing.delete())
				throw new IOException("Cannot remove stale " + existing);
		}
		writeStamp(stamp, abi, wanted);
	}

	/** True when the directory holds exactly this apk's libraries for {@code abi}. */
	private static boolean isCurrent(File libDir, File stamp, String abi, Map<String, ZipEntry> wanted) throws IOException {
		String[] recorded = readStamp(stamp);
		if (recorded == null || recorded.length == 0 || !recorded[0].equals(abi))
			return false;
		if (recorded.length - 1 != wanted.size())
			return false;
		int i = 1;
		for (Map.Entry<String, ZipEntry> entry : wanted.entrySet()) {
			String file_name = entry.getKey();
			if (!recorded[i++].equals(identity(file_name, entry.getValue())))
				return false;
			File published = new File(libDir, file_name);
			if (!published.isFile() || published.length() != entry.getValue().getSize())
				return false;
			// The stamp says which apk's bytes belong here; a length check alone would
			// accept a truncated or overwritten file that happens to keep its size.
			if (entry.getValue().getCrc() != -1 && crc32(published) != entry.getValue().getCrc())
				return false;
		}
		// Nothing else may live here: a leftover library of a replaced apk would be
		// visible to the package's library lookup.
		int published = 0;
		for (File existing : listFiles(libDir)) {
			String name = existing.getName();
			if (name.equals(STAMP_NAME) || name.equals(LOCK_NAME))
				continue;
			if (!existing.isFile() || !wanted.containsKey(name))
				return false;
			published++;
		}
		return published == wanted.size();
	}

	private static String identity(String name, ZipEntry entry) {
		return name + "\t" + entry.getSize() + "\t" + entry.getCrc();
	}

	private static void writeStamp(File stamp, String abi, Map<String, ZipEntry> wanted) throws IOException {
		File temp = new File(stamp.getParentFile(), STAMP_NAME + "." + RUN_ID + "-" + sequence.incrementAndGet() + TEMP_SUFFIX);
		StringBuilder content = new StringBuilder(abi).append('\n');
		for (Map.Entry<String, ZipEntry> entry : wanted.entrySet())
			content.append(identity(entry.getKey(), entry.getValue())).append('\n');
		try (OutputStream out = new FileOutputStream(temp)) {
			out.write(content.toString().getBytes("UTF-8"));
		}
		if (!temp.renameTo(stamp)) {
			if (!stamp.delete() || !temp.renameTo(stamp)) {
				temp.delete();
				throw new IOException("Cannot publish " + stamp);
			}
		}
	}

	/** The stamp's lines, or null when absent/unreadable/malformed. */
	private static String[] readStamp(File stamp) {
		if (!stamp.isFile())
			return null;
		try {
			byte[] bytes = new byte[(int)stamp.length()];
			try (InputStream in = new FileInputStream(stamp)) {
				int offset = 0;
				while (offset < bytes.length) {
					int read = in.read(bytes, offset, bytes.length - offset);
					if (read < 0)
						break;
					offset += read;
				}
				if (offset != bytes.length)
					return null;
			}
			String content = new String(bytes, "UTF-8");
			if (!content.endsWith("\n"))
				return null;
			return content.substring(0, content.length() - 1).split("\n", -1);
		} catch (IOException e) {
			return null;
		}
	}

	private static long crc32(File file) throws IOException {
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream in = new FileInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) > 0)
				crc.update(buffer, 0, read);
		}
		return crc.getValue();
	}

	/**
	 * Removes the partial files of an extraction that did not finish. Only ever
	 * called while holding the directory's lock, so a live publisher's private
	 * staging directory and temporary names cannot be hit - they belong to a
	 * process that crashed or was killed mid-extraction.
	 */
	private static void removeLeftovers(File libDir) {
		for (File file : listFiles(libDir)) {
			String name = file.getName();
			if (name.equals(LOCK_NAME))
				continue;
			if (name.startsWith(STAGE_PREFIX) || (name.startsWith(".") && name.endsWith(TEMP_SUFFIX)))
				deleteRecursively(file);
		}
	}

	private static void deleteRecursively(File file) {
		File[] children = file.isDirectory() ? listFiles(file) : null;
		if (children != null)
			for (File child : children)
				deleteRecursively(child);
		file.delete();
	}

	private static File[] listFiles(File dir) {
		File[] files = dir.listFiles();
		return files == null ? new File[0] : files;
	}

	/** The lock file of {@code libDir}, created with the directory. */
	private static FileChannel openLock(File libDir) throws IOException {
		if (!libDir.isDirectory() && !libDir.mkdirs() && !libDir.isDirectory())
			throw new IOException("Cannot create native library directory " + libDir);
		File lock = new File(libDir, LOCK_NAME);
		try {
			return new RandomAccessFile(lock, "rw").getChannel();
		} catch (IOException e) {
			throw new IOException("Cannot open the native library lock " + lock, e);
		}
	}

	/** The per-JVM monitor serializing publishers of one library directory. */
	private static Object monitor(File libDir) {
		String key;
		try {
			key = libDir.getCanonicalPath();
		} catch (IOException e) {
			key = libDir.getAbsolutePath();
		}
		Object created = new Object();
		Object existing = monitors.putIfAbsent(key, created);
		return existing == null ? created : existing;
	}
}
