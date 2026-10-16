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

	// AOSP AndroidKeyStoreKeyGeneratorSpi.AES defaults to 128 bits. These
	// software keys do not enforce Android hardware authorization policies.
	private static int aesKeySize(KeyGenParameterSpec spec) throws InvalidAlgorithmParameterException {
		int bits = spec.getKeySize() == -1 ? 128 : spec.getKeySize();
		if (bits != 128 && bits != 192 && bits != 256)
			throw new InvalidAlgorithmParameterException("AES key size must be 128, 192 or 256");
		return bits;
	}

	public static class AES extends KeyGenerator {
		@Override
		protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
		    throws InvalidAlgorithmParameterException {
			validateAttestation(params);
			int bits = aesKeySize((KeyGenParameterSpec) params);
			try {
				keyGenerator = javax.crypto.KeyGenerator.getInstance("AES", "BC");
				this.params = params;
				// bind the requested key size; javax.crypto.KeyGenerator.init(random)
				// would fall back to the provider default (128 bit) and silently
				// produce a key of the wrong strength.
				keyGenerator.init(bits, random);
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
