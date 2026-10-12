#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
mkdir -p "$1"
out=$(realpath "$1")
prefix=${ATL_PREFIX:-$HOME/ATL/.atl-runtime}
cc -g -Wall -Wextra -pthread -I"$repo/src/libandroid" \
    "$repo/tests/choreographer/contracts.c" "$repo/src/libandroid/choreographer.c" \
    "$repo/src/libandroid/looper.c" $(pkg-config --cflags --libs gtk4) \
    -L"$prefix/lib" -Wl,-rpath,"$prefix/lib" -lutils -lcutils -llog -o "$out/contracts"
export LD_LIBRARY_PATH="$prefix/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
timeout 20 "$out/contracts"
dbus-run-session -- xvfb-run -a timeout 20 "$out/contracts" --gdk
