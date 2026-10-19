#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(dirname "$(dirname "$here")")
mkdir -p "$1"
javac -d "$1" "$repo/src/api-impl/android/util/Range.java" "$repo/src/api-impl/android/media/MediaCodecInfo.java" "$here/Policy.java"
java -cp "$1" Policy
