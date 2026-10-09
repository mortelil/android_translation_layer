// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tools;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Host-JVM helper. Only verified signer certificates are written to stdout. */
public final class VerifyApk {
	public static void main(String[] args) {
		try {
			if (args.length != 2) throw new IllegalArgumentException("Expected APK path and API level");
			int sdk = Integer.parseInt(args[1]);
			if (sdk < 1 || sdk > 35) throw new IllegalArgumentException("Unsupported verifier API level");
			ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0]))
				.setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify();
			if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
				System.err.println("APK signature verification failed: " + result.getErrors());
				System.exit(1);
			}
			StringBuilder output = new StringBuilder("ATL-APK-SIGNERS-2\n");
			for (X509Certificate certificate : result.getSignerCertificates())
				output.append("C:").append(Base64.getEncoder().encodeToString(certificate.getEncoded())).append('\n');
			if (result.getSigningCertificateLineage() != null)
				for (X509Certificate certificate : result.getSigningCertificateLineage().getCertificatesInLineage())
					output.append("H:").append(Base64.getEncoder().encodeToString(certificate.getEncoded())).append('\n');
			System.out.print(output);
		} catch (Exception failure) {
			System.err.println("APK signature verification failed: " + failure);
			System.exit(1);
		}
	}
}
