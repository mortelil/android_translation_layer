#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# APP-COMPAT case F frozen-family identity manifest.
#
# Records sha256 of every signed family member, the .apkm container and every
# negative-set member so an ATL/Android run can be tied to the exact frozen
# inputs. Run after build.sh (host or container; coreutils + sha256sum only).
#
#   sh /home/morten/ATL/app-compat/tests/split-probe/freeze.sh [OUT_MANIFEST]
#
# Default manifest: /home/morten/ATL/agent-work/APP-COMPAT/F-SPLIT/freeze-manifest.txt
# Exit 0 on success; nonzero if the family directory is missing.
set -eu

src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
family="$src/build/family"
out=${1:-/home/morten/ATL/agent-work/APP-COMPAT/F-SPLIT/freeze-manifest.txt}

[ -d "$family" ] || { printf 'missing family: %s\n' "$family" >&2; exit 2; }

mkdir -p "$(dirname -- "$out")"
tmp="$out.tmp"
{
	printf '# APP-COMPAT F-SPLIT frozen family manifest\n'
	printf '# generated: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	printf '# sha256  size  relative-path\n'
	# family members (sorted), then negatives (sorted), then the .apkm.
	for f in $(CDPATH= cd -- "$family" && find . -type f \
			\( -name '*.apk' -o -name '*.apkm' -o -name '*.idsig' \) | sort); do
		abs="$family/${f#./}"
		printf '%s  %s  %s\n' "$(sha256sum "$abs" | cut -d' ' -f1)" \
			"$(wc -c < "$abs" | tr -d ' ')" "${f#./}"
	done
} > "$tmp"
mv "$tmp" "$out"
printf 'frozen manifest: %s (%s entries)\n' "$out" \
	"$(grep -vc '^#' "$out")"
