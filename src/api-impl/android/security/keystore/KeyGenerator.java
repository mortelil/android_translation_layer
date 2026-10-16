package android.security.keystore;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

public abstract class KeyGenerator extends KeyGeneratorSpi {

	protected javax.crypto.KeyGenerator keyGenerator;
	protected AlgorithmParameterSpec params;
	private static void validateAttestation(AlgorithmParameterSpec params) throws InvalidAlgorithmParameterException {
		if (!(params instanceof KeyGenParameterSpec))
			throw new InvalidAlgorithmParameterException("Expected KeyGenParameterSpec");
		if (((KeyGenParameterSpec)params).getAttestationChallenge() != null)
			throw new InvalidAlgorithmParameterException("ATL's software keystore does not provide key attestation");
	}

	/**
	 * AOSP requires AES keys generated through the keystore to be bound to
	 * GCM/NoPadding and to have an explicit key size
	 * (android.security.keystore.AndroidKeyStoreKeyGeneratorSpi.AES).
	 * We enforce the same contract on the requested spec instead of silently
	 * ignoring it; we never substitute a different key size or mode.
	 */
	private static void validateAesSpec(KeyGenParameterSpec spec) throws InvalidAlgorithmParameterException {
		String[] blockModes = spec.getBlockModes();
		if (blockModes != null) {
			boolean gcm = false;
			for (String mode : blockModes)
				if ("GCM".equals(mode))
					gcm = true;
			if (!gcm)
				throw new InvalidAlgorithmParameterException(
				    "AES keystore keys require block mode GCM");
		}
		String[] paddings = spec.getEncryptionPaddings();
		if (paddings != null) {
			boolean noPadding = false;
			for (String padding : paddings)
				if ("NoPadding".equals(padding))
					noPadding = true;
			if (!noPadding)
				throw new InvalidAlgorithmParameterException(
				    "AES/GCM keystore keys require encryption padding NoPadding");
		}
		if (spec.getKeySize() <= 0)
			throw new InvalidAlgorithmParameterException(
			    "KeyGenParameterSpec.setKeySize(int) must be called for AES keystore keys");
	}

	public static class AES extends KeyGenerator {
		@Override
		protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
		    throws InvalidAlgorithmParameterException {
			validateAttestation(params);
			validateAesSpec((KeyGenParameterSpec) params);
			try {
				keyGenerator = javax.crypto.KeyGenerator.getInstance("AES", "BC");
				this.params = params;
				// bind the requested key size; javax.crypto.KeyGenerator.init(random)
				// would fall back to the provider default (128 bit) and silently
				// produce a key of the wrong strength.
				keyGenerator.init(((KeyGenParameterSpec) params).getKeySize(), random);
			} catch (NoSuchAlgorithmException | NoSuchProviderException e) {
				e.printStackTrace();
				throw new UnsupportedOperationException("Unimplemented method 'engineInit'");
			}
		}
	}

	public static class HmacSHA512 extends KeyGenerator {
		@Override
		protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
		    throws InvalidAlgorithmParameterException {
			validateAttestation(params);
			try {
				keyGenerator = javax.crypto.KeyGenerator.getInstance("HmacSHA512", "BC");
				this.params = params;
				keyGenerator.init(random);
			} catch (NoSuchAlgorithmException | NoSuchProviderException e) {
				e.printStackTrace();
				throw new UnsupportedOperationException("Unimplemented method 'engineInit'");
			}
		}
	}

	@Override
	protected SecretKey engineGenerateKey() {
		System.out.println("generating key with alias " + ((KeyGenParameterSpec)params).getKeystoreAlias());
		SecretKey key = keyGenerator.generateKey();
		new AndroidKeyStore().engineSetKeyEntry(((KeyGenParameterSpec)params).getKeystoreAlias(), key, null, null);
		return key;
	}

	@Override
	protected void engineInit(SecureRandom random) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineInit'");
	}

	@Override
	protected void engineInit(int keysize, SecureRandom random) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'engineInit'");
	}
}
