# SPDX-License-Identifier: GPL-3.0-only
"""Create only synthetic red pixels in a dedicated test directory."""
import pathlib
import struct
import sys
import zlib


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


root = pathlib.Path(sys.argv[1])
png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\0\0\xff' * 2) * 2))
png += chunk(b'IEND', b'')
for directory in ('Camera', 'Hidden'):
    (root / directory).mkdir(parents=True, exist_ok=True)
    (root / directory / 'red.png').write_bytes(png)
(root / 'Hidden' / '.nomedia').touch()
