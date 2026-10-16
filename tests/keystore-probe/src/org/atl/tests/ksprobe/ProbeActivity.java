/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.ksprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KEYSTORE-001 probe.
 *
 * Decides, in ONE run, whether the androidx master-key self-test failure is
 * caused by ATL's AndroidKeyStore key object (storage round trip / key type)
 * or by the dependency-side BC/javax.crypto AES-GCM Cipher path.
 *
 * Every observation is printed to stdout as a `KSPROBE:` line (captured in
 * atl-ui-stdout.log) and mirrored into an {@code KSPROBE_JSON_BEGIN} /
 * {@code KSPROBE_JSON_END} sentinel block plus filesDir/ksprobe-results.json.
 *
 * No key bytes, ciphertext or tag bytes are ever logged: only sha256 digests,
 * lengths, algorithm names and PASS/FAIL verdicts.
 */
public class ProbeActivity extends Activity {
	private static final String TAG = "KSPROBE";
	private static final String ATL_ALIAS = "ksprobe_atl_alias";
	private static final String STORE_ALIAS = "ksprobe_store_alias";
	private static final String PERSIST_ALIAS = "ksprobe_persist_alias";
	private static final String STATE_FILE = "ksprobe-state.txt";
	/** Elapsed-time origin: every KSPROBE line carries `[<ms>ms]` so the
	 *  wall time of each keystore/libsecret call is visible in the log
	 *  (used to show that a failing libsecret backend is BOUNDED, not an
	 *  unbounded D-Bus activation wait). */
	private static final long T0 = System.currentTimeMillis();

