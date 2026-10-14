package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Parcel;
import android.util.AtomicFile;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Per-app dynamic shortcut state.
 *
 * <p>Contract reference: AOSP frameworks/base,
 * core/java/android/content/pm/ShortcutManager.java and
 * services/core/java/com/android/server/pm/ShortcutService.java (tag
 * {@code android-17.0.0_r1}, commit
 * {@code 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d}); the publisher-side method
 * contracts below follow {@code ShortcutManager} and the per-activity quota and
 * rank eviction follow {@code ShortcutPackage}. ATL has no system server /
 * {@code ShortcutService}, so the publisher-side state is kept by this class,
 * per package, in a process-wide registry (package isolation).
 *
 * <p>The state is persisted to {@code <files-dir>/shortcuts.<package>.bin}.
 *
 * <h2>Store format (version 4)</h2>
 * A version-4 store is a Parcel-framed header followed by an explicitly typed
 * payload:
 * <pre>
 *   int32   STORE_MAGIC (&hArr;{@link #STORE_MAGIC})
 *   int32   STORE_VERSION (&hArr;{@link #STORE_VERSION}, currently 4)
 *   byte[]  payload            (Parcel {@code writeByteArray}: int32 length + bytes)
 *   int32   crc32(payload)     ({@link java.util.zip.CRC32})
 * </pre>
 * The payload is a big-endian {@link DataOutputStream} stream: an {@code int32}
 * entry count (bounded by {@link #MAX_STORE_ENTRIES}) followed by one typed
 * record per entry; the record layout is documented in
 * {@link ShortcutInfo#writeTypedTo}. A store is only accepted if the magic
 * matches, the version is known, the payload length is in bounds, the CRC
 * matches and the payload is exactly consumed.
 *
 * <h2>Legacy migration (version 2)</h2>
 * The previous ATL format (magic, version {@code 2}, entry count, then one
 * {@link ShortcutInfo#writeToParcel Parcel}-encoded record each) is decoded by
 * {@link ShortcutInfo#CREATOR} - that is the one and only place the store
 * reader touches a Parcel for record data, and it is a validated one-shot
 * migration: the first write of any kind re-emits the state in version 4, so
 * the legacy path can never be used to produce new bytes. {@code
 * ShortcutInfo}'s legacy parcel schema 1 remains readable. Typed store schema 3
 * is also readable; schema 4 adds publisher and Intent extras.
 *
 * <h2>Corruption recovery</h2>
 * A store that does not decode (bad magic, unknown version, bad CRC, truncated
 * or otherwise malformed payload) is treated as empty state and logged; no
 * exception reaches the app. On the next write the unreadable file is
 * best-effort renamed to {@code <store>.corrupt} (keeping the bytes for
 * inspection) and replaced by a freshly written valid store, so the store
 * always converges back to a valid file.
 *
 * <h2>Locking (lost-update and torn-read prevention)</h2>
 * Every read and every read-modify-write runs under a single OS-managed,
 * whole-file lock on a stable sibling file {@code <store>.lock}:
 * <ul>
 * <li>The lock file is created if missing and is <b>never</b> deleted,
 * truncated, renamed or otherwise replaced by this code - so its inode, and
 * therefore the identity of the lock, is stable for the lifetime of the app
 * data directory. There is no owner record and no age-based staleness: no
 * time-based reclamation exists, so a live writer that pauses for longer than
 * any timeout keeps its lock.</li>
 * <li>Processes contend with {@link FileChannel#lock()} (a blocking POSIX
 * record lock on the whole file); a process that dies while holding it is
 * released by the kernel, and the next writer proceeds immediately.</li>
 * <li>Threads inside one process serialise on a per-lock-file monitor before
 * touching the OS lock, so same-process contention cannot raise
 * {@link java.nio.channels.OverlappingFileLockException} and cannot lose an
 * update. The monitor is reentrant for a thread that nests.</li>
 * </ul>
 * State is always re-read from the file <em>after</em> the lock is acquired and
 * immediately before the modifying operation, and the new bytes are committed
 * by atomic replacement ({@link AtomicFile}: write to {@code <store>.new},
 * fsync, {@code rename(2)} over the destination), so no reader ever observes a
 * half-written file and no writer overwrites a concurrent writer's update.
 *
 * <h2>Commit proof</h2>
 * {@link AtomicFile#finishWrite} only <em>logs</em> a failed {@code rename(2)},
 * so its return says nothing about whether the replacement happened, and a
 * length check cannot see the difference between the new file and an unchanged
 * old file of the same length. After every write this class therefore proves
 * the commit explicitly while still holding the store lock: the temp file
 * {@code <store>.new} must have been consumed by the rename and the destination
 * must contain byte-for-byte what was written (see {@link #write}). A write
 * whose replacement did not take effect raises {@link IOException}, removes the
 * leftover temp file, and leaves the previous store file intact, which
 * {@link #mutate} surfaces as {@link IllegalStateException} with the in-memory
 * state unchanged.
 */
public class ShortcutManager {
	public static final int FLAG_MATCH_MANIFEST = 1 << 0;
	public static final int FLAG_MATCH_DYNAMIC = 1 << 1;
	public static final int FLAG_MATCH_PINNED = 1 << 2;
	public static final int FLAG_MATCH_CACHED = 1 << 3;

	/**
	 * AOSP framework config defaults (framework-res
	 * {@code config_maxShortcutCountPerActivity} and
	 * {@code config_shortcutIconMaxDimension}, the latter in dp; ATL icons are not
	 * density-scaled, so the value is reported as pixels as AOSP itself does).
	 */
	private static final int MAX_SHORTCUT_COUNT_PER_ACTIVITY = 15;
	private static final int ICON_MAX_DIMENSIONS_PX = 96;

	/** ATL does not count API calls; report a non-exhausted quota. */
	private static final int RATE_LIMIT_QUOTA = 50;

	/**
	 * Store envelope magic, ASCII <code>"ATLS"</code>. A file that does not start
	 * with this value is not an ATL shortcut store (including the pre-versioning
	 * layout, which started with a bare entry count) and is ignored.
	 *
	 * @hide
	 */
	public static final int STORE_MAGIC = 0x41544C53;

	/**
	 * Current store document version. Version 2 is the previous ATL format and
	 * is migrated on read; bump when the envelope or the typed record layout
	 * changes incompatibly.
	 *
	 * @hide
	 */
	public static final int STORE_VERSION = 4;

	/** The previous ATL store version, decoded once for migration. */
	private static final int STORE_VERSION_LEGACY = 2;

	/** Upper bound on entries accepted from a store file (corruption guard). */
	private static final int MAX_STORE_ENTRIES = 4096;

	private static final int OPERATION_SET = 0;
	private static final int OPERATION_ADD = 1;
	private static final int OPERATION_UPDATE = 2;

	private static final String STORE_FILE = "shortcuts.bin";
	private static final String LOCK_SUFFIX = ".lock";
	/** Suffix of the temp file {@link AtomicFile} writes and then renames. */
	private static final String TEMP_SUFFIX = ".new";
	private static final String CORRUPT_SUFFIX = ".corrupt";
	private static final String TAG = "ShortcutManager";

	private static final Map<String, Store> sStores = new HashMap<String, Store>();
	/** One shared lock state per lock-file path, so every manager instance in the process contends on the same monitor. */
	private static final Map<String, LockState> sLocks = new HashMap<String, LockState>();

	/** Publisher state of one package. */
	private static final class Store {
		/**
		 * State used only when there is no files directory (state is then
		 * process-local and never persisted). When a files directory exists the
		 * store file is authoritative and is re-read on every access.
		 */
		LinkedHashMap<String, ShortcutInfo> dynamic =
		    new LinkedHashMap<String, ShortcutInfo>();
		/** IDs passed to {@link #reportShortcutUsed}; in-memory only, like AOSP's usage stats. */
		final Set<String> used = new HashSet<String>();
	}

	/** Result of a store load; {@code corrupt} records that recovery is needed. */
	private static final class Loaded {
		final LinkedHashMap<String, ShortcutInfo> dynamic =
		    new LinkedHashMap<String, ShortcutInfo>();
		boolean corrupt;
	}

	/** In-place operation applied to a freshly loaded copy of the store. */
	private interface Mutation {
		void apply(LinkedHashMap<String, ShortcutInfo> dynamic);
	}

	/** Critical section that runs while the store lock is held. */
	private interface LockedAction {
		void run() throws IOException;
	}

	/**
	 * Per-lock-file lock state. {@code depth} is only touched by the thread
	 * holding the monitor, which makes the monitor reentrant for nesting.
	 */
	private static final class LockState {
		int depth;
		RandomAccessFile raf;
		FileChannel channel;
		FileLock lock;
	}

	private final Context context;

	/**
	 * Package name, resolved on first use and cached. The app context - and with
	 * it its eager {@code shortcut_manager} - is constructed before the loaded
	 * app's package is installed, so calling {@link Context#getPackageName()}
	 * from the constructor dereferences the not-yet-assigned loaded app and NPEs.
	 * Resolution is therefore deferred to the first legitimate use, which always
	 * happens once the app is fully loaded.
	 */
	private String packageName;

	public ShortcutManager(Context context) {
		this.context = context;
	}

	/**
	 * The package this manager is bound to. Cached after the first successful
	 * resolution; a value that is not yet resolvable is not cached, so the call
	 * simply resolves again on a later use.
	 */
	private String packageName() {
		String resolved = packageName;
		if (resolved == null) {
			resolved = context.getPackageName();
			packageName = resolved;
		}
		return resolved;
	}

	private Store store() {
		final String pkg = packageName();
		synchronized (sStores) {
			Store store = sStores.get(pkg);
			if (store == null) {
				store = new Store();
				sStores.put(pkg, store);
			}
			return store;
		}
	}

	// --- publishing -------------------------------------------------------

	public boolean setDynamicShortcuts(List<ShortcutInfo> shortcutInfoList) {
		final List<ShortcutInfo> validated = validate(shortcutInfoList);
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				enforceShortcutCounts(dynamic, validated, OPERATION_SET);
				// Replaces all existing dynamic shortcuts, as AOSP does.
				dynamic.clear();
				for (ShortcutInfo info : validated) {
					dynamic.put(info.getId(), info);
				}
			}
		});
		return true;
	}

	public boolean addDynamicShortcuts(List<ShortcutInfo> shortcutInfoList) {
		final List<ShortcutInfo> validated = validate(shortcutInfoList);
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				enforceShortcutCounts(dynamic, validated, OPERATION_ADD);
				// Upsert by id; an existing id keeps its position in the list.
				for (ShortcutInfo info : validated) {
					dynamic.put(info.getId(), info);
				}
			}
		});
		return true;
	}

	/**
	 * Publishes one dynamic shortcut, evicting the lowest-priority shortcut of
	 * the target activity when the per-activity quota is already reached.
	 *
	 * <p>AOSP {@code ShortcutManager.pushDynamicShortcut}: "If
	 * {@link #getMaxShortcutCountPerActivity()} is already reached, an existing
	 * shortcut with the lowest rank will be removed to add space for the new
	 * shortcut. If the rank of the shortcut is not explicitly set, it will be set
	 * to zero, and shortcut will be added to the top of the list."
	 * {@code ShortcutPackage.pushDynamicShortcut} implements the eviction: the
	 * activity's shortcuts are sorted with manifest shortcuts first and then by
	 * ascending rank, and the last one (largest rank = lowest priority) is
	 * removed. Ties keep publish order. When the id already exists as a dynamic
	 * shortcut the call updates it in place and never evicts.
	 */
	public void pushDynamicShortcut(ShortcutInfo shortcut) {
		checkEntry(shortcut);
		shortcut.enforceMandatoryFields(false);
		final ShortcutInfo pushed = shortcut.clone();
		tagStored(pushed);
		if (pushed.getRank() == ShortcutInfo.RANK_NOT_SET) {
			// AOSP: an unset rank becomes 0 (top of the list).
			pushed.setRank(0);
		}
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				ShortcutInfo existing = dynamic.get(pushed.getId());
				if (existing == null || !isDynamic(existing)) {
					evictLowestPriority(dynamic, pushed.getActivity());
				}
				dynamic.put(pushed.getId(), pushed);
			}
		});
	}

	public boolean updateShortcuts(List<ShortcutInfo> shortcutInfoList) {
		if (shortcutInfoList == null) {
			throw new IllegalArgumentException("shortcut list must not be null");
		}
		// Partial update records are allowed to omit mandatory fields (AOSP fills
		// them in from the stored shortcut), so only the identity is checked here.
		final List<ShortcutInfo> incoming =
		    new ArrayList<ShortcutInfo>(shortcutInfoList);
		for (ShortcutInfo info : incoming) {
			checkEntry(info);
		}
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				enforceShortcutCounts(dynamic, incoming, OPERATION_UPDATE);
				for (ShortcutInfo info : incoming) {
					ShortcutInfo existing = dynamic.get(info.getId());
					if (existing == null) {
						// update only touches already published ids.
						continue;
					}
					existing.mergeFrom(info);
					existing.enforceMandatoryFields(false);
					tagStored(existing);
				}
			}
		});
		return true;
	}

	public void removeDynamicShortcuts(List<String> shortcutIds) {
		if (shortcutIds == null) {
			throw new IllegalArgumentException("shortcut id list must not be null");
		}
		final List<String> ids = new ArrayList<String>(shortcutIds);
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				for (String id : ids) {
					dynamic.remove(id);
				}
			}
		});
		unreportUsed(ids);
	}

	public void removeAllDynamicShortcuts() {
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				dynamic.clear();
			}
		});
		Store store = store();
		synchronized (store) {
			store.used.clear();
		}
	}

	/**
	 * Removes the given shortcuts by ID. Despite the name, the long-lived flag is
	 * not consulted: AOSP's {@code ShortcutService.removeLongLivedShortcuts}
	 * deletes every supplied ID that exists and is visible to the publisher
	 * ({@code ShortcutPackage.deleteLongLivedWithId} -&gt;
	 * {@code deleteOrDisableWithId}); the long-lived flag only decides whether a
	 * <em>pinned</em> entry survives as pinned. ATL has no launcher-side cache and
	 * no pinning, so nothing else retains them (AOSP keeps serving cached
	 * shortcuts).
	 */
	public void removeLongLivedShortcuts(List<String> shortcutIds) {
		if (shortcutIds == null) {
			throw new IllegalArgumentException("shortcut id list must not be null");
		}
		final List<String> ids = new ArrayList<String>(shortcutIds);
		mutate(new Mutation() {
			@Override
			public void apply(LinkedHashMap<String, ShortcutInfo> dynamic) {
				for (String id : ids) {
					dynamic.remove(id);
				}
			}
		});
	}

	// --- reading ----------------------------------------------------------

	public List<ShortcutInfo> getDynamicShortcuts() {
		return getShortcuts(FLAG_MATCH_DYNAMIC);
	}

	/** ATL does not parse manifest shortcuts; always empty. */
	public List<ShortcutInfo> getManifestShortcuts() {
		return new ArrayList<ShortcutInfo>();
	}

	/** ATL has no launcher pinning; always empty. */
	public List<ShortcutInfo> getPinnedShortcuts() {
		return new ArrayList<ShortcutInfo>();
	}

	public List<ShortcutInfo> getShortcuts(int matchFlags) {
		final List<ShortcutInfo> result = new ArrayList<ShortcutInfo>();
		if ((matchFlags & (FLAG_MATCH_MANIFEST | FLAG_MATCH_DYNAMIC
		    | FLAG_MATCH_PINNED | FLAG_MATCH_CACHED)) == 0) {
			// At least one MATCH flag is required, otherwise nothing is returned.
			return result;
		}
		if ((matchFlags & FLAG_MATCH_DYNAMIC) == 0) {
			// Manifest/pinned/cached shortcuts have no source in ATL.
			return result;
		}
		File file = storeFile();
		if (file == null) {
			Store store = store();
			synchronized (store) {
				// No files directory: state is process-local only.
				for (ShortcutInfo info : store.dynamic.values()) {
					result.add(info.clone());
				}
			}
			return result;
		}
		try {
			// The read runs under the same lock as the read-modify-write path so a
			// reader cannot observe a store mid-replacement and so a read never
			// races a writer's reload.
			withStoreLock(file, new LockedAction() {
				@Override
				public void run() {
					LinkedHashMap<String, ShortcutInfo> dynamic = load(file).dynamic;
					// Detached clones: callers may mutate the result freely.
					for (ShortcutInfo info : dynamic.values()) {
						result.add(info.clone());
					}
				}
			});
		} catch (IOException e) {
			// A store that cannot be locked is reported as "no state"; reads never
			// take the app down.
			Log.w(TAG, "could not lock shortcut store " + file + ": " + e);
		}
		return result;
	}

	// --- limits -----------------------------------------------------------

	public int getMaxShortcutCountPerActivity() {
		return MAX_SHORTCUT_COUNT_PER_ACTIVITY;
	}

	public int getIconMaxWidth() {
		return ICON_MAX_DIMENSIONS_PX;
	}

	public int getIconMaxHeight() {
		return ICON_MAX_DIMENSIONS_PX;
	}

	public int getRemainingCallCount() {
		return RATE_LIMIT_QUOTA;
	}

	public boolean isRateLimitingActive() {
		return false;
	}

	public void reportShortcutUsed(String shortcutId) {
		if (shortcutId == null) {
			throw new IllegalArgumentException("shortcutId must not be null");
		}
		Store store = store();
		synchronized (store) {
			store.used.add(shortcutId);
		}
	}

	// --- pinning (unsupported in ATL) -------------------------------------

	public boolean isRequestPinShortcutSupported() {
		return false;
	}

	/** ATL has no launcher integration; pinning requests are not accepted. */
	public boolean requestPinShortcut(ShortcutInfo shortcut, IntentSender resultIntent) {
		return false;
	}

	/** ATL has no launcher integration; there is no result intent to send. */
	public Intent createShortcutResultIntent(ShortcutInfo shortcut) {
		return null;
	}

	// --- validation -------------------------------------------------------

	private void checkEntry(ShortcutInfo info) {
		if (info == null) {
			throw new IllegalArgumentException("shortcut entry must not be null");
		}
		final String pkg = packageName();
		if (!pkg.equals(info.getPackage())) {
			throw new IllegalArgumentException("shortcut " + info.getId()
			    + " belongs to package " + info.getPackage() + ", not " + pkg);
		}
	}

	/** Validates a publish list and returns detached clones ready to be stored. */
	private List<ShortcutInfo> validate(List<ShortcutInfo> shortcutInfoList) {
		if (shortcutInfoList == null) {
			throw new IllegalArgumentException("shortcut list must not be null");
		}
		List<ShortcutInfo> validated = new ArrayList<ShortcutInfo>(shortcutInfoList.size());
		for (ShortcutInfo info : shortcutInfoList) {
			checkEntry(info);
			info.enforceMandatoryFields(false);
			ShortcutInfo stored = info.clone();
			tagStored(stored);
			validated.add(stored);
		}
		return validated;
	}

	/** Marks a stored record as a dynamic (and possibly long-lived) shortcut. */
	private static void tagStored(ShortcutInfo stored) {
		stored.setFlags(ShortcutInfo.FLAG_DYNAMIC
		    | (stored.isLongLived() ? ShortcutInfo.FLAG_LONG_LIVED : 0));
	}

	private static boolean isDynamic(ShortcutInfo info) {
		return (info.getFlags() & ShortcutInfo.FLAG_DYNAMIC) != 0;
	}

	/**
	 * Enforces the per-activity shortcut quota, mirroring AOSP
	 * {@code ShortcutPackage.enforceShortcutCountsBeforeOperation}: manifest and
	 * (unless the operation replaces the whole set) existing dynamic shortcuts are
	 * counted per target activity, the incoming list is then applied to the
	 * counters, and any activity above {@link #getMaxShortcutCountPerActivity()}
	 * raises {@link IllegalArgumentException} before anything is changed.
	 */
	private void enforceShortcutCounts(LinkedHashMap<String, ShortcutInfo> dynamic,
	    List<ShortcutInfo> incoming, int operation) {
		Map<ComponentName, Integer> counts = new HashMap<ComponentName, Integer>();
		if (operation != OPERATION_SET) {
			for (ShortcutInfo info : dynamic.values()) {
				increment(counts, info.getActivity(), 1);
			}
		}
		for (int i = incoming.size() - 1; i >= 0; i--) {
			ShortcutInfo info = incoming.get(i);
			ShortcutInfo original = dynamic.get(info.getId());
			ComponentName activity = info.getActivity();
			if (activity == null && original != null) {
				// Partial update record: it keeps the stored target activity.
				activity = original.getActivity();
			}
			if (activity == null) {
				// Publish enforces a non-null activity; nothing to count.
				continue;
			}
			if (original == null) {
				if (operation == OPERATION_UPDATE) {
					// Updating only touches already published ids.
					continue;
				}
				increment(counts, activity, 1);
				continue;
			}
			if (operation != OPERATION_SET) {
				increment(counts, original.getActivity(), -1);
			}
			increment(counts, activity, 1);
		}
		for (Integer count : counts.values()) {
			if (count != null && count > MAX_SHORTCUT_COUNT_PER_ACTIVITY) {
				throw new IllegalArgumentException(
				    "Max number of dynamic shortcuts exceeded");
			}
		}
	}

	private static void increment(Map<ComponentName, Integer> counts,
	    ComponentName activity, int delta) {
		Integer old = counts.get(activity);
		counts.put(activity, (old == null ? 0 : old) + delta);
	}

	/**
	 * Removes the lowest-priority dynamic shortcut of {@code activity} when it is
	 * at capacity. AOSP {@code ShortcutPackage#pushDynamicShortcut} sorts the
	 * activity's shortcuts manifest-first then by ascending rank and removes the
	 * last element; ATL has no manifest shortcuts and never touches ranks of
	 * stored shortcuts, so "the largest rank, publish order breaking ties"
	 * selects the same victim.
	 */
	private void evictLowestPriority(LinkedHashMap<String, ShortcutInfo> dynamic,
	    ComponentName activity) {
		List<ShortcutInfo> members = new ArrayList<ShortcutInfo>();
		for (ShortcutInfo info : dynamic.values()) {
			ComponentName target = info.getActivity();
			if (target == null ? activity == null : target.equals(activity)) {
				members.add(info);
			}
		}
		if (members.size() < MAX_SHORTCUT_COUNT_PER_ACTIVITY) {
			return;
		}
		ShortcutInfo victim = null;
		for (ShortcutInfo info : members) {
			if (victim == null || info.getRank() >= victim.getRank()) {
				victim = info;
			}
		}
		if (victim != null) {
			dynamic.remove(victim.getId());
		}
	}

	// --- persistence ------------------------------------------------------

	/**
	 * ATL's {@link Context#getFilesDir()} is one directory shared by every package
	 * loaded in the process (AOSP has per-package app storage), so the package name
	 * is part of the store file name to keep packages isolated on disk as well as
	 * in {@link #sStores}.
	 */
	private File storeFile() {
		File dir = context.getFilesDir();
		if (dir == null) {
			return null;
		}
		return new File(dir, STORE_FILE + "." + packageName() + ".bin");
	}

	private void unreportUsed(List<String> ids) {
		Store store = store();
		synchronized (store) {
			store.used.removeAll(ids);
		}
	}

	/**
	 * Serialised read-modify-write of the package's store file.
	 *
	 * <p>The current state is always re-read from the file while holding the
	 * store lock, so a write from another process (or another manager instance)
	 * is never silently clobbered. The new state is committed by an atomic file
	 * replacement, and {@code store.dynamic} is only updated once that
	 * replacement has succeeded - a failure leaves both the in-memory state and
	 * the previous file untouched and surfaces as
	 * {@link IllegalStateException}.
	 */
	private void mutate(final Mutation mutation) {
		final Store store = store();
		final File file = storeFile();
		if (file == null) {
			synchronized (store) {
				LinkedHashMap<String, ShortcutInfo> next =
				    new LinkedHashMap<String, ShortcutInfo>(store.dynamic);
				mutation.apply(next);
				store.dynamic = next;
			}
			return;
		}
		try {
			withStoreLock(file, new LockedAction() {
				@Override
				public void run() throws IOException {
					Loaded loaded = load(file);
					if (loaded.corrupt) {
						// Defined recovery: keep the unreadable bytes aside, then
						// let this write install a fresh valid store.
						quarantine(file);
					}
					mutation.apply(loaded.dynamic);
					write(file, loaded.dynamic);
					synchronized (store) {
						store.dynamic = loaded.dynamic;
					}
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("ShortcutManager could not persist "
			    + file + "; the previous state was kept", e);
		}
	}

	/**
	 * Runs {@code action} while holding the OS-managed store lock.
	 *
	 * <p>The lock lives in a stable sibling file {@code <store>.lock} that is
	 * created here if missing and is never deleted or replaced, so its inode -
	 * and therefore the lock identity - is stable. Same-process threads
	 * serialise on a per-path monitor (also giving the same process a working
	 * reentrant path and avoiding {@code OverlappingFileLockException});
	 * different processes serialise on the blocking whole-file
	 * {@link FileChannel#lock()}. There is no timeout and no staleness
	 * reclamation: a live holder keeps the lock for as long as it needs it, and
	 * a holder that dies is released by the kernel.
	 */
	private static void withStoreLock(File storeFile, LockedAction action) throws IOException {
		File lockFile = new File(storeFile.getPath() + LOCK_SUFFIX);
		File dir = lockFile.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs()) {
			throw new IOException("could not create " + dir);
		}
		String key = lockFile.getPath();
		LockState state;
		synchronized (sLocks) {
			state = sLocks.get(key);
			if (state == null) {
				state = new LockState();
				sLocks.put(key, state);
			}
		}
		synchronized (state) {
			if (state.depth == 0) {
				RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
				FileChannel channel = raf.getChannel();
				try {
					state.raf = raf;
					state.channel = channel;
					// Whole-file blocking lock; released by closeLock() only when
					// the outermost holder exits, or by the kernel if we die.
					state.lock = channel.lock();
				} catch (IOException e) {
					closeLock(state);
					throw e;
				} catch (RuntimeException e) {
					closeLock(state);
					throw e;
				}
				state.depth = 1;
			} else {
				state.depth++;
			}
			try {
				action.run();
			} finally {
				if (--state.depth == 0) {
					closeLock(state);
				}
			}
		}
	}

	/** Releases the OS lock and closes the descriptor for the outermost holder. */
	private static void closeLock(LockState state) {
		try {
			if (state.lock != null) {
				state.lock.release();
			}
		} catch (IOException e) {
			Log.w(TAG, "could not release shortcut store lock: " + e);
		}
		try {
			if (state.channel != null) {
				state.channel.close();
			}
		} catch (IOException e) {
			Log.w(TAG, "could not close shortcut store lock channel: " + e);
		}
		try {
			if (state.raf != null) {
				state.raf.close();
			}
		} catch (IOException e) {
			Log.w(TAG, "could not close shortcut store lock file: " + e);
		}
		state.lock = null;
		state.channel = null;
		state.raf = null;
	}

	/** Best-effort: move an unreadable store aside so the next write starts clean. */
	private static void quarantine(File file) {
		File corrupt = new File(file.getPath() + CORRUPT_SUFFIX);
		try {
			corrupt.delete();
			if (!file.renameTo(corrupt)) {
				Log.w(TAG, "could not quarantine corrupt shortcut store " + file);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "could not quarantine corrupt shortcut store " + file + ": " + e);
		}
	}

	/**
	 * Decodes the store. A missing or empty file yields empty state; a file that
	 * does not decode yields empty state with {@link Loaded#corrupt} set (the
	 * caller decides whether to quarantine). Never throws.
	 */
	private static Loaded load(File file) {
		Loaded loaded = new Loaded();
		if (file == null || !file.exists() || file.length() == 0L) {
			return loaded;
		}
		byte[] bytes;
		try {
			bytes = readFile(file);
		} catch (IOException e) {
			Log.w(TAG, "could not read shortcut store " + file + ": " + e);
			loaded.corrupt = true;
			return loaded;
		}
		Parcel parcel = Parcel.obtain();
		try {
			parcel.unmarshall(bytes, 0, bytes.length);
			parcel.setDataPosition(0);
			if (bytes.length < 12 || parcel.readInt() != STORE_MAGIC) {
				throw new IllegalArgumentException("not an ATL shortcut store");
			}
			int version = parcel.readInt();
			if (version == STORE_VERSION || version == 3) {
				decodeCurrent(parcel, loaded.dynamic, version >= 4);
			} else if (version == STORE_VERSION_LEGACY) {
				decodeLegacy(parcel, loaded.dynamic);
			} else {
				throw new IllegalArgumentException("unsupported shortcut store version " + version);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "ignoring unreadable shortcut store " + file + ": " + e);
			loaded.dynamic.clear();
			loaded.corrupt = true;
		} finally {
			parcel.recycle();
		}
		return loaded;
	}

	/** Decodes a version-3 or version-4 store: typed payload framed by a length and a CRC. */
	private static void decodeCurrent(Parcel parcel, LinkedHashMap<String, ShortcutInfo> out, boolean withExtras) {
		int payloadLength = parcel.readInt();
		if (payloadLength < 0 || payloadLength > parcel.dataAvail()) {
			throw new IllegalArgumentException("bad shortcut store payload length " + payloadLength);
		}
		byte[] payload = new byte[payloadLength];
		for (int i = 0; i < payloadLength; i++) {
			payload[i] = parcel.readByte();
		}
		int expectedCrc = parcel.readInt();
		CRC32 crc = new CRC32();
		crc.update(payload);
		if ((int) crc.getValue() != expectedCrc) {
			throw new IllegalArgumentException("shortcut store checksum mismatch");
		}
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
		try {
			int count = in.readInt();
			if (count < 0 || count > MAX_STORE_ENTRIES) {
				throw new IllegalArgumentException("bad shortcut count " + count);
			}
			for (int i = 0; i < count; i++) {
				ShortcutInfo info = ShortcutInfo.readTypedFrom(in, withExtras);
				if (info.getId() == null) {
					throw new IllegalArgumentException("shortcut without id");
				}
				out.put(info.getId(), info);
			}
			if (parcel.dataAvail() != 0 || in.read() != -1) {
				throw new IllegalArgumentException("trailing bytes in shortcut store payload");
			}
		} catch (IOException e) {
			throw new IllegalArgumentException("truncated shortcut store payload", e);
		}
	}

	/**
	 * Decodes a version-2 (legacy) store. The record bytes are Parcel-encoded and
	 * are read with {@link ShortcutInfo#CREATOR}, the exact decoder the previous
	 * format wrote with; this is the only Parcel-coded record path and it is
	 * read-only. The next write re-emits the state in version 4.
	 */
	private static void decodeLegacy(Parcel parcel, LinkedHashMap<String, ShortcutInfo> out) {
		int count = parcel.readInt();
		if (count < 0 || count > MAX_STORE_ENTRIES) {
			throw new IllegalArgumentException("bad shortcut count " + count);
		}
		for (int i = 0; i < count; i++) {
			ShortcutInfo info = ShortcutInfo.CREATOR.createFromParcel(parcel);
			if (info.getId() == null) {
				throw new IllegalArgumentException("shortcut without id");
			}
			out.put(info.getId(), info);
		}
	}

	/** Encodes a version-4 store (see the class comment for the layout). */
	private static byte[] encode(LinkedHashMap<String, ShortcutInfo> dynamic) throws IOException {
		ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
		DataOutputStream payload = new DataOutputStream(payloadBuffer);
		payload.writeInt(dynamic.size());
		for (ShortcutInfo info : dynamic.values()) {
			info.writeTypedTo(payload);
		}
		payload.flush();
		byte[] payloadBytes = payloadBuffer.toByteArray();
		CRC32 crc = new CRC32();
		crc.update(payloadBytes);

		Parcel parcel = Parcel.obtain();
		try {
			parcel.writeInt(STORE_MAGIC);
			parcel.writeInt(STORE_VERSION);
			parcel.writeByteArray(payloadBytes);
			parcel.writeInt((int) crc.getValue());
			return parcel.marshall();
		} finally {
			parcel.recycle();
		}
	}

	/**
	 * Replaces the store file atomically: the new bytes go to
	 * {@code <store>.new}, are fsync'd, and are then renamed over the
	 * destination, so a concurrent reader sees either the old or the new
	 * complete file.
	 *
	 * <p>{@link AtomicFile#finishWrite} only logs a failed {@code rename(2)}, and
	 * a length comparison is not a sufficient witness either: an unchanged file
	 * that happens to have the same length as the new bytes passes it and the
	 * caller would report a commit that never happened. The replacement is
	 * therefore proven under the store lock: the temp file must be gone (the
	 * rename consumed it) and the destination must contain exactly the bytes
	 * that were just written. Otherwise the uncommitted temp file is dropped and
	 * an {@link IOException} is raised, leaving the previous store in place so
	 * the caller rolls back.
	 */
	private static void write(File file, LinkedHashMap<String, ShortcutInfo> dynamic)
	    throws IOException {
		byte[] bytes = encode(dynamic);
		File temp = new File(file.getPath() + TEMP_SUFFIX);
		AtomicFile atomic = new AtomicFile(file);
		java.io.FileOutputStream out = null;
		try {
			out = atomic.startWrite();
			out.write(bytes);
			atomic.finishWrite(out);
			out = null;
		} catch (IOException e) {
			atomic.failWrite(out);
			throw e;
		}
		if (temp.exists() || !contentEquals(file, bytes)) {
			temp.delete();
			throw new IOException("shortcut store " + file
			    + " was not atomically replaced; the previous state was kept");
		}
	}

	/**
	 * Byte-exact comparison of a file against {@code expected}, read in fixed
	 * chunks so no second copy of the store is allocated.
	 */
	private static boolean contentEquals(File file, byte[] expected) {
		FileInputStream in;
		try {
			in = new FileInputStream(file);
		} catch (IOException e) {
			return false;
		}
		try {
			byte[] chunk = new byte[4096];
			int offset = 0;
			while (offset < expected.length) {
				int want = Math.min(chunk.length, expected.length - offset);
				int read = 0;
				while (read < want) {
					int count = in.read(chunk, read, want - read);
					if (count < 0) {
						break;
					}
					read += count;
				}
				if (read < want) {
					return false;
				}
				for (int i = 0; i < want; i++) {
					if (chunk[i] != expected[offset + i]) {
						return false;
					}
				}
				offset += want;
			}
			return in.read() < 0;
		} catch (IOException e) {
			return false;
		} finally {
			try {
				in.close();
			} catch (IOException e) {
				// The comparison result is already determined.
			}
		}
	}

	private static byte[] readFile(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} finally {
			in.close();
		}
	}
}