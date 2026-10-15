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

# One second of synthetic silence, with RIFF INFO tags (no private music).
import wave
music = root / 'Music'
music.mkdir(exist_ok=True)
with wave.open(str(music / 'tone.wav'), 'wb') as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(8000)
    wav.writeframes(b'\0\0' * 8000)
info = b'INFO'
for key, value in ((b'INAM', b'Test Song\0'), (b'IART', b'Test Artist\0'), (b'IPRD', b'Test Album\0'), (b'IGNR', b'Test Genre\0')):
    info += key + struct.pack('<I', len(value)) + value + (b'\0' if len(value) % 2 else b'')
path = music / 'tone.wav'
data = path.read_bytes() + b'LIST' + struct.pack('<I', len(info)) + info
data = data[:4] + struct.pack('<I', len(data) - 8) + data[8:]
path.write_bytes(data)
(root / 'Hidden' / 'hidden.wav').write_bytes(data)
(music / 'broken.mp3').write_bytes(b'not audio')
link = music / 'outside.wav'
if not link.exists() and not link.is_symlink():
    link.symlink_to(root / 'Hidden' / 'hidden.wav')
