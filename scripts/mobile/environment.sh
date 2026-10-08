#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Source after setting atl_repo to this checkout's absolute path.
atl_workspace=${ATL_WORKSPACE:-$(dirname "$atl_repo")}
atl_prefix=${ATL_PREFIX:-$atl_workspace/.atl-runtime}
atl_build=${ATL_BUILD_DIR:-$atl_repo/build-mobile}
atl_art_dex=${ATL_ART_DEX_DIR:-/usr/lib/java/dex/art}
export LD_LIBRARY_PATH="$atl_build:$atl_prefix/lib:/usr/lib/art:$atl_art_dex/natives:/usr/lib/java/dex/android_translation_layer/natives:/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# Use the locally rebuilt provider, leaving the system's boot jars unchanged.
BOOTCLASSPATH=
for atl_jar in core-oj apachehttp apache-xml bouncycastle core-junit core-libart hamcrest junit-runner okhttp; do
	BOOTCLASSPATH=${BOOTCLASSPATH:+$BOOTCLASSPATH:}$atl_art_dex/$atl_jar-hostdex.jar
done
export BOOTCLASSPATH="$BOOTCLASSPATH:$atl_prefix/share/art/wolfssljni-hostdex.jar"
export RUN_FROM_BUILDDIR=1
