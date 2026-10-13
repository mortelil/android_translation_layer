/* SPDX-License-Identifier: GPL-3.0-only */
#include "apk_assets_cache.h"
#include <glib.h>
#include <glib/gstdio.h>
#include <assert.h>
#include <stdio.h>
int main(int argc, char **argv) {
    assert(argc==2);
    gchar *bytes=NULL;gsize size=0;
    assert(g_file_get_contents(argv[1],&bytes,&size,NULL));
    gchar *dir=g_dir_make_tmp("atl-assets-XXXXXX",NULL);assert(dir);
    gchar *path=g_build_filename(dir,"test.apk",NULL);
    assert(!atl_load_apk_assets(NULL,false));
    assert(!atl_load_apk_assets("",false));
    assert(!atl_load_apk_assets(path,false));
    assert(g_file_set_contents(path,bytes,size,NULL));
    const struct ApkAssets *first=atl_load_apk_assets(path,false);assert(first);
    assert(atl_load_apk_assets(path,false)==first);
    /* Atomic replacement ensures changed identity even on coarse-time filesystems. */
    assert(g_file_set_contents(path,bytes,size,NULL));
    const struct ApkAssets *replaced=atl_load_apk_assets(path,false);assert(replaced&&replaced!=first);
    assert(atl_load_apk_assets(path,false)==replaced);
    assert(!g_unlink(path));assert(!atl_load_apk_assets(path,false));assert(!g_rmdir(dir));
    g_free(path);g_free(dir);g_free(bytes);
    puts("PASS: shared asset identity, failed-load retry, replacement and removed path");
}
