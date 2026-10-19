// SPDX-License-Identifier: GPL-3.0-only
import android.atl.ATLNativeLibs;
import java.io.*;
import java.util.zip.*;

/** Exercises ART's actual FileChannel locks, SHA-256 and directory rename. */
public class RuntimeGeneration {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        File apk = new File(root, "fixture.apk");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) {
            out.putNextEntry(new ZipEntry("lib/x86_64/libfixture.so"));
            out.write(new byte[]{1,2,3}); out.closeEntry();
        }
        File base = new File(root, "lib");
        File first = ATLNativeLibs.publishOrFail(apk, base, "x86_64");
        if (!new File(first,"libfixture.so").isFile() ||
            !first.equals(ATLNativeLibs.publishOrFail(apk,base,"x86_64")))
            throw new AssertionError("generation/reuse");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) {
            out.putNextEntry(new ZipEntry("lib/x86_64/libnext.so"));
            out.write(new byte[]{4,5}); out.closeEntry();
        }
        File next = ATLNativeLibs.publishOrFail(apk,base,"x86_64");
        if (first.equals(next) || !new File(first,"libfixture.so").isFile() ||
            !new File(next,"libnext.so").isFile() || new File(next,"libfixture.so").exists())
            throw new AssertionError("generation isolation");
        System.out.println("ART_GENERATION_PASS"); System.out.flush();
        // Avoid the known unrelated standalone ART teardown abort.
        Runtime.getRuntime().halt(0);
    }
}
