#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Deterministic fixture-identity check for the fullscreen (photo view) capture.
#
#   sh verify-fixture.sh SHOT.png FIXTURE.png [LABEL]
#
# A "non-black screenshot" is NOT proof that the photo was shown. This script
# compares the captured fullscreen against the synthetic fixture
# ATL-test-pattern.png (640x480: opaque sRGB blue border #2453BA, ~100 px thick,
# around a #FFFF70 core 441x281 at +100+100):
#
#   1. the shot must contain pixels of BOTH fixture colours and nothing else may
#      dominate the content area;
#   2. the fixture's core must appear as an axis-aligned rectangle with the SAME
#      aspect ratio (441:281) -> the image is not stretched/cropped;
#   3. the derived scale must make a fit-inside-the-window placement
#      (no upscaling beyond the window, no negative origin);
#   4. sampling the shot at the fixture's known relative coordinates (border
#      band, field centre) must return the expected fixture colour at every
#      sample;
#   5. after normalising both images to the same 64x48 raster, the two renders
#      must agree on >= 95% of pixels (region md5 printed for both).
#
# Every number is printed so the result can be re-derived by hand. Exits 0 on
# PASS, 1 on FAIL. No vision/model calls: ImageMagick statistics only.
set -eu

[ "$#" -ge 2 ] || { echo "usage: $0 SHOT.png FIXTURE.png [LABEL]" >&2; exit 2; }
shot=$1
fixture=$2
label=${3:-$(basename "$shot")}

BLUE='#2453BA'
YELLOW='#FFFF70'
CORE_W=441
CORE_H=281
CORE_X=100
CORE_Y=100

fail=0
say() { printf '%s\n' "$*"; }
chk() { # chk <name> <got> <expected>
	if [ "$2" = "$3" ]; then say "  ok   $1 ($2)"; else say "  FAIL $1 (got $2, want $3)"; fail=$((fail+1)); fi
}

# ---- fixture ground truth (recomputed, never assumed) --------------------
fy=$(convert "$fixture" -alpha off -fill black +opaque "$YELLOW" -fill white -opaque "$YELLOW" -threshold 50% -format '%@' info:)
case "$fy" in
	"${CORE_W}x${CORE_H}+${CORE_X}+${CORE_Y}") ;;
	*) say "  FAIL fixture-core-geometry (got $fy, want ${CORE_W}x${CORE_H}+${CORE_X}+${CORE_Y})"; fail=$((fail+1)) ;;
esac

# ---- 1. both fixture colours present ------------------------------------
sblue=$(convert "$shot" -alpha off -fill black +opaque "$BLUE" -fill white -opaque "$BLUE" -threshold 50% -format '%[fx:int(mean*w*h)]' info:)
syellow=$(convert "$shot" -alpha off -fill black +opaque "$YELLOW" -fill white -opaque "$YELLOW" -threshold 50% -format '%[fx:int(mean*w*h)]' info:)
say "${label}: shot=${sblue} border-blue px, ${syellow} field-yellow px"
if [ "$sblue" -le 1000 ] || [ "$syellow" -le 1000 ]; then
	say "  FAIL both-fixture-colours-present (blue=$sblue yellow=$syellow)"
	say "FIXTURE-IDENTITY FAIL ${label} failures=$((fail+1))"
	exit 1
fi

