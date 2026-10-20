#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:-/tmp/atl-media-codec-csd}
mkdir -p "$out"
${CC:-cc} -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
	"$repo/tests/media-codec-csd/Probe.c" -o "$out/probe"
ASAN_OPTIONS=${ASAN_OPTIONS:-detect_leaks=0} "$out/probe"
