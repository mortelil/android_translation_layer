#!/usr/bin/env python3
"""Check presented pixels, independently of the Java callback counters."""
import json
import pathlib
import sys
from PIL import Image

shots = pathlib.Path(sys.argv[1])
result = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert result['status'] == 'PASS', result
counts = {}
for stage in ('initial', 'allowed', 'retained', 'updated'):
    with Image.open(shots / ('shot-' + stage + '.png')) as image:
        image = image.convert('RGB')
        colours = {c: n for n, c in image.getcolors(image.width * image.height)}
        counts[stage] = (colours.get((255, 0, 255), 0), colours.get((0, 255, 0), 0))
print(counts)
assert counts['initial'] == (0, 0), 'initial cancelled content was drawn'
assert counts['allowed'][0] > 10000 and counts['allowed'][1] == 0, 'first frame missing'
assert counts['retained'] == counts['allowed'], 'cancel erased or replaced previous frame'
assert counts['updated'][1] > 10000 and counts['updated'][0] == 0, 'replacement frame missing'
print('PASS: initial cancellation, visible-frame retention, and accepted replacement')