	private final StringBuilder log = new StringBuilder();
	private final StringBuilder json = new StringBuilder();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		try {
			runAll();
		} catch (Throwable t) {
			Log.e(TAG, "probe crashed", t);
			line("exception " + t.getClass().getName() + ": " + t.getMessage());
		}
		System.out.println("KSPROBE_JSON_BEGIN");
		System.out.println(json.toString());
		System.out.println("KSPROBE_JSON_END");
		System.out.println("KSPROBE_RAW_BEGIN");
		System.out.print(log.toString());
		System.out.println("KSPROBE_RAW_END");
		System.out.println("KSPROBE_DONE");
		try { System.out.flush(); } catch (Throwable ignored) {}
		writeResults();
	}

	/* ---------------------------------------------------------------- */

	private void runAll() throws Exception {
		json.append("{\"task\":\"KEYSTORE-001\"");
		json.append(",\"phase\":\"").append(hasStateFile() ? "phase2" : "phase1").append("\"");
		json.append(",\"provider\":{\"cipherAesGcm\":\"").append(providerName()).append("\"}");

		kat();
		softwareRoundTrip();
		atlKeystoreRoundTrip();
		androidxExactPath();
		tinkLayout();
		negatives();
		persistence();

		json.append("}");
	}

	private static String providerName() {
		try {
			return Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName();
		} catch (Throwable t) {
			return "ERR:" + t.getClass().getSimpleName();
		}
	}

	/* --- NIST SP 800-38D known-answer vectors, zero key / zero IV ---- */

	private void kat() throws Exception {
		line("--- KAT (NIST SP 800-38D zero key/IV) ---");
		// Test case 2: AES-128, PT = 16 zero bytes, empty AAD.
		katCase("aes128_pt16", 16, new byte[16], new byte[16], new byte[0],
		        "0388dace60b6a392f328c2b971b2fe78", "ab6e47d42cec13bdf53a67b21257bddf");
		// Test case 1: AES-128, empty PT, empty AAD.
		katCase("aes128_pt0", 16, new byte[0], new byte[16], new byte[0],
		        "", "58e2fccefa7e3061367f1d57a4e7455a");
		// Test case 13: AES-256, empty PT, empty AAD.
		katCase("aes256_pt0", 32, new byte[0], new byte[32], new byte[0],
		        "", "530f8afbc74536b9a963b4f1c4cb738b");
		// Test case 14: AES-256, PT = 16 zero bytes, empty AAD.
		katCase("aes256_pt16", 32, new byte[16], new byte[32], new byte[0],
		        "cea7403d4d606b6e074ec5d3baf39d18", "d0d1c8a799996bf0265b98b5d48ab919");
	}

	private void katCase(String name, int keyLen, byte[] pt, byte[] keyBytes, byte[] aad,
	                     String expectCt, String expectTag) {
		try {
			SecretKey k = new SecretKeySpec(keyBytes, "AES");
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, new byte[12]));
			byte[] out = enc(c, aad, pt);
			int ctLen = out.length - 16;
			String ct = hex(Arrays.copyOfRange(out, 0, ctLen));
			String tag = hex(Arrays.copyOfRange(out, ctLen, out.length));
			boolean ok = ct.equalsIgnoreCase(expectCt) && tag.equalsIgnoreCase(expectTag);
			line(name + " ct=" + ct + " tag=" + tag + " -> " + (ok ? "PASS" : "FAIL"));
			json.append(",\"") .append(name).append("\":{\"ct\":\"").append(ct)
			    .append("\",\"tag\":\"").append(tag).append("\",\"verdict\":\"")
			    .append(ok ? "PASS" : "FAIL").append("\"}");
			// decryption of the produced vector must round trip to the input
			Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
			d.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, new byte[12]));
			byte[] back = dec(d, aad, out);
			line(name + " decrypt_roundtrip -> " + (Arrays.equals(back, pt) ? "PASS" : "FAIL"));
		} catch (Throwable t) {
			line(name + " EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
			json.append(",\"").append(name).append("\":{\"verdict\":\"EXCEPTION\",\"error\":\"")
			    .append(esc(t.getClass().getSimpleName())).append("\"}");
		}
	}

	/* --- software (SecretKeySpec) round trip, androidx sequence ----- */

	private void softwareRoundTrip() {
		line("--- software key round trip ---");
		try {
			byte[] keyBytes = new byte[32];
			new SecureRandom().nextBytes(keyBytes);
			SecretKey k = new SecretKeySpec(keyBytes, "AES");
			rt("software", k, "aad-soft".getBytes("UTF-8"));
			line("software keyAlgo=" + k.getAlgorithm() + " keyLen=" + keyBytes.length);
		} catch (Throwable t) {
			line("software EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
		}
	}

	/** The exact androidx/Tink AndroidKeystoreAesGcm encrypt->decrypt sequence. */
	private void rt(String label, SecretKey key, byte[] aad) throws Exception {
		Cipher e = Cipher.getInstance("AES/GCM/NoPadding");
		e.init(Cipher.ENCRYPT_MODE, key);
		byte[] iv = e.getIV();
		line(label + " ivLen=" + (iv == null ? -1 : iv.length));
		byte[] ct = enc(e, aad, "atl-keystore-probe-plaintext".getBytes("UTF-8"));
		line(label + " ctLen=" + ct.length);
		Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
		d.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
		byte[] back = dec(d, aad, ct);
		boolean ok = Arrays.equals(back, "atl-keystore-probe-plaintext".getBytes("UTF-8"));
		line(label + " selfRoundTrip -> " + (ok ? "PASS" : "FAIL"));
		json.append(",\"").append(label).append("_roundtrip\":{\"ivLen\":")
		    .append(iv == null ? -1 : iv.length).append(",\"ctLen\":").append(ct.length)
		    .append(",\"verdict\":\"").append(ok ? "PASS" : "FAIL").append("\"}");
	}

	/* --- ATL AndroidKeyStore: store vs read-back key identity ------- */

	private void atlKeystoreRoundTrip() {
		line("--- ATL AndroidKeyStore store/get + sha256 ---");
		try {
			KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			KeyGenerator kg = KeyGenerator.getInstance("AES");
			kg.init(256);
			SecretKey sk = kg.generateKey();
			String setHash = sha256Hex(sk.getEncoded());
			line("store set algo=" + sk.getAlgorithm() + " len=" + sk.getEncoded().length
			     + " sha256=" + setHash);
			ks.setKeyEntry(STORE_ALIAS, sk, null, null);
			SecretKey got = (SecretKey) ks.getKey(STORE_ALIAS, null);
			if (got == null) {
				line("store get -> NULL");
				json.append(",\"atl_store\":{\"verdict\":\"NULL\"}");
				return;
			}
			String getHash = sha256Hex(got.getEncoded());
			line("store get algo=" + got.getAlgorithm() + " len=" + got.getEncoded().length
			     + " sha256=" + getHash);
			boolean same = setHash.equals(getHash);
			line("store identity -> " + (same ? "PASS" : "FAIL"));
			json.append(",\"atl_store\":{\"algo\":\"").append(esc(got.getAlgorithm()))
			    .append("\",\"len\":").append(got.getEncoded().length)
			    .append(",\"sha256Match\":").append(same).append("}");
			rt("atlStore", got, new byte[0]);
		} catch (Throwable t) {
			line("atlStore EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
			json.append(",\"atl_store\":{\"verdict\":\"EXCEPTION\",\"error\":\"")
			    .append(esc(t.getClass().getSimpleName())).append("\"}");
		}
	}

	/* --- androidx MasterKey path: KeyGenerator("AES","AndroidKeyStore") */

	private void androidxExactPath() {
		line("--- androidx path: KeyGenerator AES/AndroidKeyStore + spec ---");
		try {
			KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
			android.security.keystore.KeyGenParameterSpec spec =
			    new android.security.keystore.KeyGenParameterSpec.Builder(ATL_ALIAS, 3 /*ENCRYPT|DECRYPT*/)
			        .setKeySize(256)
			        .setBlockModes(new String[]{"GCM"})
			        .setEncryptionPaddings(new String[]{"NoPadding"})
			        .build();
			kg.init(spec);
			SecretKey k = kg.generateKey();
			String hash = sha256Hex(k.getEncoded());
			line("androidx gen algo=" + k.getAlgorithm() + " len=" + k.getEncoded().length
			     + " sha256=" + hash + " (requested 256 bits)");
			json.append(",\"androidx_gen\":{\"algo\":\"").append(esc(k.getAlgorithm()))
			    .append("\",\"len\":").append(k.getEncoded().length)
			    .append(",\"requestedBits\":256}");
			rt("androidxKey", k, new byte[]{1});
			// read the same alias back and round trip with the re-read key
			KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			if (ks.containsAlias(ATL_ALIAS)) {
				SecretKey k2 = (SecretKey) ks.getKey(ATL_ALIAS, null);
				if (k2 != null) {
					line("androidx reread algo=" + k2.getAlgorithm()
					     + " len=" + k2.getEncoded().length + " sha256=" + sha256Hex(k2.getEncoded()));
					rt("androidxReread", k2, new byte[]{1});
				} else {
					line("androidx reread -> NULL");
				}
			} else {
				line("androidx containsAlias -> FALSE");
			}
		} catch (Throwable t) {
			line("androidx EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
			json.append(",\"androidx_gen\":{\"verdict\":\"EXCEPTION\",\"error\":\"")
			    .append(esc(t.getClass().getSimpleName())).append("\"}");
		}
	}

	/* --- Tink AndroidKeystoreAesGcm blob layout (iv||ct, IV fixed 12) -- */

	private void tinkLayout() {
		line("--- Tink AndroidKeystoreAesGcm layout ---");
		try {
			Cipher probe = Cipher.getInstance("AES/GCM/NoPadding");
			line("cipher class=" + probe.getClass().getName()
			     + " providerClass=" + probe.getProvider().getClass().getName()
			     + " blockSize=" + probe.getBlockSize());
			json.append(",\"cipherImpl\":{\"class\":\"")
			    .append(esc(probe.getClass().getName())).append("\",\"providerClass\":\"")
			    .append(esc(probe.getProvider().getClass().getName()))
			    .append("\",\"blockSize\":").append(probe.getBlockSize()).append("}");

			KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
			ks.load(null);
			SecretKey key;
			if (ks.containsAlias(ATL_ALIAS)) {
				key = (SecretKey) ks.getKey(ATL_ALIAS, null);
			} else {
				KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
				kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(ATL_ALIAS, 3)
				        .setKeySize(256).setBlockModes(new String[]{"GCM"})
				        .setEncryptionPaddings(new String[]{"NoPadding"}).build());
				key = kg.generateKey();
			}
			byte[] aad = new byte[]{1};
			Cipher e = Cipher.getInstance("AES/GCM/NoPadding");
			e.init(Cipher.ENCRYPT_MODE, key);
			byte[] iv = e.getIV();
			byte[] ct = enc(e, aad, "tink-layout-plaintext".getBytes("UTF-8"));
			line("tink encrypt ivLen=" + iv.length + " ctLen=" + ct.length);
			byte[] blob = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, blob, 0, iv.length);
			System.arraycopy(ct, 0, blob, iv.length, ct.length);

			// Tink decrypt slices a FIXED 12-byte IV off the blob front.
			String verdict12;
			try {
				Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
				d.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(blob, 12)));
				byte[] pt = dec(d, aad, Arrays.copyOfRange(blob, 12, blob.length));
				verdict12 = "DECRYPTED(" + pt.length + ")";
			} catch (javax.crypto.AEADBadTagException ex) {
				verdict12 = "AEADBadTagException";
			} catch (Throwable t) {
				verdict12 = "OTHER:" + t.getClass().getSimpleName();
			}
			line("tink-style 12-byte-iv decrypt -> " + verdict12);

			// The same blob decrypted with the FULL IV the cipher produced.
			String verdictFull;
			try {
				Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
				d.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
				byte[] pt = dec(d, aad, ct);
				verdictFull = Arrays.equals(pt, "tink-layout-plaintext".getBytes("UTF-8"))
				    ? "PASS" : "WRONG_PLAINTEXT";
			} catch (Throwable t) {
				verdictFull = "EXCEPTION:" + t.getClass().getSimpleName();
			}
			line("full-iv decrypt -> " + verdictFull);

			// IV length when the caller supplies the IV explicitly.
			Cipher e2 = Cipher.getInstance("AES/GCM/NoPadding");
			e2.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
			line("explicit-12-byte-iv encrypt getIV().length=" + e2.getIV().length);
			json.append(",\"tink_layout\":{\"encryptIvLen\":").append(iv.length)
			    .append(",\"iv12Decrypt\":\"").append(verdict12)
			    .append("\",\"fullIvDecrypt\":\"").append(verdictFull)
			    .append("\",\"explicitIvLen\":").append(e2.getIV().length).append("}");
		} catch (Throwable t) {
			line("tinkLayout EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
			json.append(",\"tink_layout\":{\"verdict\":\"EXCEPTION\",\"error\":\"")
			    .append(esc(t.getClass().getSimpleName())).append("\"}");
		}
	}

	/* --- negatives: each MUST throw AEADBadTagException ------------ */

	private void negatives() {
		line("--- negatives (must throw AEADBadTagException) ---");
		try {
			byte[] keyBytes = new byte[32];
			new SecureRandom().nextBytes(keyBytes);
			SecretKey k = new SecretKeySpec(keyBytes, "AES");
			byte[] aad = "probe-aad".getBytes("UTF-8");
			Cipher e = Cipher.getInstance("AES/GCM/NoPadding");
			e.init(Cipher.ENCRYPT_MODE, k);
			byte[] iv = e.getIV();
			byte[] good = enc(e, aad, "negative-case-plaintext".getBytes("UTF-8"));

			neg("altered_ct", k, iv, flip(good, 0), aad);
			neg("altered_tag", k, iv, flip(good, good.length - 1), aad);
			neg("wrong_aad", k, iv, good, "other-aad".getBytes("UTF-8"));
			byte[] otherKeyBytes = new byte[32];
			new SecureRandom().nextBytes(otherKeyBytes);
			neg("wrong_key", new SecretKeySpec(otherKeyBytes, "AES"), iv, good, aad);
			neg("altered_iv", k, flip(iv, 0), good, aad);
		} catch (Throwable t) {
			line("negatives EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
		}
	}

	private void neg(String name, SecretKey k, byte[] iv, byte[] ct, byte[] aad) {
		String verdict;
		String detail = "";
		try {
			Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
			d.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
			byte[] out = dec(d, aad, ct);
			verdict = "FAIL_NO_EXCEPTION(" + out.length + ")";
		} catch (javax.crypto.AEADBadTagException ex) {
			verdict = "PASS_AEADBadTagException";
		} catch (Throwable t) {
			verdict = "OTHER_EXCEPTION:" + t.getClass().getName();
			detail = esc(String.valueOf(t.getMessage()));
		}
		line("neg " + name + " -> " + verdict);
		json.append(",\"neg_").append(name).append("\":{\"verdict\":\"").append(verdict)
		    .append("\",\"detail\":\"").append(detail).append("\"}");
	}

	/* --- persistence across a process restart ---------------------- */

	private File stateFile() {
		return new File(getFilesDir(), STATE_FILE);
	}

	private boolean hasStateFile() {
		return stateFile().exists();
	}

	private void persistence() {
		line("--- persistence ---");
		File f = stateFile();
		try {
			if (!f.exists()) {
				// Dedicated alias: never regenerated here once it exists, so the
				// persistence check measures the key round trip and not the
				// (caller-contract) replace-on-generate behaviour.
				KeyStore pks = KeyStore.getInstance("AndroidKeyStore");
				pks.load(null);
				SecretKey k;
				if (pks.containsAlias(PERSIST_ALIAS)) {
					k = (SecretKey) pks.getKey(PERSIST_ALIAS, null);
				} else {
					KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
					android.security.keystore.KeyGenParameterSpec spec =
					    new android.security.keystore.KeyGenParameterSpec.Builder(PERSIST_ALIAS, 3)
					        .setKeySize(256).setBlockModes(new String[]{"GCM"})
					        .setEncryptionPaddings(new String[]{"NoPadding"}).build();
					kg.init(spec);
					k = kg.generateKey();
				}
				Cipher e = Cipher.getInstance("AES/GCM/NoPadding");
				e.init(Cipher.ENCRYPT_MODE, k);
				byte[] iv = e.getIV();
				byte[] ct = enc(e, new byte[]{1}, "persist-plaintext".getBytes("UTF-8"));
				FileOutputStream out = new FileOutputStream(f);
				out.write((hex(iv) + "\n" + hex(ct) + "\n" + sha256Hex(k.getEncoded()) + "\n").getBytes("UTF-8"));
				out.close();
				line("persist WRITE ok keySha256=" + sha256Hex(k.getEncoded()));
				json.append(",\"persistence\":{\"phase\":\"WRITE\"}");
			} else {
				FileInputStream in = new FileInputStream(f);
				byte[] buf = new byte[(int) f.length()];
				int n = in.read(buf);
				in.close();
				String txt = new String(buf, 0, n, "UTF-8").trim();
				String[] parts = txt.split("\n");
				KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
				ks.load(null);
				SecretKey k = (SecretKey) ks.getKey(PERSIST_ALIAS, null);
				if (k == null) {
					line("persist READ -> key NULL");
					json.append(",\"persistence\":{\"phase\":\"READ\",\"verdict\":\"KEY_NULL\"}");
					return;
				}
				String nowHash = sha256Hex(k.getEncoded());
				boolean same = nowHash.equals(parts[2].trim());
				line("persist READ keySha256=" + nowHash + " sameAsWrite=" + same);
				Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
				d.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, unhex(parts[0].trim())));
				byte[] pt = dec(d, new byte[]{1}, unhex(parts[1].trim()));
				boolean ok = Arrays.equals(pt, "persist-plaintext".getBytes("UTF-8"));
				line("persist READ decrypt -> " + (ok ? "PASS" : "FAIL"));
				json.append(",\"persistence\":{\"phase\":\"READ\",\"sameKey\":").append(same)
				    .append(",\"verdict\":\"").append(ok ? "PASS" : "FAIL").append("\"}");
			}
		} catch (Throwable t) {
			line("persist EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
			json.append(",\"persistence\":{\"verdict\":\"EXCEPTION\",\"error\":\"")
			    .append(esc(t.getClass().getSimpleName())).append("\"}");
		}
	}

	/* ---------------------------------------------------------------- */

	/** AEAD encrypt with AAD via updateAAD (javax.crypto.Cipher has no doFinal(in, aad)). */
	private static byte[] enc(Cipher c, byte[] aad, byte[] pt) throws Exception {
		if (aad != null && aad.length > 0) c.updateAAD(aad);
		return c.doFinal(pt);
	}

	private static byte[] dec(Cipher c, byte[] aad, byte[] ct) throws Exception {
		if (aad != null && aad.length > 0) c.updateAAD(aad);
		return c.doFinal(ct);
	}

	private byte[] flip(byte[] in, int idx) {
		byte[] out = in.clone();
		out[idx] = (byte) (out[idx] ^ 0x01);
		return out;
	}

	private void line(String s) {
		log.append("KSPROBE: [").append(System.currentTimeMillis() - T0).append("ms] ").append(s).append('\n');
		Log.i(TAG, "[" + (System.currentTimeMillis() - T0) + "ms] " + s);
	}

	private void writeResults() {
		try {
			File f = new File(getFilesDir(), "ksprobe-results.json");
			FileOutputStream out = new FileOutputStream(f);
			out.write(json.toString().getBytes("UTF-8"));
			out.close();
		} catch (Throwable ignored) {
		}
	}

	private static String sha256Hex(byte[] b) throws Exception {
		if (b == null) return "null";
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		return hex(md.digest(b));
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16))
		                   .append(Character.forDigit(x & 0xf, 16));
		return sb.toString();
	}

	private static byte[] unhex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++)
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		return out;
	}

	private static String esc(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}