// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.content.pm.Signature;
import android.content.res.XmlBlock;
import android.content.res.XmlResourceParser;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.*;
import org.xmlpull.v1.XmlPullParser;

/** Manifest-identified APK sets. Containers are copied into a fresh private
 * directory, never into member-supplied paths and never over a previous cache.
 * Resolution happens before app classes, resources or native code are loaded.
 */
public final class ATLAppSplits {
	private static final String NS = "http://schemas.android.com/apk/res/android";
	private static final long APK_LIMIT = 1024L * 1024 * 1024;
	private static final long SET_LIMIT = 2 * APK_LIMIT;
	private static final int MEMBER_LIMIT = 256;
	private static final Map<String, SplitSet> cache = new HashMap<>();
	private ATLAppSplits() {}

	public static final class SplitSet {
		public final String classPath, dexPath, baseApk;
		public final String[] splitPaths;
		SplitSet(String all, String dex, String base) {
			classPath=all; dexPath=dex; baseApk=base;
			List<String> splits = new ArrayList<>();
			if (base != null) for (String p : all.split(":")) if (!p.equals(base) && p.endsWith(".apk")) splits.add(p);
			splitPaths = splits.toArray(new String[0]);
		}
	}
	private static final class InvalidSet extends IOException {
		final String status;
		final int exit;
		InvalidSet(String status, int exit, String message) { super(message); this.status=status; this.exit=exit; }
	}
	private static InvalidSet mismatch(String detail) { return new InvalidSet("SPLIT_MISMATCH",14,detail); }

	/** Called only at the native-owned primary launch boundary. Secondary loads
	 * use resolve(), whose IOException remains catchable by Java callers. */
	public static synchronized String resolveAppClassPath() throws IOException {
		String original = System.getProperty("atl.app.class.path");
		try {
			SplitSet set = resolve(original);
			return set.classPath;
		} catch (InvalidSet e) {
			System.err.println("ATL APP STATUS=" + e.status + ": " + e.getMessage());
			System.out.println("ATL APP STATUS=" + e.status + ": " + e.getMessage());
			System.exit(e.exit);
			throw e;
		}
	}

	public static synchronized SplitSet resolve(String path) throws IOException {
		SplitSet known = cache.get(path);
		if (known != null) return known;
		if (path == null || path.isEmpty()) return new SplitSet(path,path,null);
		List<String> apks = new ArrayList<>(), extra = new ArrayList<>();
		boolean container = false;
		for (String part : new LinkedHashSet<>(Arrays.asList(path.split(":")))) {
			if (part.isEmpty()) continue;
			String name = part.toLowerCase(Locale.ROOT);
			if (!name.endsWith(".apk") && !name.endsWith(".apkm")) { extra.add(part); continue; }
			try (ZipFile zip = new ZipFile(part)) {
				if (zip.getEntry("AndroidManifest.xml") != null) apks.add(part);
				else { apks.addAll(unpack(zip)); container = true; }
			}
		}
		if (apks.isEmpty() && !container) return new SplitSet(path,path,null);
		if (apks.size() > MEMBER_LIMIT) throw mismatch("Too many APKs");
		List<Identity> identities = new ArrayList<>();
		Identity base = null;
		Set<String> names = new HashSet<>();
		for (String apk : apks) {
			Identity id = identify(apk);
			identities.add(id);
			if (id.split == null) {
				if (base != null) throw mismatch("More than one manifest declares a base APK");
				base = id;
			} else if (!names.add(id.split)) throw mismatch("Duplicate split name: " + id.split);
		}
		if (base == null) throw new InvalidSet("SPLIT_NO_BASE",13,"No manifest without a split attribute");
		for (Identity id : identities)
			if (!base.pkg.equals(id.pkg) || base.version != id.version)
				throw mismatch("Package/version differs: " + id.path);
		identities.remove(base); identities.add(0,base);
		String host = System.getProperty("atl.native.arch", "");
		List<Identity> selected = new ArrayList<>();
		for (Identity id : identities) {
			// Only native-only configuration splits may be omitted for another ABI.
			// Never discard a feature/code split on this heuristic.
			if (container && id != base && !id.feature && !id.dex && !id.abis.isEmpty() && !id.abis.contains(host)) continue;
			selected.add(id);
		}
		boolean nativeCode = false, hostCode = false;
		for (Identity id : identities) { nativeCode |= !id.abis.isEmpty(); hostCode |= id.abis.contains(host); }
		if (container && nativeCode && !hostCode)
			throw new InvalidSet("CONTAINER_UNSUPPORTED",15,"No native code for host ABI " + host);
		Set<String> selectedNames = new HashSet<>();
		for (Identity id : selected) if (id.split != null) selectedNames.add(id.split);
		for (Identity id : selected) {
			if (id.requires != null && !selectedNames.contains(id.requires)) throw mismatch("Missing required split " + id.requires);
			if (id.configFor != null && !id.configFor.isEmpty() && !selectedNames.contains(id.configFor)) throw mismatch("Missing feature " + id.configFor);
		}
		List<String> all = new ArrayList<>(), dex = new ArrayList<>();
		for (Identity id : selected) { all.add(id.path); if (id.dex) dex.add(id.path); }
		all.addAll(extra); dex.addAll(extra);
		SplitSet set = new SplitSet(join(all),join(dex),base.path);
		// Build.VERSION's early manifest parser must see the base before signature
		// verification initializes Build. This does not load application code.
		boolean primary = path.equals(System.getProperty("atl.app.class.path"));
		if (primary) System.setProperty("atl.app.resolved.class.path",set.classPath);
		try {
			if (container || identities.size() > 1) verifySigners(identities);
		} catch (IOException e) {
			if (primary) System.clearProperty("atl.app.resolved.class.path");
			throw e;
		}
		cache.put(path,set); cache.put(set.classPath,set);
		return set;
	}

