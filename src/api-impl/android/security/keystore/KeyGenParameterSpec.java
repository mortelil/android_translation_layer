package android.security.keystore;

import java.math.BigInteger;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

public class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {

	private String keystoreAlias;
	private int purposes;
	private int keySize;
	private String[] blockModes;
	private String[] encryptionPaddings;
	private boolean userAuthenticationRequired;
	private byte[] attestationChallenge;

	private KeyGenParameterSpec() {}
	private KeyGenParameterSpec(KeyGenParameterSpec source) {
		keystoreAlias = source.keystoreAlias;
		purposes = source.purposes;
		keySize = source.keySize;
		blockModes = source.blockModes == null ? null : source.blockModes.clone();
		encryptionPaddings = source.encryptionPaddings == null ? null : source.encryptionPaddings.clone();
		userAuthenticationRequired = source.userAuthenticationRequired;
		attestationChallenge = source.getAttestationChallenge();
	}
	public byte[] getAttestationChallenge() {
		return attestationChallenge == null ? null : attestationChallenge.clone();
	}

	public static class Builder {
		private KeyGenParameterSpec spec = new KeyGenParameterSpec();

		public Builder(String keystoreAlias, int purposes) {
			spec.keystoreAlias = keystoreAlias;
			spec.purposes = purposes;
		}

		public Builder setKeySize(int keySize) {
			spec.keySize = keySize;
			return this;
		}

		public Builder setBlockModes(String[] blockModes) {
			spec.blockModes = blockModes;
			return this;
		}

		public Builder setEncryptionPaddings(String[] encryptionPaddings) {
			spec.encryptionPaddings = encryptionPaddings;
			return this;
		}

		public Builder setUserAuthenticationRequired(boolean userAuthenticationRequired) {
			spec.userAuthenticationRequired = userAuthenticationRequired;
			return this;
		}

		public Builder setCertificateSerialNumber(BigInteger serialNumber) {
			return this;
		}

		public Builder setCertificateSubject(X500Principal subject) {
			return this;
		}

		public Builder setCertificateNotBefore(Date notBefore) {
			return this;
		}

		public Builder setCertificateNotAfter(Date notAfter) {
			return this;
		}

		public Builder setDigests(String[] digests) {
			return this;
		}

		public Builder setAttestationChallenge(byte[] challenge) {
			spec.attestationChallenge = challenge == null ? null : challenge.clone();
			return this;
		}

		public Builder setSignaturePaddings(String[] signaturePaddings) {
			return this;
		}

		public KeyGenParameterSpec build() {
			return new KeyGenParameterSpec(spec);
		}
	}

	public int getKeySize() {
		return keySize;
	}

	public String[] getBlockModes() {
		return blockModes;
	}

	public int getPurposes() {
		return purposes;
	}

	public String[] getEncryptionPaddings() {
		return encryptionPaddings;
	}

	public boolean isUserAuthenticationRequired() {
		return userAuthenticationRequired;
	}

	public String getKeystoreAlias() {
		return keystoreAlias;
	}
}
