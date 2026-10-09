// SPDX-License-Identifier: GPL-3.0-only
import android.security.keystore.KeyGenParameterSpec;
import java.security.InvalidAlgorithmParameterException;

public class TestKeySpec {
	static class AESProbe extends android.security.keystore.KeyGenerator.AES {
		void initialize(KeyGenParameterSpec spec) throws Exception { engineInit(spec, null); }
	}
	static class HmacProbe extends android.security.keystore.KeyGenerator.HmacSHA512 {
		void initialize(KeyGenParameterSpec spec) throws Exception { engineInit(spec, null); }
	}
	public static void run() throws Exception {
		byte[] challenge = {1, 2, 3};
		KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder("test-only-no-key-generated", 1)
			.setAttestationChallenge(challenge);
		challenge[0] = 9;
		KeyGenParameterSpec spec = builder.build();
		builder.setAttestationChallenge(null);
		byte[] returned = spec.getAttestationChallenge(); returned[1] = 9;
		if (spec.getAttestationChallenge()[0] != 1 || spec.getAttestationChallenge()[1] != 2 ||
		    builder.build().getAttestationChallenge() != null)
			throw new AssertionError("Attestation challenge must survive caller and builder mutation");
		try { new AESProbe().initialize(spec); throw new AssertionError("AES silently ignored attestation"); }
		catch (InvalidAlgorithmParameterException expected) {}
		try { new HmacProbe().initialize(spec); throw new AssertionError("HMAC silently ignored attestation"); }
		catch (InvalidAlgorithmParameterException expected) {}
		System.out.println("PASS: attestation metadata is isolated and unsupported attestation is rejected before key generation");
	}
}