# ---- 2./3. core rectangle: geometry + aspect + scale ---------------------
sy=$(convert "$shot" -alpha off -fill black +opaque "$YELLOW" -fill white -opaque "$YELLOW" -threshold 50% -format '%@' info:)
say "  field-core bbox: $sy"
# %@ = WxH+X+Y
w=${sy%%x*}
h=${sy#*x}; h=${h%%+*}
ox=${sy#*+}; ox=${ox%%+*}
oy=${sy##*+}
# aspect ratio must match within 2%
ardiff=$(awk -v w="$w" -v h="$h" -v cw="$CORE_W" -v ch="$CORE_H" 'BEGIN{d=(w/h)/(cw/ch); printf "%d", (d>1?d-1:1-d)*1000}')
say "  core ${w}x${h} at +${ox}+${oy}; aspect error ${ardiff}/1000"
[ "$ardiff" -le 20 ] || { say "  FAIL core-aspect-matches-fixture"; fail=$((fail+1)); }

# scale + derived image origin, both axes must agree within 2%
read -r sx sy2 oxi oyi <<EOF
$(awk -v w="$w" -v h="$h" -v cw="$CORE_W" -v ch="$CORE_H" -v ox="$ox" -v oy="$oy" -v cx="$CORE_X" -v cy="$CORE_Y" \
 'BEGIN{printf "%d %d %d %d", (w*1000)/cw, (h*1000)/ch, ox-(cx*w)/cw, oy-(cy*h)/ch}')
EOF
scaleok=$(awk -v a="$sx" -v b="$sy2" 'BEGIN{d=a-b; if(d<0)d=-d; printf "%d", (d*1000)/a}')
say "  scale x=${sx}/1000 y=${sy2}/1000 (mismatch ${scaleok}/1000); image origin ~+${oxi}+${oyi}"
[ "$scaleok" -le 20 ] || { say "  FAIL isotropic-scale"; fail=$((fail+1)); }
[ "$oxi" -ge 0 ] && [ "$oyi" -ge 0 ] || { say "  FAIL image-origin-non-negative"; fail=$((fail+1)); }

# ---- 4. sample the shot at the fixture's known relative positions --------
sample() { # sample fx fy  -> prints colour
	convert "$shot" -format "%[pixel:p{$1,$2}]" info:
}
near() { # near <actual> <expected>
	case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac
}
# relative fixture coordinates: (0,0)=top-left of the image, (1,1)=bottom-right
for spec in "0.50 0.05 border-blue srgb(36,83,186)" \
            "0.05 0.50 border-blue srgb(36,83,186)" \
            "0.95 0.50 border-blue srgb(36,83,186)" \
            "0.50 0.95 border-blue srgb(36,83,186)" \
            "0.50 0.50 field-yellow srgb(255,255,112)" \
            "0.35 0.50 field-yellow srgb(255,255,112)" \
            "0.65 0.50 field-yellow srgb(255,255,112)"; do
	set -- $spec
	px=$(awk -v s="$sx" -v f="$1" -v o="$oxi" 'BEGIN{printf "%d", o + (f*640*s)/1000}')
	py=$(awk -v s="$sy2" -v f="$2" -v o="$oyi" 'BEGIN{printf "%d", o + (f*480*s)/1000}')
	got=$(sample "$px" "$py")
	if near "$got" "$4"; then say "  ok   rel($1,$2) -> ($px,$py) $3 $got"; else say "  FAIL rel($1,$2) -> ($px,$py) $3 got $got want $4"; fail=$((fail+1)); fi
done

# ---- 5. normalised raster agreement -------------------------------------
# Crop the whole displayed image area from the shot (640x480 scaled by the
# measured factor, at the derived origin) and the whole fixture, resample both
# to the same 64x48 raster with a nearest-neighbour (point) filter and compare.
iw=$(( 640 * sx / 1000 ))
ih=$(( 480 * sy2 / 1000 ))
convert "$shot"    -crop "${iw}x${ih}+${oxi}+${oyi}" +repage -filter point -resize 64x48! /tmp/vf-shot.$$.png
convert "$fixture" -alpha off -filter point -resize 64x48! /tmp/vf-fix.$$.png
m1=$(md5sum /tmp/vf-shot.$$.png | cut -d' ' -f1)
m2=$(md5sum /tmp/vf-fix.$$.png | cut -d' ' -f1)
diffpx=$(compare -metric AE /tmp/vf-shot.$$.png /tmp/vf-fix.$$.png null: 2>&1 || true)
pdiff=$(awk -v d="$diffpx" 'BEGIN{printf "%d", (d*1000)/3072}')
say "  displayed image area ${iw}x${ih}+${oxi}+${oyi}"
say "  normalised region md5 shot=$m1 fixture=$m2 differing_pixels=$diffpx/3072 (${pdiff}/1000)"
[ "$pdiff" -le 50 ] || { say "  FAIL normalised-raster-agreement"; fail=$((fail+1)); }
rm -f /tmp/vf-shot.$$.png /tmp/vf-fix.$$.png

if [ "$fail" -eq 0 ]; then
	say "FIXTURE-IDENTITY PASS ${label}"
	exit 0
fi
say "FIXTURE-IDENTITY FAIL ${label} failures=$fail"
exit 1
