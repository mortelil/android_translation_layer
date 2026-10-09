// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded protocol to an explicit host-side APK signature verifier. */
public final class ATLApkSignatures {
	private ATLApkSignatures() {}
	private static final class Output {
		final int status;
		final String text;
		Output(int status, String text) { this.status = status; this.text = text; }
	}
	public static android.content.pm.SigningInfo verify(String verifier, String apk) throws Exception {
		if (!new java.io.File(verifier).isAbsolute()) throw new IOException("APK verifier path must be absolute");
		final Process process = new ProcessBuilder(verifier, apk, Integer.toString(Build.VERSION.SDK_INT))
			.redirectErrorStream(true).start();
		FutureTask<Output> reader = new FutureTask<>(new java.util.concurrent.Callable<Output>() {
			@Override public Output call() throws Exception {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (InputStream input = process.getInputStream()) {
				byte[] buffer = new byte[4096]; int count;
				while ((count = input.read(buffer)) != -1) {
					if (bytes.size() + count > 256 * 1024) throw new IOException("APK verifier output too large");
					bytes.write(buffer, 0, count);
				}
			}
			return new Output(process.waitFor(), bytes.toString("UTF-8"));
			}
		});
		Thread worker = new Thread(reader, "ATL APK signature verification");
		worker.setDaemon(true); worker.start();
		try {
			Output output = reader.get(60, TimeUnit.SECONDS);
			if (output.status != 0) throw new IOException("APK verifier rejected package: " +
				output.text.substring(0, Math.min(output.text.length(), 2048)));
			String[] lines = output.text.split("\n");
			if (lines.length < 2 || lines.length > 65 || !lines[0].equals("ATL-APK-SIGNERS-2"))
				throw new IOException("Invalid APK verifier protocol");
			java.util.ArrayList<Signature> current = new java.util.ArrayList<>();
			java.util.ArrayList<Signature> history = new java.util.ArrayList<>();
			for (int i = 1; i < lines.length; i++) {
				if (!lines[i].startsWith("C:") && !lines[i].startsWith("H:"))
					throw new IOException("Invalid signer record");
				byte[] certificate = Base64.decode(lines[i].substring(2), Base64.NO_WRAP);
				if (certificate.length == 0 || certificate.length > 65536)
					throw new IOException("Invalid signer certificate size");
				java.security.cert.CertificateFactory.getInstance("X.509")
					.generateCertificate(new java.io.ByteArrayInputStream(certificate));
				(lines[i].startsWith("C:") ? current : history).add(new Signature(certificate));
			}
			if (current.isEmpty() || current.size() > 32 || history.size() > 32)
				throw new IOException("Invalid signer count");
			if (!history.isEmpty() && (current.size() != 1 || !current.get(0).equals(history.get(history.size() - 1))))
				throw new IOException("Signing history does not end with current signer");
			return new android.content.pm.SigningInfo(current.toArray(new Signature[0]),
				history.isEmpty() ? null : history.toArray(new Signature[0]));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw interrupted;
		} finally {
			process.destroyForcibly();
			reader.cancel(true);
		}
	}
}
