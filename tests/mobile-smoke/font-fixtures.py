#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate original rectangle glyphs; no third-party font data is embedded."""
import sys
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.designspaceLib import DesignSpaceDocument, AxisDescriptor, SourceDescriptor
from fontTools.varLib import build
from fontTools.ttLib import TTCollection

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)

def master(width, style):
    font = FontBuilder(1000, isTTF=True)
    font.setupGlyphOrder(['.notdef', 'A'])
    font.setupCharacterMap({65: 'A'})
    outlines = {}
    for name in ['.notdef', 'A']:
        pen = TTGlyphPen(None)
        pen.moveTo((0, 0))
        pen.lineTo((width - 50, 0))
        pen.lineTo((width - 50, 700))
        pen.lineTo((0, 700))
        pen.closePath()
        outlines[name] = pen.glyph()
    font.setupGlyf(outlines)
    font.setupHorizontalMetrics({name: (width, 0) for name in outlines})
    font.setupHorizontalHeader(ascent=800, descent=-200)
    font.setupNameTable(dict(familyName='ATL Test Rectangles', styleName=style,
                            uniqueFontIdentifier='ATLRectangle-' + style,
                            fullName='ATL Test Rectangles ' + style,
                            psName='ATLRectangle-' + style))
    font.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    font.setupPost()
    return font.font

narrow, wide = master(400, 'Regular'), master(800, 'Wide')
design = DesignSpaceDocument()
axis = AxisDescriptor()
axis.name, axis.tag = 'Width', 'wdth'
axis.minimum, axis.default, axis.maximum = 100, 100, 200
design.addAxis(axis)
for font, value in [(narrow, 100), (wide, 200)]:
    source = SourceDescriptor()
    source.font = font
    source.location = {'Width': value}
    source.name = str(value)
    design.addSource(source)
variable, _, _ = build(design)
variable.save(out / 'width.ttf')
collection = TTCollection()
collection.fonts = [narrow, wide]
collection.save(out / 'faces.ttc')