	private static String join(List<String> paths) {
		StringBuilder s = new StringBuilder();
		for (String p : paths) { if (s.length()>0) s.append(':'); s.append(p); }
		return s.toString();
	}
	private static final class Identity {
		String path, pkg, split, requires, configFor;
		long version;
		boolean dex, feature;
		Set<String> abis = new HashSet<>();
	}
	private static Identity identify(String file) throws IOException {
		Identity id = new Identity(); id.path=file;
		try (ZipFile zip = new ZipFile(file)) {
			ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
			if (manifest == null) throw mismatch("Missing manifest: " + file);
			byte[] bytes;
			try (InputStream in = zip.getInputStream(manifest); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
				copy(in,out,8*1024*1024); bytes=out.toByteArray();
			}
			try (XmlBlock block = new XmlBlock(bytes); XmlResourceParser xml = block.newParser()) {
				if (xml == null) throw mismatch("Unreadable manifest: " + file);
				int e;
				while ((e=xml.next()) != XmlPullParser.END_DOCUMENT) {
					if (e != XmlPullParser.START_TAG) continue;
					if (xml.getDepth()==1) {
						if (!"manifest".equals(xml.getName())) throw mismatch("Invalid manifest root");
						id.pkg=xml.getAttributeValue(null,"package");
						id.split=xml.getAttributeValue(null,"split");
						if (id.split != null && id.split.isEmpty()) id.split=null;
						id.configFor=xml.getAttributeValue(null,"configForSplit");
						id.feature=xml.getAttributeBooleanValue(NS,"isFeatureSplit",false);
						id.version=((long)xml.getAttributeIntValue(NS,"versionCodeMajor",0)<<32)
							| (xml.getAttributeIntValue(NS,"versionCode",0)&0xffffffffL);
					} else if (xml.getDepth()==2 && "uses-split".equals(xml.getName())) {
						if (id.requires != null) throw mismatch("Multiple uses-split declarations");
						id.requires=xml.getAttributeValue(NS,"name");
						if (id.requires == null || id.requires.isEmpty()) throw mismatch("Invalid uses-split");
					}
				}
			}
			if (id.pkg==null || id.pkg.isEmpty()) throw mismatch("Missing package identity: " + file);
			Enumeration<? extends ZipEntry> entries=zip.entries();
			Set<String> seen = new HashSet<>();
			while (entries.hasMoreElements()) {
				String n=entries.nextElement().getName();
				if (!seen.add(n)) throw mismatch("Duplicate APK ZIP member: " + n);
				if (seen.size()>100000) throw mismatch("Too many APK ZIP members");
				if (n.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) id.dex=true;
				String[] parts=n.split("/");
				if (parts.length==3 && parts[0].equals("lib") && parts[2].endsWith(".so")) id.abis.add(parts[1]);
			}
		} catch (IOException e) { throw e; }
		catch (Exception e) { throw mismatch("Unreadable manifest " + file + ": " + e); }
		return id;
	}
	private static void verifySigners(List<Identity> identities) throws IOException {
		String verifier=System.getenv("ATL_APK_VERIFIER");
		if (verifier==null || verifier.isEmpty()) throw mismatch("ATL_APK_VERIFIER is required for split sets");
		Set<Signature> expected=null;
		for (Identity id : identities) {
			try {
				Signature[] signers=ATLApkSignatures.verify(verifier,id.path).getApkContentsSigners();
				Set<Signature> actual=new HashSet<>(Arrays.asList(signers));
				if (actual.isEmpty() || (expected!=null && !expected.equals(actual))) throw mismatch("Split signers differ: " + id.path);
				expected=actual;
			} catch (Exception e) { throw mismatch("Signature verification failed for " + id.path + ": " + e); }
		}
	}

