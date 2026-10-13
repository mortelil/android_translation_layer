/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_APK_ASSETS_CACHE_H
#define ATL_APK_ASSETS_CACHE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

struct ApkAssets;

/*
 * Return the process-wide ApkAssets instance for a version of `path`, loading it on first
 * use.  `directory` selects a DirectoryAssetsProvider (the path must then end
 * in '/'), otherwise a file APK is loaded.
 *
 * AOSP keeps one ApkAssets per (path, flags) in ResourcesManager and reuses it
 * for every AssetManager, rather than loading the same APK again for each
 * Resources.  That sharing is load-bearing for Resources.Theme.setTo(): the
 * androidfw implementation of Theme::SetTo() remaps the copied styles' asset
 * cookies to the destination AssetManager's cookies by matching ApkAssets
 * *instances*, so a configuration copy that re-loads the same APK files loses
 * every bag entry whose value lives in a re-loaded package (see
 * tests/theme-chain/probe.c).  Callers must therefore go through this helper
 * instead of ApkAssets_load()/ApkAssets_loadDir() directly.
 *
 * File identity, size and nanosecond modification/change times distinguish
 * replaced APKs. Old instances remain alive for managers already using them.
 * This is process-lifetime ownership, not a bounded eviction cache. Directory
 * contents are not recursively fingerprinted.
 *
 * Returns NULL if the path could not be loaded.  The result is owned by the
 * process and must not be freed.
 */
const struct ApkAssets *atl_load_apk_assets(const char *path, bool directory);

#ifdef __cplusplus
}
#endif

#endif /* ATL_APK_ASSETS_CACHE_H */
