package android.security.keystore;

import android.atl.ATLLoadedApp;
import android.util.Slog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import javax.crypto.spec.SecretKeySpec;

public class AndroidKeyStore extends KeyStoreSpi {

	private final static String TAG = "AndroidKeyStore";

	private static native void nativeSetKey(String package_name, String alias, byte[] encoded);
	private static native byte[] nativeGetKey(String package_name, String alias);

	@Override
	public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
		Slog.i(TAG, "engineGetKey alias=" + alias);

		byte[] data = nativeGetKey(ATLLoadedApp.getPrimaryApplication().getApplication().getPackageName(), alias);
		if (data == null)
			return null;
		ByteBuffer buffer = ByteBuffer.wrap(data);
		byte[] algorithmBytes = new byte[buffer.getInt()];
		buffer.get(algorithmBytes);
		byte[] keyBytes = new byte[buffer.remaining()];
		buffer.get(keyBytes);
		return new SecretKeySpec(keyBytes, new String(algorithmBytes, StandardCharsets.UTF_8));
	}

	@Override
	public Certificate[] engineGetCertificateChain(String alias) {
		Slog.i(TAG, "engineGetCertificateChain(" + alias + ") called");
		return new Certificate[0];
	}

	@Override
	public Certificate engineGetCertificate(String alias) {
		Slog.i(TAG, "engineGetCertificate(" + alias + ") called");
		return null;
	}

	@Override
	public Date engineGetCreationDate(String alias) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineGetCreationDate'");
	}

	@Override
	public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
		byte[] algorithm = key.getAlgorithm().getBytes(StandardCharsets.UTF_8);
		byte[] encoded = key.getEncoded();
		ByteBuffer buffer = ByteBuffer.allocate(4 + algorithm.length + encoded.length);
		buffer.putInt(algorithm.length);
		buffer.put(algorithm);
		buffer.put(encoded);
		nativeSetKey(ATLLoadedApp.getPrimaryApplication().getApplication().getPackageName(), alias, buffer.array());
	}

	@Override
	public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineSetKeyEntry'");
	}

	@Override
	public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineSetCertificateEntry'");
	}

	@Override
	public void engineDeleteEntry(String alias) throws KeyStoreException {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineDeleteEntry'");
	}

	@Override
	public Enumeration<String> engineAliases() {
		Slog.i(TAG, "engineAliases() called");
		return Collections.emptyEnumeration();
	}

	@Override
	public boolean engineContainsAlias(String alias) {
		Slog.i(TAG, "engineContainsAlias(" + alias + ") called");
		return engineIsKeyEntry(alias);
	}

	@Override
	public int engineSize() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineSize'");
	}

	@Override
	public boolean engineIsKeyEntry(String alias) {
		byte[] data = nativeGetKey(ATLLoadedApp.getPrimaryApplication().getApplication().getPackageName(), alias);
		return data != null;
	}

	@Override
	public boolean engineIsCertificateEntry(String alias) {
		return false;
	}

	@Override
	public String engineGetCertificateAlias(Certificate cert) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineGetCertificateAlias'");
	}

	@Override
	public void engineStore(OutputStream stream, char[] password)
	    throws IOException, NoSuchAlgorithmException, CertificateException {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineStore'");
	}

	@Override
	public void engineLoad(InputStream stream, char[] password)
	    throws IOException, NoSuchAlgorithmException, CertificateException {
	}

	/**
	 * Android-compatible {@code AES/GCM/NoPadding} cipher.
	 *
	 * Android's AES-GCM cipher (Conscrypt) generates a 12-byte IV when the
	 * caller does not supply one, i.e. after {@code init(ENCRYPT_MODE, key)}.
	 * The BouncyCastle provider on the boot classpath instead falls back to the
	 * AES block size (16 bytes). Callers that rely on the Android convention
	 * then break: Tink's {@code AndroidKeystoreAesGcm} prepends the IV to the
	 * ciphertext and, on decryption, slices a fixed 12-byte IV off the front,
	 * so the GCM tag check fails with {@code AEADBadTagException: mac check in
	 * GCM failed} (this is the androidx.security master-key self-test failure
	 * seen by Bitwarden/Vipps).
	 *
	 * This SPI delegates every operation to BC unchanged - including the
	 * authentication tag check, an explicitly supplied IV/parameter spec and
	 * any AAD - and only replaces the implicit default IV with a random 12-byte
	 * one. See {@code Context} for the provider registration.
	 */
	public static class AesGcmCipher extends javax.crypto.CipherSpi {
		private static final int ANDROID_GCM_IV_LENGTH = 12;
		private static final int GCM_TAG_LENGTH_BITS = 128;

		private final javax.crypto.Cipher delegate;
		private final SecureRandom fallbackRandom = new SecureRandom();
		private SecureRandom random = fallbackRandom;

		public AesGcmCipher() {
			try {
				delegate = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding", "BC");
			} catch (Exception e) {
				throw new IllegalStateException("BouncyCastle AES/GCM/NoPadding is unavailable", e);
			}
		}

		// The algorithm name already fixes mode and padding on the delegate.
		@Override
		protected void engineSetMode(String mode) {}

		@Override
		protected void engineSetPadding(String padding) {}

		@Override
		protected int engineGetBlockSize() {
			return delegate.getBlockSize();
		}

		@Override
		protected int engineGetOutputSize(int inputLen) {
			return delegate.getOutputSize(inputLen);
		}

		@Override
		protected byte[] engineGetIV() {
			return delegate.getIV();
		}

		@Override
		protected java.security.AlgorithmParameters engineGetParameters() {
			return delegate.getParameters();
		}

		@Override
		protected int engineGetKeySize(Key key) {
			byte[] encoded = key.getEncoded();
			return encoded == null ? 0 : encoded.length * 8;
		}

		@Override
		protected void engineInit(int opmode, Key key, SecureRandom random)
		    throws java.security.InvalidKeyException {
			this.random = random != null ? random : fallbackRandom;
			if (opmode == javax.crypto.Cipher.ENCRYPT_MODE || opmode == javax.crypto.Cipher.WRAP_MODE) {
				byte[] iv = new byte[ANDROID_GCM_IV_LENGTH];
				this.random.nextBytes(iv);
				try {
					delegate.init(opmode, key, new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv), this.random);
				} catch (java.security.InvalidAlgorithmParameterException e) {
					// CipherSpi.engineInit(int, Key, SecureRandom) cannot report this
					// checked exception; a 12-byte GCM IV/tag is always valid for BC.
					throw new java.security.InvalidKeyException(
					    "AES/GCM parameter rejected: " + e.getMessage(), e);
				}
			} else {
				// No IV for decryption: delegate so the caller gets BC's/Android's
				// normal "IV not specified" failure instead of a silent decrypt.
				delegate.init(opmode, key, this.random);
			}
		}

		@Override
		protected void engineInit(int opmode, Key key, java.security.spec.AlgorithmParameterSpec params, SecureRandom random)
		    throws java.security.InvalidKeyException, java.security.InvalidAlgorithmParameterException {
			if (params == null) {
				engineInit(opmode, key, random);
				return;
			}
			this.random = random != null ? random : fallbackRandom;
			delegate.init(opmode, key, params, this.random);
		}

		@Override
		protected void engineInit(int opmode, Key key, java.security.AlgorithmParameters params, SecureRandom random)
		    throws java.security.InvalidKeyException, java.security.InvalidAlgorithmParameterException {
			if (params == null) {
				engineInit(opmode, key, random);
				return;
			}
			this.random = random != null ? random : fallbackRandom;
			delegate.init(opmode, key, params, this.random);
		}

		@Override
		protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
			return delegate.update(input, inputOffset, inputLen);
		}

		@Override
		protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
		    throws javax.crypto.ShortBufferException {
			return delegate.update(input, inputOffset, inputLen, output, outputOffset);
		}

		@Override
		protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
		    throws javax.crypto.IllegalBlockSizeException, javax.crypto.BadPaddingException {
			return delegate.doFinal(input, inputOffset, inputLen);
		}

		@Override
		protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
		    throws javax.crypto.ShortBufferException, javax.crypto.IllegalBlockSizeException, javax.crypto.BadPaddingException {
			return delegate.doFinal(input, inputOffset, inputLen, output, outputOffset);
		}

		@Override
		protected int engineUpdate(java.nio.ByteBuffer input, java.nio.ByteBuffer output)
		    throws javax.crypto.ShortBufferException {
			return delegate.update(input, output);
		}

		@Override
		protected int engineDoFinal(java.nio.ByteBuffer input, java.nio.ByteBuffer output)
		    throws javax.crypto.ShortBufferException, javax.crypto.IllegalBlockSizeException, javax.crypto.BadPaddingException {
			return delegate.doFinal(input, output);
		}

		@Override
		protected void engineUpdateAAD(byte[] src, int offset, int len) {
			delegate.updateAAD(src, offset, len);
		}

		@Override
		protected void engineUpdateAAD(java.nio.ByteBuffer src) {
			delegate.updateAAD(src);
		}
	}
}
