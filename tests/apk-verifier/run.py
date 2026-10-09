#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Real signature and tamper tests; no user APKs, credentials or signing keys."""
import base64
import pathlib
import subprocess
import sys
import tempfile
import zipfile

repo = pathlib.Path(__file__).resolve().parents[2]
helper = pathlib.Path(sys.argv[1]).resolve()
unsigned = pathlib.Path(sys.argv[2]).resolve()
jar = helper.parent / 'apksig-8.6.1.jar'
jdk = pathlib.Path('/usr/lib/jvm/java-1.8-openjdk/bin')

def run(*args):
    return subprocess.run([str(a) for a in args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

with tempfile.TemporaryDirectory(prefix='atl-apk-signatures-') as tmp:
    tmp = pathlib.Path(tmp)
    keys = tmp / 'keys.p12'
    for i in (1, 2):
        run(jdk / 'keytool', '-genkeypair', '-alias', 'fixture' + str(i), '-keystore', keys,
            '-storetype', 'PKCS12', '-storepass', 'fixture-only', '-keypass', 'fixture-only',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=ATL test only', '-noprompt')
    run(jdk / 'javac', '-cp', jar, '-d', tmp, repo / 'tests/apk-verifier/SignFixture.java')
    expected = [run(jdk / 'keytool', '-exportcert', '-alias', 'fixture' + str(i),
                    '-keystore', keys, '-storepass', 'fixture-only').stdout for i in (1, 2)]
    def verify(path, valid, count=1, sdk='28'):
        p = subprocess.run([str(helper), str(path), sdk], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=65)
        if valid:
            assert p.returncode == 0, p.stderr.decode()
            lines = p.stdout.decode().splitlines()
            assert lines[0] == 'ATL-APK-SIGNERS-2'
            certs = [base64.b64decode(line[2:], validate=True) for line in lines[1:] if line.startswith('C:')]
            assert len(certs) == count and set(certs) == set(expected[:count])
        else:
            assert p.returncode != 0 and p.stdout == b'', 'Rejected APK leaked signer output'
    for count in (1, 2):
        signed = tmp / ('signed%d.apk' % count)
        run(jdk / 'java', '-cp', str(tmp) + ':' + str(jar), 'SignFixture', keys, unsigned, signed, count)
        verify(signed, True, count)
        if count == 1:
            for sdk in ('24', '35'):
                verify(signed, True, sdk=sdk)
            verify(signed, False, sdk='36')
            data = bytearray(signed.read_bytes())
            with zipfile.ZipFile(signed) as archive:
                entry = archive.getinfo('classes.dex')
                offset = entry.header_offset
                name_len = int.from_bytes(data[offset+26:offset+28], 'little')
                extra_len = int.from_bytes(data[offset+28:offset+30], 'little')
                data[offset + 30 + name_len + extra_len + 10] ^= 1
            tampered = tmp / 'tampered.apk'
            tampered.write_bytes(data)
            verify(tampered, False)
            truncated = tmp / 'truncated.apk'
            truncated.write_bytes(data[:len(data)//2])
            verify(truncated, False)
    verify(unsigned, False)
    verify(tmp / 'missing.apk', False)
print('PASS: verified real v2/v3 signers, multiple signers, API bounds, tampered/unsigned/truncated/missing rejection')
