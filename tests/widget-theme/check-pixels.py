#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Assert real GTK text pixels for each selector outcome, not just Java getters."""
import sys
from PIL import Image
image = Image.open(sys.argv[1]).convert('RGB')
for name, color in [('normal', (0xd0, 0x20, 0x30)), ('selected', (0x24, 0x68, 0xac)), ('disabled', (0x77, 0x77, 0x77))]:
    count = sum(pixel == color for pixel in image.getdata())
    assert count >= 20, f'{name}: only {count} exact colour pixels'
    print(f'PASS: {name} text has {count} expected colour pixels')
