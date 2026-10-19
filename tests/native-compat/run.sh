#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cc "$repo/tests/native-compat/test-hardware-buffer.c" "$repo/src/libandroid/hardware_buffer.c" -I"$repo/src/libandroid" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" -o "$out/hardware-test"
"$out/hardware-test"
cc "$repo/tests/native-compat/test-shared-memory.c" "$repo/src/libandroid/shared_memory.c" -o "$out/shared-memory-test"
"$out/shared-memory-test"
cc "$repo/tests/native-compat/test-sync-fence.c" "$repo/src/libandroid/sync.c" -pthread -o "$out/sync-fence-test"
"$out/sync-fence-test"
cc "$repo/tests/native-compat/test-aaudio.c" $(pkg-config --cflags --libs alsa) -pthread -o "$out/audio-test"
# Synthetic ALSA device only: this test must never open a microphone.
printf 'pcm.!default { type null }\n' > "$out/alsa.conf"
ALSA_CONFIG_PATH="$out/alsa.conf" timeout 10 "$out/audio-test"
cc "$repo/tests/native-compat/test-font-lifetime.c" "$repo/src/libandroid/font.c" -o "$out/font-test"
"$out/font-test"

cc "$repo/tests/native-compat/test-hardware-transfer.c" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" -o "$out/transfer-test"
"$out/transfer-test"
