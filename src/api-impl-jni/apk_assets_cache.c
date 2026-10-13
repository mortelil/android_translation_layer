/* SPDX-License-Identifier: GPL-3.0-only */
/* Process-wide ApkAssets cache; see apk_assets_cache.h for why it is required. */
#include <glib.h>
#include <sys/stat.h>
#include <inttypes.h>
#include <androidfw/androidfw_c_api.h>

#include "apk_assets_cache.h"

G_LOCK_DEFINE_STATIC(atl_apk_assets_cache);

static GHashTable *atl_apk_assets_table;

static void ensure_table(void)
{
	if (G_UNLIKELY(atl_apk_assets_table == NULL))
		atl_apk_assets_table = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, NULL);
}

const struct ApkAssets *atl_load_apk_assets(const char *path, bool directory)
{
	if (path == NULL || !*path)
		return NULL;

	/* Key on the flavour as well, so a directory and a file with the same
	 * spelling cannot be confused. */
	struct stat st;
	if (stat(path, &st) != 0)
		return NULL;
	char *key = g_strdup_printf("%s:%s:%ju:%ju:%ju:%jd:%ld:%jd:%ld",
	                           directory ? "dir" : "apk", path,
	                           (uintmax_t)st.st_dev, (uintmax_t)st.st_ino,
	                           (uintmax_t)st.st_size, (intmax_t)st.st_mtim.tv_sec,
	                           st.st_mtim.tv_nsec, (intmax_t)st.st_ctim.tv_sec,
	                           st.st_ctim.tv_nsec);
	const struct ApkAssets *assets;

	G_LOCK(atl_apk_assets_cache);
	ensure_table();
	assets = g_hash_table_lookup(atl_apk_assets_table, key);
	if (assets == NULL) {
		assets = directory ? ApkAssets_loadDir(path) : ApkAssets_load(path, false);
		if (assets != NULL)
			g_hash_table_insert(atl_apk_assets_table, g_strdup(key),
			                    (gpointer)assets);
	}
	G_UNLOCK(atl_apk_assets_cache);

	g_free(key);
	return assets;
}
