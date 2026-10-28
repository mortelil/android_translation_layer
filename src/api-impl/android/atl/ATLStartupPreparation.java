// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import dalvik.system.DexFile;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.zip.ZipFile;

/** Opt-in cache population through the real launch loader, without app callbacks. */
public final class ATLStartupPreparation {
    private ATLStartupPreparation() {}

    public static void prepare() throws IOException {
        // Creates the same PathClassLoader as launch. It does not instantiate the
        // Application, providers or activities, or initialize their classes.
        ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
        String verifier = System.getenv("ATL_APK_VERIFIER");
        if (verifier != null && !verifier.isEmpty() && app.pkg.atlSigningInfo == null)
            throw new IOException("APK signature verification failed during preparation");

        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(ATLPaths.api_impl_jar.getPath());
        if (ATLToggles.ATL_GHAX) paths.add(ATLPaths.ghax_jar.getPath());
        String path = System.getProperty("atl.app.class.path");
        ATLAppSplits.SplitSet splits = ATLAppSplits.resolve(path);
        if (!path.equals(splits.classPath))
            throw new IOException("Preparation requires a stable installed single APK path");
        for (String dex : splits.dexPath.split(File.pathSeparator))
            if (!dex.isEmpty()) paths.add(dex);
        for (String dex : paths) {
            try (ZipFile zip = new ZipFile(dex)) {
                if (zip.getEntry("classes.dex") == null) continue;
            }
            // ART may fall back to interpreting a dex after a compiler failure.
            // Loading it alone therefore is not evidence that preparation worked.
            // The pinned ART implements this via OatFileAssistant::IsUpToDate,
            // including dex checksums and runtime/boot-image compatibility.
            if (DexFile.isDexOptNeeded(dex))
                throw new IOException("ART cache is not up to date after preparation: " + dex);
        }
    }
}
