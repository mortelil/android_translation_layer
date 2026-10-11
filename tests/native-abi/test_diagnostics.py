#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run the real Java ABI scanner on synthetic archives; no APK loading claimed.
Requires a JDK. Temporary Build/Slog shims provide only host identity and logging.
"""
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SOURCE = Path(__file__).resolve().parents[2] / 'src/api-impl/android/atl/ATLNativeAbi.java'

class NativeAbiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        sources = {
            'android/os/Build.java': 'package android.os; public class Build { public static String CPU_ABI="x86_64"; public static String[] SUPPORTED_ABIS={"x86_64"}; }',
            'android/util/Slog.java': 'package android.util; public class Slog { public static int w(String t,String m) { System.err.println(m); return 0; } }',
            'Main.java': 'public class Main { public static void main(String[] a) { android.atl.ATLNativeAbi.checkOrExit(a[0]); System.out.println("RETURNED"); } }',
        }
        paths = []
        for name, content in sources.items():
            p = cls.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            paths.append(str(p))
        subprocess.run(['javac', '-d', str(cls.root), str(SOURCE), *paths], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def archive(self, name, entries):
        p = self.root / name
        with zipfile.ZipFile(p, 'w') as z:
            for key, value in entries.items():
                z.writestr(key, value)
        return str(p)

    def check(self, paths, blocked):
        result = subprocess.run(['java', '-cp', str(self.root), 'Main', paths], capture_output=True, text=True)
        self.assertEqual(result.returncode, 12 if blocked else 0, result.stderr)
        self.assertEqual('ATL APP STATUS=ABI_BLOCKED' in result.stdout, blocked)
        self.assertEqual('RETURNED' in result.stdout, not blocked)
        if blocked:
            self.assertIn('host ABI is x86_64', result.stdout)

    def test_arm_only(self):
        self.check(self.archive('arm.apk', {'lib/arm64-v8a/libsample.so': b''}), True)

    def test_native_host(self):
        self.check(self.archive('host.apk', {'lib/x86_64/libsample.so': b''}), False)

    def test_split_set(self):
        arm = self.archive('arm.apk', {'lib/arm64-v8a/libsample.so': b''})
        host = self.archive('host.apk', {'lib/x86_64/libsample.so': b''})
        self.check(arm + ':' + host, False)

    def test_java_apk_embedded_arm_asset_is_not_an_installed_split(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as z:
            z.writestr('lib/arm64-v8a/libsample.so', b'')
        self.check(self.archive('java.apk', {'AndroidManifest.xml': b'', 'assets/example.apk': inner.getvalue()}), False)

    def test_bundle_arm_diagnostic(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as z:
            z.writestr('lib/arm64-v8a/libsample.so', b'')
        self.check(self.archive('arm.apkm', {'split.apk': inner.getvalue()}), True)

    def test_missing_file_is_left_to_loader(self):
        self.check(str(self.root / 'missing.apk'), False)

if __name__ == '__main__':
    unittest.main()