	private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
		byte[] b=new byte[65536]; long total=0; int n;
		while ((n=in.read(b))!=-1) {
			if (n>limit-total) throw new IOException("Uncompressed size limit exceeded");
			out.write(b,0,n); total+=n;
		}
		return total;
	}
	private static List<String> unpack(ZipFile zip) throws IOException {
		// The fresh directory's own entries are the only paths cleanup may unlink.
		// There is no recursive deletion and no reusable path/mtime/size cache.
		Path parent=ATLPaths.app_data_dir_base.toPath().toRealPath();
		final Path dir=Files.createTempDirectory(parent,"atl-splits-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		final List<Path> created=new ArrayList<>();
		Runnable cleanup=new Runnable() { @Override public void run() {
			for (int i=created.size()-1; i>=0; i--) { try { Files.deleteIfExists(created.get(i)); } catch (IOException ignored) {} }
			try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
		} };
		try {
			List<String> result=new ArrayList<>(); Set<String> seen=new HashSet<>(); long total=0;
			Enumeration<? extends ZipEntry> entries=zip.entries(); int count=0;
			while (entries.hasMoreElements()) {
				ZipEntry entry=entries.nextElement(); String name=entry.getName();
				if (++count>100000) throw new IOException("Too many container entries");
				if (!seen.add(name)) throw new IOException("Duplicate container member: " + name);
				if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
				if (name.startsWith("/") || name.indexOf(':')>=0 || name.indexOf('\\')>=0 || Arrays.asList(name.split("/",-1)).contains("..")) throw new IOException("Unsafe member name: " + name);
				if (result.size()>=MEMBER_LIMIT) throw new IOException("Too many nested APKs");
				Path memberDir=Files.createDirectory(dir.resolve("member-"+result.size()));
				created.add(memberDir);
				String leaf=name.substring(name.lastIndexOf('/')+1);
				Path target=memberDir.resolve(leaf);
				created.add(target);
				try (InputStream in=zip.getInputStream(entry); OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
					total+=copy(in,out,Math.min(APK_LIMIT,SET_LIMIT-total));
				}
				result.add(target.toString());
			}
			if (result.isEmpty()) throw new IOException("Container has no APKs");
			Runtime.getRuntime().addShutdownHook(new Thread(cleanup,"ATL split cleanup"));
			return result;
		} catch (IOException e) { cleanup.run(); throw new InvalidSet("CONTAINER_UNSUPPORTED",15,e.getMessage()); }
	}
}
