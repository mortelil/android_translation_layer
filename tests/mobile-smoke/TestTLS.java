// SPDX-License-Identifier: GPL-3.0-only
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

public class TestTLS {
	public static void main(String[] args) throws Exception {
		X509KeyManager keys = new X509KeyManager() {
			public String[] getClientAliases(String t, Principal[] i) { return null; }
			public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return null; }
			public String[] getServerAliases(String t, Principal[] i) { return null; }
			public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
			public X509Certificate[] getCertificateChain(String a) { throw new AssertionError("No alias was selected"); }
			public PrivateKey getPrivateKey(String a) { throw new AssertionError("No alias was selected"); }
		};
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(new KeyManager[] {keys}, null, null);
		if (!context.getProvider().getName().toLowerCase().contains("wolf"))
			throw new AssertionError(context.getProvider());
		System.out.println("PASS: WolfSSL context initializes without client certificate; no socket connection made");
	}
}
