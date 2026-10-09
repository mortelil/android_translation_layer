// SPDX-License-Identifier: GPL-3.0-only
package android.content.pm;

/** Verified current signers and, for single signers, certificate rotation history. */
public final class SigningInfo {
	private final Signature[] current;
	private final Signature[] history;
	public SigningInfo() { this(null, null); }
	/** @hide Used by the package parser with verified certificates. */
	public SigningInfo(Signature[] current, Signature[] history) {
		this.current = current == null ? null : current.clone();
		this.history = current == null || current.length != 1 ? null :
			(history == null || history.length == 0 ? current.clone() : history.clone());
	}
	public Signature[] getApkContentsSigners() { return current == null ? null : current.clone(); }
	public boolean hasMultipleSigners() { return current != null && current.length > 1; }
	public Signature[] getSigningCertificateHistory() { return history == null ? null : history.clone(); }
	public boolean hasPastSigningCertificates() { return history != null && history.length > 1; }
}
