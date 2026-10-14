// SPDX-License-Identifier: Apache-2.0
//
// Focused contract/race/migration probe for the ATL ShortcutManager/ShortcutInfo
// durable state. See tests/shortcut-manager/README.md for how it is driven
// (single-process checks, the cross-process lock modes, SIGKILL and migration)
// and for provenance of the committed legacy-v2 fixture.
//
// Single source that runs on both ATL and an AOSP reference emulator: ATL-only
// storage/race checks are guarded by isAtl() so the same APK can be compared
// against API 37 for the public semantics.
package org.atl.shortcutmanager;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Prints {@code SHORTCUT_MANAGER PASS checks=N} and writes
 * {@code getFilesDir()/results.json} on success, or a FAIL/CRASH line otherwise.
 */
public class Probe extends Activity {
	private static final int STORE_MAGIC = 0x41544C53;
	private static final String CATEGORY = "org.atl.shortcutmanager.category";
	/**
	 * Probe-owned copy of the migrated v3 store, written by {@code migrate} and
	 * compared field by field by {@code verify-migrated} after the restart (the
	 * store itself is already v3 by then, so the legacy fixture bytes are gone).
	 */
	private static final String MIGRATION_SNAPSHOT = "shortcuts.migrated.snapshot.bin";

	private int checks;
	private boolean atl;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		atl = isAtl();
		try {
			String mode = control("mode");
			if ("hold".equals(mode)) {
				runHold();
			} else if ("contend".equals(mode)) {
				runContend();
			} else if ("peer".equals(mode)) {
				runPeer();
			} else if ("verify".equals(mode)) {
				runVerifyPeers();
			} else if ("migrate".equals(mode)) {
				runMigrate();
			} else if ("verify-migrated".equals(mode)) {
				runVerifyMigrated();
			} else if ("crashloop".equals(mode)) {
				runCrashLoop();
			} else if ("verify-store".equals(mode)) {
				runVerifyStore();
			} else if ("recover".equals(mode)) {
				runRecover();
			} else {
				runChecks();
			}
			System.out.println("SHORTCUT_MANAGER PASS checks=" + checks);
			try {
				writeAll(new File(getFilesDir(), "results.json"),
				    ("{\"status\":\"PASS\",\"checks\":" + checks + "}").getBytes("UTF-8"));
			} catch (Throwable ignored) {
			}
		} catch (Throwable t) {
			System.out.println("SHORTCUT_MANAGER FAIL " + t);
			t.printStackTrace(System.out);
			try {
				writeAll(new File(getFilesDir(), "results.json"),
				    ("{\"status\":\"FAIL\",\"checks\":" + checks + ",\"error\":\""
				        + t + "\"}").getBytes("UTF-8"));
			} catch (Throwable ignored) {
			}
		}
		// Runs for both result paths (the PASS write above and the FAIL write in
		// the catch): the marker and results.json must be readable by the runner
		// before this process disappears.
		stayAliveForResultCapture();
		finish();
	}

	/**
	 * Keeps this probe process alive briefly after the marker has been printed
	 * and results.json written, so the shared runner can collect them.
	 *
	 * The runner (tools/run-atl-probe-lane.sh) launches the app, sleeps a fixed
	 * one second and then only checks whether the launcher is still alive; if it
	 * has already exited it reports {@code STATUS=INTERNAL_ERROR} immediately,
	 * without ever looking at the stdout marker or results.json. Fast finite
	 * modes therefore produce a valid PASS/FAIL result that the wrapper would
	 * otherwise discard as a false negative. The delay is purely to outlive that
	 * liveness check - it changes no assertion, marker or file content. The
	 * hold/crashloop scenarios never reach result finalization (they stay in
	 * their own loop), so they are unaffected.
	 */
	private void stayAliveForResultCapture() {
		try {
			Thread.sleep(1500L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// --- single-process contract + ATL store checks --------------------------

	private void runChecks() throws Throwable {
		final ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		check(sm != null);

		if (atl) {
			// --- process restart persistence ---------------------------------
			// This mode is also driven twice in a row against the same app-data
			// directory (README: write/read across two fresh probe launches, no
			// clearing). The second, fresh process must load the store the first
			// process committed instead of starting from empty state. The
			// manager's initial view is compared with an independent decode of
			// the durable file, so a silently empty reload cannot pass. Skipped
			// when no store exists yet (first launch in a fresh data dir).
			File persistedStore = storeFile();
			if (persistedStore.exists() && persistedStore.length() > 12) {
				byte[] persisted = readAll(persistedStore);
				List<String> persistedIds = isLegacy(persisted)
				    ? decodeV2Ids(persisted) : decodeV3Ids(persisted);
				check(!persistedIds.isEmpty());
				check(sameStrings(ids(sm.getDynamicShortcuts()),
				    persistedIds.toArray(new String[0])));
				System.out.println("SHORTCUT_MANAGER NOTE restart_persisted_ids="
				    + joinIds(persistedIds));
			}
		}

		// --- ordinary set/add/update/remove/lookup ---------------------------
		check(sm.setDynamicShortcuts(list(shortcut("a", "A"), shortcut("c", "C"),
		    shortcut("e", "E"), shortcut("f", "F"))));
		check(sameIds(sm.getDynamicShortcuts(), "a,c,e,f"));
		check(sm.addDynamicShortcuts(list(shortcut("b", "B"))));
		if (atl) {
			check(sameIds(sm.getDynamicShortcuts(), "a,c,e,f,b"));
		}
		check(sm.addDynamicShortcuts(list(shortcut("a", "A2"))));
		check(same("A2", find(sm.getDynamicShortcuts(), "a").getShortLabel()));
		check(sm.updateShortcuts(list(builder("c").setShortLabel("C2").build())));
		List<ShortcutInfo> afterUpdate = sm.getDynamicShortcuts();
		check(same("C2", find(afterUpdate, "c").getShortLabel()));
		check(same("C long", find(afterUpdate, "c").getLongLabel()));
		check(same("C disabled", find(afterUpdate, "c").getDisabledMessage()));
		check(find(afterUpdate, "c").getActivity() != null);
		check(find(afterUpdate, "c").getIntents().length == 1);
		// an id that was never published is ignored by update
		check(sm.updateShortcuts(list(builder("never").setShortLabel("N").build())));
		check(find(sm.getDynamicShortcuts(), "never") == null);
		sm.removeDynamicShortcuts(idList("b", "e"));
		check(sameIds(sm.getDynamicShortcuts(), "a,c,f"));
		check(sm.setDynamicShortcuts(list(shortcut("k", "K"))));
		sm.removeAllDynamicShortcuts();
		check(sm.getDynamicShortcuts().isEmpty());

		// --- required-field validation ---------------------------------------
		check(throwsIAE(new Run() {
			@Override
			public void run() {
				sm.setDynamicShortcuts(null);
			}
		}));
		check(throwsIAE(new Run() {
			@Override
			public void run() {
				sm.setDynamicShortcuts(list(builder("nolabel").setActivity(activity())
				    .setIntent(intent()).build()));
			}
		}));
		check(throwsIAE(new Run() {
			@Override
			public void run() {
				sm.setDynamicShortcuts(list(builder("nointent").setActivity(activity())
				    .setShortLabel("x").build()));
			}
		}));
		if (atl) {
			// Observed on the API-37 reference emulator: a shortcut without an
			// explicit target activity is accepted (a default activity is filled
			// in) rather than rejected, so this ATL-only check has no reference
			// counterpart. The publisher-side validation lives in ATL's
			// ShortcutManager; the AOSP files inspected for this probe are only
			// the two core/content/pm ones named in README.md.
			check(throwsIAE(new Run() {
				@Override
				public void run() {
					sm.setDynamicShortcuts(list(builder("noactivity").setShortLabel("x")
					    .setIntent(intent()).build()));
				}
			}));
		}
		if (atl) {
			check(throwsIAE(new Run() {
				@Override
				public void run() throws Throwable {
					// publisher identity mismatch: foreign Builder context
					Context system = createPackageContext("android", 0);
					sm.setDynamicShortcuts(list(new ShortcutInfo.Builder(system, "foreign")
					    .setActivity(activity()).setShortLabel("x").setIntent(intent()).build()));
				}
			}));
		}

		// --- per-activity quota ----------------------------------------------
		final int max = sm.getMaxShortcutCountPerActivity();
		List<ShortcutInfo> full = new ArrayList<ShortcutInfo>();
		for (int i = 0; i < max; i++) {
			full.add(shortcut("q" + i, "Q" + i));
		}
		check(sm.setDynamicShortcuts(full));
		check(sm.getDynamicShortcuts().size() == max);
		check(throwsIAE(new Run() {
			@Override
			public void run() {
				List<ShortcutInfo> over = new ArrayList<ShortcutInfo>(sm.getDynamicShortcuts());
				over.add(shortcut("q-over", "Over"));
				sm.setDynamicShortcuts(over);
			}
		}));
		check(throwsIAE(new Run() {
			@Override
			public void run() {
				List<ShortcutInfo> over = new ArrayList<ShortcutInfo>(sm.getDynamicShortcuts());
				over.add(shortcut("q-over2", "Over2"));
				sm.addDynamicShortcuts(over);
			}
		}));
		check(sm.getDynamicShortcuts().size() == max);
		check(sm.updateShortcuts(list(builder("q0").setShortLabel("Q0b").build())));
		check(sm.getDynamicShortcuts().size() == max);

		// --- pushDynamicShortcut evicts lowest priority ----------------------
		List<ShortcutInfo> ranked = new ArrayList<ShortcutInfo>();
		for (int i = 0; i < max; i++) {
			ranked.add(builder("p" + i).setActivity(activity()).setShortLabel("P" + i)
			    .setIntent(intent()).setRank(i).build());
		}
		check(sm.setDynamicShortcuts(ranked));
		String before = joinIds(ids(sm.getDynamicShortcuts()));
		sm.pushDynamicShortcut(builder("pnew").setActivity(activity()).setShortLabel("PNew")
		    .setIntent(intent()).build());
		List<ShortcutInfo> afterPush = sm.getDynamicShortcuts();
		check(afterPush.size() == max);
		check(find(afterPush, "p" + (max - 1)) == null);
		check(find(afterPush, "pnew") != null && find(afterPush, "pnew").getRank() == 0);
		System.out.println("SHORTCUT_MANAGER NOTE push_before=" + before + " after="
		    + joinIds(ids(afterPush)));

		// --- long-lived removal ----------------------------------------------
		check(sm.setDynamicShortcuts(list(builder("ll").setActivity(activity())
		    .setShortLabel("LL").setIntent(intent()).setLongLived(true).build(),
		    shortcut("nl", "NL"))));
		sm.removeLongLivedShortcuts(idList("ll", "nl"));
		// AOSP removes every supplied ID irrespective of the long-lived flag:
		// ShortcutService.removeLongLivedShortcuts -> ShortcutPackage
		// .deleteLongLivedWithId -> deleteOrDisableWithId force-deletes any
		// supplied, non-pinned dynamic shortcut (android-17.0.0_r1,
		// 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d).
		check(sm.getDynamicShortcuts().isEmpty());

		// --- detached return values ------------------------------------------
		check(sm.setDynamicShortcuts(list(shortcut("a", "A"), shortcut("c", "C"),
		    shortcut("e", "E"), shortcut("f", "F"))));
		sm.getDynamicShortcuts().clear();
		check(sameIds(sm.getDynamicShortcuts(), "a,c,e,f"));
		Intent[] intents = sm.getDynamicShortcuts().get(0).getIntents();
		intents[0].setAction("org.atl.mutated");
		intents[0] = new Intent("org.atl.replaced");
		check(Intent.ACTION_VIEW.equals(sm.getDynamicShortcuts().get(0).getIntents()[0].getAction()));

		// --- parcel round trip -----------------------------------------------
		ShortcutInfo source = sm.getDynamicShortcuts().get(0);
		Parcel parcel = Parcel.obtain();
		source.writeToParcel(parcel, 0);
		byte[] raw = parcel.marshall();
		parcel.recycle();
		Parcel restoredParcel = Parcel.obtain();
		restoredParcel.unmarshall(raw, 0, raw.length);
		restoredParcel.setDataPosition(0);
		ShortcutInfo restored = ShortcutInfo.CREATOR.createFromParcel(restoredParcel);
		restoredParcel.recycle();
		check(restored != null && source.getId().equals(restored.getId()));
		check(source.getPackage().equals(restored.getPackage()));
		check(same(source.getShortLabel(), restored.getShortLabel()));
		check(restored.getIntents().length == 1
		    && Intent.ACTION_VIEW.equals(restored.getIntents()[0].getAction()));

		// --- extras surface (API 26, used by androidx ShortcutInfoCompat) -----
		Method setExtras;
		Method getExtras;
		try {
			setExtras = ShortcutInfo.Builder.class.getMethod("setExtras", PersistableBundle.class);
			getExtras = ShortcutInfo.class.getMethod("getExtras");
		} catch (NoSuchMethodException e) {
			throw new AssertionError("extras surface missing: " + e);
		}
		PersistableBundle pb = new PersistableBundle();
		pb.putString("atl-probe", "1");
		ShortcutInfo.Builder eb = builder("extras-1").setActivity(activity())
		    .setShortLabel("Extras").setIntent(intent());
		setExtras.invoke(eb, pb);
		Object got = getExtras.invoke(eb.build());
		check(got instanceof PersistableBundle
		    && "1".equals(((PersistableBundle) got).getString("atl-probe")));

		// --- a fresh manager for the same package reads the same state -------
		Context samePackage = createPackageContext(getPackageName(), 0);
		ShortcutManager fresh = (ShortcutManager) samePackage.getSystemService("shortcut");
		check(fresh != null);
		check(sameIds(fresh.getDynamicShortcuts(), "a,c,e,f"));

		// --- package isolation -----------------------------------------------
		Context system = createPackageContext("android", 0);
		ShortcutManager other = (ShortcutManager) system.getSystemService("shortcut");
		check(other != null);
		if (atl) {
			// Discrimination, not a vacuous emptiness check: an earlier run of this
			// probe can leave shortcuts in the android package's own store (it is a
			// separate file in the same process data dir), so clear it first to keep
			// repeated runs deterministic.
			other.removeAllDynamicShortcuts();
			check(other.getDynamicShortcuts().isEmpty());

			// Publish through that package's manager using an identity derived from
			// its context. ATL validates only the package identity, so the shortcut
			// must be accepted into android's store - which both proves the manager
			// is live rather than inert, and that it is what the following package
			// separation is measured against.
			final String otherPkg = system.getPackageName();
			final String otherClass = otherPkg + ".ShortcutProbeActivity";
			check(other.setDynamicShortcuts(list(new ShortcutInfo.Builder(system, "system-only")
			    .setActivity(new ComponentName(otherPkg, otherClass))
			    .setShortLabel("System only")
			    .setIntent(new Intent(Intent.ACTION_VIEW)
			        .setClassName(otherPkg, otherClass).setPackage(otherPkg))
			    .build())));
			check(sameIds(other.getDynamicShortcuts(), "system-only"));

			// ...and none of it may be visible to the calling app's package.
			check(sameIds(sm.getDynamicShortcuts(), "a,c,e,f"));
		} else {
			System.out.println("SHORTCUT_MANAGER NOTE other_package_ids="
			    + joinIds(safe(other)));
		}

		if (atl) {
			runStoreChecks(sm);
		}
	}

	// --- ATL-only durable-store checks ---------------------------------------

	private void runStoreChecks(ShortcutManager sm) throws Throwable {
		File store = storeFile();
		check(store != null);
		check(sm.setDynamicShortcuts(list(shortcut("s1", "S1"), shortcut("s2", "S2"))));

		// --- typed version-3 schema (independent decoder) --------------------
		byte[] bytes = readAll(store);
		check(storeVersion(bytes) == 3);
		check(!isLegacy(bytes));
		check(sameStrings(decodeV3Ids(bytes), new String[] {"s1", "s2"}));
		System.out.println("SHORTCUT_MANAGER NOTE store_bytes=" + bytes.length
		    + " version=" + storeVersion(bytes));

		// --- atomic replacement leaves no temp file; lock file persists ------
		check(!new File(store.getPath() + ".new").exists());

		// --- injected failing write preserves prior durable state ------------
		// Obstruct <store>.new with a non-empty directory so the atomic write
		// cannot even create its temp file. The previous durable state must
		// survive and the failure must surface as IllegalStateException.
		File obstruction = new File(store.getPath() + ".new");
		check(obstruction.mkdirs());
		writeAll(new File(obstruction, "keep"), new byte[] {1});
		boolean failed = false;
		try {
			sm.addDynamicShortcuts(list(shortcut("s3", "S3")));
		} catch (IllegalStateException expected) {
			failed = true;
		}
		check(failed);
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"s1", "s2"}));
		check(sameIds(sm.getDynamicShortcuts(), "s1,s2"));
		check(new File(obstruction, "keep").delete() && obstruction.delete());
		check(sm.addDynamicShortcuts(list(shortcut("s3", "S3"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"s1", "s2", "s3"}));
		check(!new File(store.getPath() + ".new").exists());

		// --- same-length rename failure must not be reported as success ------
		// AtomicFile.finishWrite() only *logs* a failed rename(2), and a length
		// check cannot tell an unchanged old file from the new one when both
		// have the same size - exactly the case where a lost update would be
		// reported as a commit. Force that case: two states whose encodings
		// differ but are the same length, and a rename(2) that fails while the
		// destination keeps its bytes. No privileges are needed: the store
		// directory is made non-writable (EACCES on rename) *after* the temp
		// file has been created writable, so the temp write itself succeeds.
		File storeDir = store.getParentFile();
		File temp = new File(store.getPath() + ".new");
		check(storeDir != null);
		check(lockFile().exists() || lockFile().createNewFile());
		check(sm.setDynamicShortcuts(list(shortcut("rf", "AAAA"))));
		byte[] stateA = readAll(store);
		check(sm.setDynamicShortcuts(list(shortcut("rf", "BBBB"))));
		byte[] stateB = readAll(store);
		check(stateA.length == stateB.length);
		check(!Arrays.equals(stateA, stateB));
		check(sm.setDynamicShortcuts(list(shortcut("rf", "AAAA"))));
		check(Arrays.equals(readAll(store), stateA));
		writeAll(temp, new byte[] {0});
		setDirWritable(storeDir, false);
		boolean renameFailed = false;
		try {
			sm.setDynamicShortcuts(list(shortcut("rf", "BBBB")));
		} catch (IllegalStateException expected) {
			renameFailed = true;
		}
		System.out.println("SHORTCUT_MANAGER NOTE same_length_rename_failed=" + renameFailed
		    + " previous_bytes_kept=" + Arrays.equals(readAll(store), stateA));
		check(renameFailed);
		check(Arrays.equals(readAll(store), stateA));
		check(same("AAAA", find(sm.getDynamicShortcuts(), "rf").getShortLabel()));
		setDirWritable(storeDir, true);
		// The uncommitted temp file survived (the directory was not writable,
		// so it could not be removed); the next real commit consumes it.
		check(temp.exists());
		check(sm.setDynamicShortcuts(list(shortcut("rf", "BBBB"))));
		check(Arrays.equals(readAll(store), stateB));
		check(!temp.exists());

		// --- interrupted write: partial temp, previous base intact -----------
		// A crash between the temp write and the rename leaves a partial,
		// non-decodable <store>.new. It must never be adopted: the previous base
		// stays readable and the next commit overwrites the partial temp file.
		byte[] base = readAll(store);
		writeAll(temp, Arrays.copyOf(base, Math.max(1, base.length / 3)));
		check(sameIds(sm.getDynamicShortcuts(), "rf"));
		check(Arrays.equals(readAll(store), base));
		check(sm.addDynamicShortcuts(list(shortcut("t1", "T1"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"rf", "t1"}));
		check(!temp.exists());

		// --- corruption / truncation / unknown version recovery --------------
		byte[] good = readAll(store);
		// garbage
		writeAll(store, "not a shortcut store at all".getBytes("UTF-8"));
		check(sm.getDynamicShortcuts().isEmpty());
		// next write recovers and quarantines the unreadable bytes
		check(sm.setDynamicShortcuts(list(shortcut("r1", "R1"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"r1"}));
		check(new File(store.getPath() + ".corrupt").exists());
		new File(store.getPath() + ".corrupt").delete();
		// truncated current store
		writeAll(store, Arrays.copyOf(good, good.length / 2));
		check(sm.getDynamicShortcuts().isEmpty());
		check(sm.setDynamicShortcuts(list(shortcut("r2", "R2"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"r2"}));
		new File(store.getPath() + ".corrupt").delete();
		// unknown version still carries the magic
		byte[] unknown = withStoreInt(good, 4, 99);
		writeAll(store, unknown);
		check(sm.getDynamicShortcuts().isEmpty());
		check(sm.setDynamicShortcuts(list(shortcut("r3", "R3"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"r3"}));
		new File(store.getPath() + ".corrupt").delete();
		// checksum mismatch with a still-parseable payload
		byte[] tampered = withStoreInt(good, good.length - 4, 0x11223344);
		writeAll(store, tampered);
		check(sm.getDynamicShortcuts().isEmpty());
		check(sm.setDynamicShortcuts(list(shortcut("r4", "R4"))));
		check(sameStrings(decodeV3Ids(readAll(store)), new String[] {"r4"}));
		new File(store.getPath() + ".corrupt").delete();

		// leave a known state for the following modes
		check(sm.setDynamicShortcuts(list(shortcut("z1", "Z1"), shortcut("z2", "Z2"))));
	}

	// --- migration from the committed legacy v2 fixture ----------------------

	private void runMigrate() throws Throwable {
		ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		File store = storeFile();
		new File(store.getPath() + ".corrupt").delete();
		check(store.exists() && store.length() > 12);
		byte[] fixture = readAll(store);
		check(isLegacy(fixture));
		List<Record> legacy = decodeV2Records(fixture);
		check(legacy.size() == 14);
		List<String> expected = idsOfRecords(legacy);
		System.out.println("SHORTCUT_MANAGER NOTE migrate_fixture_ids=" + expected);
		check(sameStrings(ids(sm.getDynamicShortcuts()), expected.toArray(new String[0])));
		// one write converts the store to the typed version while preserving state
		check(sm.addDynamicShortcuts(list(shortcut("migrated", "Migrated"))));
		byte[] now = readAll(store);
		check(storeVersion(now) == 3);
		check(!isLegacy(now));
		List<Record> migrated = decodeV3Records(now);
		List<String> after = idsOfRecords(migrated);
		check(after.size() == 15 && after.containsAll(expected) && after.contains("migrated"));
		check(!new File(store.getPath() + ".corrupt").exists());
		check(sameStrings(ids(sm.getDynamicShortcuts()), after.toArray(new String[0])));
		// Every field the legacy fixture encodes is compared before (v2) and
		// after (v3) the migration. The entries are matched by id, so the
		// appended "migrated" entry cannot shift the comparison. Neither format
		// persists icon or extras, so those are the only ShortcutInfo state not
		// covered here (see README).
		List<Record> migratedLegacy = new ArrayList<Record>();
		for (Record record : legacy) {
			Record match = findRecord(migrated, record.id);
			check(match != null);
			migratedLegacy.add(match);
		}
		String diff = diffRecords(legacy, migratedLegacy);
		if (diff != null) {
			System.out.println("SHORTCUT_MANAGER NOTE migrate_field_diff=" + diff);
		}
		System.out.println("SHORTCUT_MANAGER NOTE migrate_fields_compared entries=" + legacy.size()
		    + " fields=" + RECORD_FIELD_NAMES);
		check(diff == null);
		// Snapshot the committed v3 store for the post-restart field comparison.
		writeAll(new File(getFilesDir(), MIGRATION_SNAPSHOT), now);
	}

	private void runVerifyMigrated() throws Throwable {
		ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		File store = storeFile();
		byte[] now = readAll(store);
		check(storeVersion(now) == 3);
		List<Record> live = decodeV3Records(now);
		List<String> present = ids(sm.getDynamicShortcuts());
		check(present.size() == 15 && present.contains("migrated"));
		check(sameStrings(present, idsOfRecords(live).toArray(new String[0])));
		System.out.println("SHORTCUT_MANAGER NOTE migrated_after_restart=" + present);
		// The same fields the v2 fixture encoded, compared across the restart:
		// the committed store written by `migrate` against what this fresh
		// process reads back. Run `migrate` first (the README recipe does).
		File snapshot = new File(getFilesDir(), MIGRATION_SNAPSHOT);
		check(snapshot.exists());
		List<Record> committed = decodeV3Records(readAll(snapshot));
		String diff = diffRecords(committed, live);
		if (diff != null) {
			System.out.println("SHORTCUT_MANAGER NOTE migrated_restart_field_diff=" + diff);
		}
		System.out.println("SHORTCUT_MANAGER NOTE migrated_restart_fields_compared entries="
		    + committed.size() + " fields=" + RECORD_FIELD_NAMES);
		check(diff == null);
	}

	// --- crash / SIGKILL modes (driven by the README) ------------------------

	/**
	 * Publishes stores large enough to make the commit window observable, in a
	 * tight loop, so the driving harness can {@code kill -9} the process while
	 * it is inside a commit. Never returns normally.
	 */
	private void runCrashLoop() throws Throwable {
		final ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		final File store = storeFile();
		StringBuilder padding = new StringBuilder();
		for (int i = 0; i < 8192; i++) {
			padding.append('X');
		}
		long round = 0;
		while (true) {
			List<ShortcutInfo> next = new ArrayList<ShortcutInfo>();
			for (int i = 0; i < 15; i++) {
				next.add(builder("crash" + i)
				    .setActivity(activity())
				    .setShortLabel("C" + i)
				    .setLongLabel(round + " " + padding)
				    .setIntent(intent())
				    .setRank(i)
				    .build());
			}
			check(sm.setDynamicShortcuts(next));
			round++;
			if (round % 50 == 0) {
				System.out.println("SHORTCUT_MANAGER CRASH_LOOP round=" + round
				    + " bytes=" + store.length() + " pid=" + android.os.Process.myPid());
			}
		}
	}

	/**
	 * Runs after a {@code kill -9} during {@link #runCrashLoop}: whatever the
	 * process was doing, the store on disk must decode to a complete document
	 * (never a torn one), the abandoned lock must already be free so the next
	 * commit succeeds immediately, and a leftover temp file must be overwritten
	 * rather than adopted.
	 */
	private void runVerifyStore() throws Throwable {
		ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		File store = storeFile();
		byte[] bytes = readAll(store);
		check(bytes.length > 12);
		check(storeVersion(bytes) == 3);
		List<String> before = decodeV3Ids(bytes);
		check(!before.isEmpty());
		File temp = new File(store.getPath() + ".new");
		System.out.println("SHORTCUT_MANAGER VERIFY_STORE bytes=" + bytes.length
		    + " entries=" + before.size() + " temp_present=" + temp.exists());
		long start = System.currentTimeMillis();
		check(sm.addDynamicShortcuts(list(peerShortcut("after-crash"))));
		long elapsed = System.currentTimeMillis() - start;
		check(elapsed < 10000);
		check(!temp.exists());
		check(decodeV3Ids(readAll(store)).contains("after-crash"));
		System.out.println("SHORTCUT_MANAGER VERIFY_STORE recovered_ms=" + elapsed);
	}

	/**
	 * Runs after the owner of the store lock was {@code kill -9}d while holding
	 * it ({@link #runHold}). The kernel released the lock, so a commit must
	 * complete immediately - a stale-lock design would block for the whole hold
	 * window instead. The lock file must still be the same inode with the dead
	 * owner's sentinel (never unlinked, truncated or replaced), and the store
	 * must decode.
	 */
	private void runRecover() throws Throwable {
		ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		File store = storeFile();
		File lock = lockFile();
		check(lock.exists());
		byte[] sentinel = readAll(lock);
		check(sentinel.length > 0);
		if (store.exists() && store.length() > 12) {
			byte[] bytes = readAll(store);
			check(isLegacy(bytes) ? !decodeV2Ids(bytes).isEmpty() : !decodeV3Ids(bytes).isEmpty());
		}
		long start = System.currentTimeMillis();
		check(sm.addDynamicShortcuts(list(peerShortcut("after-kill"))));
		long elapsed = System.currentTimeMillis() - start;
		check(elapsed < 10000);
		check(Arrays.equals(readAll(lock), sentinel));
		check(decodeV3Ids(readAll(store)).contains("after-kill"));
		check(!new File(store.getPath() + ".new").exists());
		System.out.println("SHORTCUT_MANAGER RECOVER elapsed_ms=" + elapsed
		    + " sentinel_intact=true pid=" + android.os.Process.myPid());
	}

	// --- cross-process lock modes --------------------------------------------

	/**
	 * Holds the OS lock on the stable lock file for {@code files/hold-seconds}
	 * seconds (default 35), records a sentinel in the lock file first, then
	 * releases. Driven by the README so a contender process can observe that the
	 * lock is neither entered nor reclaimed while held.
	 */
	private void runHold() throws Throwable {
		int seconds = controlInt("hold-seconds", 35);
		File lock = lockFile();
		RandomAccessFile raf = new RandomAccessFile(lock, "rw");
		raf.setLength(0);
		byte[] sentinel = ("sentinel=" + android.os.Process.myPid() + "\n").getBytes("UTF-8");
		raf.write(sentinel);
		FileChannel channel = raf.getChannel();
		FileLock fileLock = channel.lock();
		System.out.println("SHORTCUT_MANAGER HOLD READY pid=" + android.os.Process.myPid()
		    + " seconds=" + seconds);
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			System.out.println("SHORTCUT_MANAGER HOLD HOLDING");
			Thread.sleep(Math.min(5000, Math.max(1, deadline - System.currentTimeMillis())));
		}
		byte[] seen = readAll(lock);
		System.out.println("SHORTCUT_MANAGER HOLD INODE_CHECK exists=" + lock.exists()
		    + " length=" + lock.length() + " sentinel_intact=" + Arrays.equals(seen, sentinel));
		check(Arrays.equals(seen, sentinel));
		fileLock.release();
		channel.close();
		raf.close();
		System.out.println("SHORTCUT_MANAGER HOLD RELEASED");
	}

	/**
	 * Starts a manager publish in a background thread while a separate process
	 * holds the lock. The publish must make no progress before the holder
	 * releases (even after the old 30 s staleness window) and must complete
	 * afterwards, without the lock file being unlinked or replaced.
	 */
	private void runContend() throws Throwable {
		int holdSeconds = controlInt("hold-seconds", 35);
		File lock = lockFile();
		byte[] sentinel = readAll(lock);
		final ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		final Object done = new Object();
		final boolean[] finished = new boolean[] {false};
		final Throwable[] error = new Throwable[] {null};
		final String tag = "contender" + control("contender-tag");
		Thread writer = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					sm.addDynamicShortcuts(list(shortcut(tag, "C")));
				} catch (Throwable t) {
					error[0] = t;
				} finally {
					synchronized (done) {
						finished[0] = true;
						done.notifyAll();
					}
				}
			}
		});
		writer.start();
		// Observe the lock just before the holder is due to release.
		long observeAt = System.currentTimeMillis() + Math.max(1, holdSeconds - 2) * 1000L;
		synchronized (done) {
			while (!finished[0] && System.currentTimeMillis() < observeAt) {
				done.wait(500);
			}
		}
		boolean enteredWhileHeld = finished[0] && error[0] == null;
		System.out.println("SHORTCUT_MANAGER CONTEND entered_while_held=" + enteredWhileHeld
		    + " finished=" + finished[0]);
		check(!enteredWhileHeld);
		// The manager must never have touched the lock file (no unlink/replace).
		check(lock.exists() && Arrays.equals(readAll(lock), sentinel));
		long deadline = System.currentTimeMillis() + 60000L;
		synchronized (done) {
			while (!finished[0] && System.currentTimeMillis() < deadline) {
				done.wait(500);
			}
		}
		System.out.println("SHORTCUT_MANAGER CONTEND completed=" + finished[0]
		    + " error=" + (error[0] == null ? "none" : error[0].toString())
		    + " sentinel_intact=" + Arrays.equals(readAll(lock), sentinel));
		check(finished[0] && error[0] == null);
		check(lock.exists() && Arrays.equals(readAll(lock), sentinel));
	}

	/**
	 * One of two concurrent read-modify-write processes (tagged by
	 * {@code files/peer-tag}), each adding distinct ids with a raw-file reader
	 * thread that must never observe a torn store.
	 */
	private void runPeer() throws Throwable {
		final String tag = control("peer-tag");
		check(tag != null);
		int count = controlInt("peer-count", 12);
		final ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		final File store = storeFile();
		final int[] torn = new int[] {0};
		final boolean[] running = new boolean[] {true};
		Thread reader = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running[0]) {
					try {
						if (store.exists() && store.length() > 0) {
							byte[] bytes = readAll(store);
							// a complete store is either valid v3 or legacy v2
							if (!isLegacy(bytes)) {
								decodeV3Ids(bytes);
							}
						}
					} catch (Throwable t) {
						torn[0]++;
					}
				}
			}
		});
		reader.start();
		for (int i = 0; i < count; i++) {
			sm.addDynamicShortcuts(list(peerShortcut(tag + i)));
		}
		running[0] = false;
		reader.join();
		System.out.println("SHORTCUT_MANAGER PEER tag=" + tag + " added=" + count
		    + " torn=" + torn[0] + " ids=" + joinIds(ids(sm.getDynamicShortcuts())));
		check(torn[0] == 0);
	}

	/** After both peers finished, every id from both must still be present. */
	private void runVerifyPeers() throws Throwable {
		int count = controlInt("peer-count", 12);
		ShortcutManager sm = (ShortcutManager) getSystemService("shortcut");
		List<String> present = ids(sm.getDynamicShortcuts());
		System.out.println("SHORTCUT_MANAGER VERIFY peers present=" + present.size()
		    + " ids=" + present);
		for (String tag : new String[] {"A", "B"}) {
			for (int i = 0; i < count; i++) {
				check(present.contains(tag + i));
			}
		}
	}

	// --- validation helpers ---------------------------------------------------

	private void check(boolean value) {
		if (!value) {
			StackTraceElement at = new Throwable().getStackTrace()[1];
			System.out.println("SHORTCUT_MANAGER NOTE failed_check=" + checks + " at "
			    + at.getFileName() + ":" + at.getLineNumber());
			throw new AssertionError("check " + checks);
		}
		checks++;
	}

	private interface Run {
		void run() throws Throwable;
	}

	private boolean throwsIAE(Run run) {
		try {
			run.run();
			return false;
		} catch (IllegalArgumentException expected) {
			return true;
		} catch (Throwable other) {
			return !atl;
		}
	}

	private ShortcutInfo.Builder builder(String id) {
		return new ShortcutInfo.Builder(this, id);
	}

	private ComponentName activity() {
		return new ComponentName(this, Probe.class);
	}

	private Intent intent() {
		return new Intent(Intent.ACTION_VIEW)
		    .setClassName(getPackageName(), getPackageName() + ".Probe");
	}

	private ShortcutInfo shortcut(String id, String label) {
		return builder(id)
		    .setActivity(activity())
		    .setShortLabel(label)
		    .setLongLabel(label + " long")
		    .setDisabledMessage(label + " disabled")
		    .setIntent(intent())
		    .setRank(1)
		    .setCategories(new HashSet<String>(Arrays.asList(CATEGORY)))
		    .build();
	}

	/** A peer shortcut with its own target activity so per-activity quota never guards the race. */
	private ShortcutInfo peerShortcut(String id) {
		return builder(id)
		    .setActivity(new ComponentName(getPackageName(), getPackageName() + ".PeerActivity" + id))
		    .setShortLabel(id)
		    .setIntent(intent())
		    .build();
	}

	private static List<String> idList(String... ids) {
		return new ArrayList<String>(Arrays.asList(ids));
	}

	private static List<ShortcutInfo> list(ShortcutInfo... infos) {
		return new ArrayList<ShortcutInfo>(Arrays.asList(infos));
	}

	/** Ids of the given shortcuts, in list order. */
	private static List<String> ids(List<ShortcutInfo> infos) {
		List<String> result = new ArrayList<String>();
		for (ShortcutInfo info : infos) {
			result.add(info.getId());
		}
		return result;
	}

	/** Ids of the given independently decoded store records, in store order. */
	private static List<String> idsOfRecords(List<Record> records) {
		List<String> result = new ArrayList<String>();
		for (Record record : records) {
			result.add(record.id);
		}
		return result;
	}

	/** Comma-joined ids, for the log lines (the typed helper above returns a list). */
	private static String joinIds(List<String> values) {
		StringBuilder sb = new StringBuilder();
		for (String value : values) {
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(value);
		}
		return sb.toString();
	}

	private static List<String> safe(ShortcutManager sm) {
		try {
			List<String> result = new ArrayList<String>();
			for (ShortcutInfo info : sm.getDynamicShortcuts()) {
				result.add(info.getId());
			}
			return result;
		} catch (Throwable t) {
			return new ArrayList<String>();
		}
	}

	/** Order-sensitive under ATL; the reference emulator may reorder, hence the set form. */
	private boolean sameIds(List<ShortcutInfo> infos, String expected) {
		List<String> got = ids(infos);
		List<String> want = Arrays.asList(expected.split(","));
		if (!atl) {
			return new HashSet<String>(got).equals(new HashSet<String>(want))
			    && got.size() == want.size();
		}
		return got.equals(want);
	}

	private static boolean sameStrings(List<String> got, String[] want) {
		return got.equals(Arrays.asList(want));
	}

	private static ShortcutInfo find(List<ShortcutInfo> infos, String id) {
		for (ShortcutInfo info : infos) {
			if (id.equals(info.getId())) {
				return info;
			}
		}
		return null;
	}

	private static boolean same(CharSequence left, CharSequence right) {
		if (left == null || right == null) {
			return left == right;
		}
		return left.toString().equals(right.toString());
	}

	private static boolean isAtl() {
		try {
			Class.forName("android.atl.ATLLoadedApp");
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// --- probe-side store paths / IO -----------------------------------------

	private File storeFile() {
		return new File(getFilesDir(), "shortcuts.bin." + getPackageName() + ".bin");
	}

	private File lockFile() {
		return new File(storeFile().getPath() + ".lock");
	}

	private String readTextFile(String name) throws Exception {
		File file = new File(getFilesDir(), name);
		if (!file.exists()) {
			return null;
		}
		return new String(readAll(file), "UTF-8").trim();
	}

	/**
	 * Control value read from an Intent extra if present, otherwise from a file
	 * of that name in the app data directory. The extra form lets several probe
	 * processes share one data directory - and therefore one store file, which
	 * is what the contention, peer and migration modes need - while keeping
	 * different per-process control values
	 * ({@code -e peer-tag=A -e mode=peer}). The file form keeps the
	 * single-process runs simple.
	 */
	private String control(String name) {
		try {
			Intent intent = getIntent();
			String extra = intent == null ? null : intent.getStringExtra(name);
			if (extra != null) {
				return extra.trim();
			}
		} catch (Throwable ignored) {
			// fall back to the control file
		}
		try {
			return readTextFile(name);
		} catch (Throwable t) {
			return null;
		}
	}

	private int controlInt(String name, int fallback) {
		String text = control(name);
		try {
			return text == null || text.length() == 0 ? fallback : Integer.parseInt(text);
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static byte[] readAll(File file) throws Exception {
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

	private static void writeAll(File file, byte[] bytes) throws Exception {
		FileOutputStream out = new FileOutputStream(file);
		try {
			out.write(bytes);
		} finally {
			out.close();
		}
	}

	/**
	 * Sets the directory's writability for this process and asserts the effect,
	 * so the rename-failure regression cannot silently degrade into a weaker
	 * test on an implementation with different {@code ownerOnly} semantics.
	 */
	private void setDirWritable(File dir, boolean writable) {
		dir.setWritable(writable, true);
		if (!writable && dir.canWrite()) {
			dir.setWritable(false, false);
		}
		check(dir.canWrite() == writable);
	}

	// --- probe-side, independent decoders ------------------------------------
	//
	// These deliberately do not use the implementation's decoders: the typed
	// reader below mirrors the documented version-3 layout and the legacy reader
	// mirrors the frozen PARCEL_VERSION-1 record layout, so a store the manager
	// wrote (or the committed v2 fixture) is validated independently.

	private static int storeInt(byte[] bytes, int index) {
		Parcel parcel = Parcel.obtain();
		try {
			parcel.unmarshall(bytes, 0, bytes.length);
			parcel.setDataPosition(index * 4);
			return parcel.readInt();
		} finally {
			parcel.recycle();
		}
	}

	/** Replaces the int at byte position {@code bytePos} and returns the new bytes. */
	private static byte[] withStoreInt(byte[] bytes, int bytePos, int value) {
		Parcel parcel = Parcel.obtain();
		try {
			parcel.unmarshall(bytes, 0, bytes.length);
			parcel.setDataPosition(bytePos);
			parcel.writeInt(value);
			return parcel.marshall();
		} finally {
			parcel.recycle();
		}
	}

	private static int storeVersion(byte[] bytes) {
		if (bytes.length < 12 || storeInt(bytes, 0) != STORE_MAGIC) {
			throw new IllegalStateException("not an ATL shortcut store");
		}
		return storeInt(bytes, 1);
	}

	private static boolean isLegacy(byte[] bytes) {
		return bytes.length >= 12 && storeInt(bytes, 0) == STORE_MAGIC
		    && storeInt(bytes, 1) == 2;
	}

	/** Independent version-3 decoder; returns the ids in store order. */
	private static List<String> decodeV3Ids(byte[] bytes) throws Exception {
		return idsOfRecords(decodeV3Records(bytes));
	}

	/** Independent version-3 decoder; returns one record per entry, in store order. */
	private static List<Record> decodeV3Records(byte[] bytes) throws Exception {
		if (storeVersion(bytes) != 3) {
			throw new IllegalStateException("not a version-3 store");
		}
		Parcel parcel = Parcel.obtain();
		int payloadLength;
		byte[] payload;
		int expectedCrc;
		try {
			parcel.unmarshall(bytes, 0, bytes.length);
			parcel.setDataPosition(8);
			payloadLength = parcel.readInt();
			if (payloadLength < 0 || payloadLength > bytes.length) {
				throw new IllegalStateException("bad payload length " + payloadLength);
			}
			payload = new byte[payloadLength];
			for (int i = 0; i < payloadLength; i++) {
				payload[i] = parcel.readByte();
			}
			expectedCrc = parcel.readInt();
		} finally {
			parcel.recycle();
		}
		CRC32 crc = new CRC32();
		crc.update(payload);
		if ((int) crc.getValue() != expectedCrc) {
			throw new IllegalStateException("checksum mismatch");
		}
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
		int count = in.readInt();
		if (count < 0 || count > 4096) {
			throw new IllegalStateException("bad count " + count);
		}
		List<Record> records = new ArrayList<Record>();
		for (int i = 0; i < count; i++) {
			records.add(readTypedRecord(in));
		}
		if (in.read() != -1) {
			throw new IllegalStateException("trailing payload bytes");
		}
		return records;
	}

	/**
	 * Parses one typed record with a mirror of the documented layout; any
	 * structural surprise throws, which also makes it a torn-read detector.
	 */
	private static Record readTypedRecord(DataInputStream in) throws Exception {
		Record record = new Record();
		record.id = readString(in);
		record.pkg = readString(in);
		if (in.readByte() != 0) {
			record.activity = component(readString(in), readString(in));
		}
		record.shortLabel = readString(in);
		record.longLabel = readString(in);
		int intentCount = in.readInt();
		if (intentCount < 0) {
			record.intents = null;
		} else {
			record.intents = new ArrayList<String>();
			for (int i = 0; i < intentCount; i++) {
				record.intents.add(readTypedIntent(in));
			}
		}
		record.rank = in.readInt();
		record.disabledMessage = readString(in);
		record.categories = readStringList(in);
		record.flags = in.readInt();
		record.longLived = in.readByte() != 0;
		return record;
	}

	private static String readTypedIntent(DataInputStream in) throws Exception {
		if (in.readByte() == 0) {
			return NULL_ENTRY;
		}
		String action = readString(in);
		String data = in.readByte() != 0 ? readString(in) : null;
		String type = readString(in);
		String pkg = readString(in);
		String intentComponent = null;
		if (in.readByte() != 0) {
			intentComponent = component(readString(in), readString(in));
		}
		int flags = in.readInt();
		List<String> categories = readStringList(in);
		return describeIntent(action, data, type, pkg, intentComponent, flags, categories);
	}

	private static String readString(DataInputStream in) throws Exception {
		int length = in.readInt();
		if (length < 0) {
			return null;
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, "UTF-8");
	}

	/** Reads one typed string list; {@code null} is preserved. */
	private static List<String> readStringList(DataInputStream in) throws Exception {
		int count = in.readInt();
		if (count < 0) {
			return null;
		}
		List<String> values = new ArrayList<String>(count);
		for (int i = 0; i < count; i++) {
			values.add(readString(in));
		}
		return values;
	}

	/** Independent version-2 (legacy parcel) decoder; returns the ids in order. */
	private static List<String> decodeV2Ids(byte[] bytes) {
		return idsOfRecords(decodeV2Records(bytes));
	}

	/** Independent version-2 (legacy parcel) decoder; one record per entry, in order. */
	private static List<Record> decodeV2Records(byte[] bytes) {
		if (!isLegacy(bytes)) {
			throw new IllegalStateException("not a version-2 store");
		}
		Parcel parcel = Parcel.obtain();
		try {
			parcel.unmarshall(bytes, 0, bytes.length);
			parcel.setDataPosition(8);
			int count = parcel.readInt();
			if (count < 0 || count > 4096) {
				throw new IllegalStateException("bad count " + count);
			}
			List<Record> records = new ArrayList<Record>();
			for (int i = 0; i < count; i++) {
				records.add(readLegacyRecord(parcel));
			}
			return records;
		} finally {
			parcel.recycle();
		}
	}

	private static Record readLegacyRecord(Parcel parcel) {
		int version = parcel.readInt();
		if (version != 1) {
			throw new IllegalStateException("unexpected parcel version " + version);
		}
		Record record = new Record();
		record.id = parcel.readString();
		record.pkg = parcel.readString();
		record.activity = readLegacyComponent(parcel);
		record.shortLabel = parcel.readString();
		record.longLabel = parcel.readString();
		int intentCount = parcel.readInt();
		if (intentCount < 0) {
			record.intents = null;
		} else {
			record.intents = new ArrayList<String>();
			for (int i = 0; i < intentCount; i++) {
				record.intents.add(readLegacyIntent(parcel));
			}
		}
		record.rank = parcel.readInt();
		record.disabledMessage = parcel.readString();
		record.categories = parcel.createStringArrayList();
		record.flags = parcel.readInt();
		record.longLived = parcel.readInt() != 0;
		return record;
	}

	private static String readLegacyComponent(Parcel parcel) {
		if (parcel.readInt() == 0) {
			return null;
		}
		return component(parcel.readString(), parcel.readString());
	}

	private static String readLegacyIntent(Parcel parcel) {
		if (parcel.readInt() == 0) {
			return NULL_ENTRY;
		}
		String action = parcel.readString();
		String data = parcel.readInt() != 0 ? parcel.readString() : null;
		String type = parcel.readString();
		String pkg = parcel.readString();
		String intentComponent = readLegacyComponent(parcel);
		int flags = parcel.readInt();
		List<String> categories = parcel.createStringArrayList();
		return describeIntent(action, data, type, pkg, intentComponent, flags, categories);
	}

	// --- independent record model + field comparison --------------------------

	/**
	 * Canonical spelling of the whole {@code ShortcutInfo} field set both store
	 * formats persist (the legacy v1 parcel and the typed v3 record encode the
	 * same fields; {@code icon} and {@code extras} are persisted by neither, so
	 * they cannot be compared from the fixture).
	 */
	private static final String RECORD_FIELD_NAMES = "id,package,activity,shortLabel,longLabel,"
	    + "intents(action,data,type,package,component,flags,categories),rank,"
	    + "disabledMessage,categories,flags,longLived";

	/** Marks a null intent list entry (a record may carry null intents). */
	private static final String NULL_ENTRY = "<null>";

	/** One store entry decoded independently of the implementation. */
	private static final class Record {
		String id;
		String pkg;
		String activity;
		String shortLabel;
		String longLabel;
		List<String> intents;
		int rank;
		String disabledMessage;
		List<String> categories;
		int flags;
		boolean longLived;

		String describe() {
			return "id=" + id
			    + " package=" + pkg
			    + " activity=" + activity
			    + " shortLabel=" + shortLabel
			    + " longLabel=" + longLabel
			    + " intents=" + intents
			    + " rank=" + rank
			    + " disabledMessage=" + disabledMessage
			    + " categories=" + categories
			    + " flags=" + flags
			    + " longLived=" + longLived;
		}
	}

	private static String component(String pkg, String className) {
		return pkg + "/" + className;
	}

	/** Canonical text of one intent, covering every persisted sub-field. */
	private static String describeIntent(String action, String data, String type, String pkg,
	    String component, int flags, List<String> categories) {
		return "action=" + action + ",data=" + data + ",type=" + type + ",package=" + pkg
		    + ",component=" + component + ",flags=" + flags + ",categories=" + categories;
	}

	private static Record findRecord(List<Record> records, String id) {
		for (Record record : records) {
			if (record.id != null && record.id.equals(id)) {
				return record;
			}
		}
		return null;
	}

	/** Returns {@code null} when every field of every record matches, else the first difference. */
	private static String diffRecords(List<Record> before, List<Record> after) {
		if (before.size() != after.size()) {
			return "entry count " + before.size() + " -> " + after.size();
		}
		for (int i = 0; i < before.size(); i++) {
			String field = diffRecord(before.get(i), after.get(i));
			if (field != null) {
				return "entry " + i + " field " + field + " (before {" + before.get(i).describe()
				    + "} after {" + after.get(i).describe() + "})";
			}
		}
		return null;
	}

	private static String diffRecord(Record before, Record after) {
		if (!sameValue(before.id, after.id)) {
			return "id";
		}
		if (!sameValue(before.pkg, after.pkg)) {
			return "package";
		}
		if (!sameValue(before.activity, after.activity)) {
			return "activity";
		}
		if (!sameValue(before.shortLabel, after.shortLabel)) {
			return "shortLabel";
		}
		if (!sameValue(before.longLabel, after.longLabel)) {
			return "longLabel";
		}
		if (!sameValue(before.intents, after.intents)) {
			return "intents";
		}
		if (before.rank != after.rank) {
			return "rank";
		}
		if (!sameValue(before.disabledMessage, after.disabledMessage)) {
			return "disabledMessage";
		}
		if (!sameValue(before.categories, after.categories)) {
			return "categories";
		}
		if (before.flags != after.flags) {
			return "flags";
		}
		if (before.longLived != after.longLived) {
			return "longLived";
		}
		return null;
	}

	private static boolean sameValue(Object before, Object after) {
		return before == null ? after == null : before.equals(after);
	}
}