// SPDX-License-Identifier: GPL-3.0-only
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.content.pm.PackageInfo;

public final class TestSigningInfo {
 private static void rejectProtocol(String body) throws Exception {
  java.io.File helper = java.io.File.createTempFile("atl-verifier-test-", ".sh");
  try {
   try (java.io.FileOutputStream out = new java.io.FileOutputStream(helper)) {
    out.write(("#!/bin/sh\n" + body + "\n").getBytes("UTF-8"));
   }
   if (!helper.setExecutable(true, true)) throw new AssertionError("Cannot make test helper executable");
   try { android.atl.ATLApkSignatures.verify(helper.getAbsolutePath(), "/unused-fixture.apk"); }
   catch (Exception expected) { return; }
   throw new AssertionError("Invalid verifier protocol was accepted");
  } finally { helper.delete(); }
 }
 public static void run() throws Exception {
  Signature old = new Signature(new byte[] {1});
  Signature current = new Signature(new byte[] {2});
  Signature[] signers = {current};
  Signature[] history = {old, current};
  SigningInfo info = new SigningInfo(signers, history);
  signers[0] = old; history[0] = current;
  info.getApkContentsSigners()[0] = old;
  info.getSigningCertificateHistory()[0] = current;
  if (!info.hasPastSigningCertificates() || info.hasMultipleSigners() ||
      !info.getApkContentsSigners()[0].equals(current) || !info.getSigningCertificateHistory()[0].equals(old))
   throw new AssertionError("SigningInfo must preserve defensive copies and oldest-first history");
  SigningInfo multi = new SigningInfo(new Signature[] {old, current}, null);
  if (!multi.hasMultipleSigners() || multi.getSigningCertificateHistory() != null || multi.hasPastSigningCertificates())
   throw new AssertionError("Independent signers are not a rotation history");
  SigningInfo single = new SigningInfo(new Signature[] {current}, null);
  if (single.hasPastSigningCertificates() || !single.getSigningCertificateHistory()[0].equals(current))
   throw new AssertionError("Single current signer history");
  PackageParser.Package pkg = new PackageParser.Package("org.atl.signer.test");
  pkg.mSignatures = new Signature[] {current}; pkg.atlSigningInfo = info;
  PackageInfo pi = PackageParser.generatePackageInfo(pkg, new int[0], 0, 0, 0,
      new java.util.HashSet<String>(), new PackageUserState());
  if (pi.signatures != null || pi.signingInfo != null) throw new AssertionError("Unrequested signatures exposed");
  pi = PackageParser.generatePackageInfo(pkg, new int[0], PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES,
      0, 0, new java.util.HashSet<String>(), new PackageUserState());
  if (!pi.signatures[0].equals(old) || !pi.signingInfo.getApkContentsSigners()[0].equals(current))
   throw new AssertionError("Legacy and modern rotation semantics");
  rejectProtocol("exit 1");
  rejectProtocol("printf 'WRONG-PROTOCOL\\nC:AQ==\\n'");
  rejectProtocol("printf 'ATL-APK-SIGNERS-2\\nC:AQ==\\n'");
  rejectProtocol("head -c 270000 /dev/zero");
  System.out.println("PASS: ART verifier rejects nonzero exit, malformed protocol/certificates and excessive output");
  String verifier = System.getenv("ATL_TEST_APK_VERIFIER");
  String apk = System.getenv("ATL_TEST_SIGNED_APK");
  if (verifier != null && apk != null && android.os.Build.VERSION.SDK_INT >= 28) {
   SigningInfo verified = android.atl.ATLApkSignatures.verify(verifier, apk);
   if (verified.getApkContentsSigners().length != 1) throw new AssertionError("ART verifier protocol");
   System.out.println("PASS: external APK verifier protocol under ART");
  }
  System.out.println("PASS: signing metadata, flags, defensive copies, multiple signers and rotation semantics");
 }
}
