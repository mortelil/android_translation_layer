// SPDX-License-Identifier: GPL-3.0-only
import com.android.apksig.ApkSigner;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Signs only the generated smoke fixture with disposable test keys. */
public final class SignFixture {
 public static void main(String[] args) throws Exception {
  KeyStore keys = KeyStore.getInstance("PKCS12");
  try (InputStream input = new FileInputStream(args[0])) { keys.load(input, "fixture-only".toCharArray()); }
  List<ApkSigner.SignerConfig> signers = new ArrayList<>();
  int count = Integer.parseInt(args[3]);
  for (int i = 1; i <= count; i++) {
   String alias = "fixture" + i;
   X509Certificate cert = (X509Certificate)keys.getCertificate(alias);
   signers.add(new ApkSigner.SignerConfig.Builder(alias,
    (PrivateKey)keys.getKey(alias, "fixture-only".toCharArray()), Collections.singletonList(cert)).build());
  }
  new ApkSigner.Builder(signers).setInputApk(new File(args[1])).setOutputApk(new File(args[2]))
   .setMinSdkVersion(28).setV1SigningEnabled(false).setV2SigningEnabled(true)
   .setV3SigningEnabled(count == 1).setV4SigningEnabled(false).build().sign();
 }
}
