// SPDX-License-Identifier: Apache-2.0
// Adapted from OMP Sketchbook f81a24b's theme-chain probe.
// Exercise asset sharing and the native Theme::SetTo API directly. Cross-package
// remapping regression coverage also lives in ART tests/theme-copy.
// Usage: probe framework.apk app.apk package:style package:attr...
#include <androidfw/androidfw_c_api.h>

#include "apk_assets_cache.h"


#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ResolveAttrs()'s per-attribute output layout, as consumed by
 * android.content.res.TypedArray (AOSP STYLE_* indices). */
enum {
	STYLE_TYPE = 0,
	STYLE_DATA = 1,
	STYLE_ASSET_COOKIE = 2,
	STYLE_RESOURCE_ID = 3,
	STYLE_CHANGING_CONFIGURATIONS = 4,
	STYLE_DENSITY = 5,
	STYLE_NUM_ENTRIES = 7,
};

static int failures = 0;

#define CHECK(cond, ...)                              \
	do {                                          \
		if (!(cond)) {                        \
			failures++;                   \
			fprintf(stderr, "FAIL: ");    \
			fprintf(stderr, __VA_ARGS__); \
			fprintf(stderr, "\n");        \
		}                                     \
	} while (0)

struct Resolved {
	struct Res_value value;
	uint32_t flags;
	ApkAssetsCookie cookie;
};

static struct Resolved lookup(struct Theme *theme, uint32_t resid)
{
	struct Resolved out;
	memset(&out, 0, sizeof(out));
	out.cookie = Theme_getAttribute(theme, resid, &out.value, &out.flags);
	return out;
}

/* Resources.Theme.obtainStyledAttributes() -> AssetManager.resolveAttrs(): the
 * values TypedArray consumes directly (def style 0, no XML input). */
static size_t resolve_attrs(struct Theme *theme, const uint32_t *attrs, size_t count,
                            uint32_t *out_values)
{
	uint32_t out_indices[count + 1];
	memset(out_values, 0, count * STYLE_NUM_ENTRIES * sizeof(*out_values));
	memset(out_indices, 0, sizeof(out_indices));
	CHECK(ResolveAttrs(theme, 0 /* def_style_attr */, 0 /* def_style_res */, NULL, 0,
	                   (uint32_t *)attrs, count, out_values, out_indices),
	      "ResolveAttrs failed");
	return out_indices[0];
}

static bool is_concrete(uint32_t type)
{
	return type != TYPE_NULL && type != TYPE_ATTRIBUTE && type != TYPE_REFERENCE &&
	       type != TYPE_DYNAMIC_ATTRIBUTE && type != TYPE_DYNAMIC_REFERENCE;
}

/* Configuration used by ATL's AssetManager_init(): medium density, modern SDK. */
static void set_config(struct AssetManager *am, uint32_t ui_mode)
{
	struct ResTable_config config;
	memset(&config, 0, sizeof(config));
	config.size = sizeof(config);
	config.density = 160; /* ACONFIGURATION_DENSITY_MEDIUM */
	config.sdkVersion = 28;
	config.uiMode = ui_mode;
	AssetManager_setConfiguration(am, &config);
}

static struct AssetManager *new_manager(const struct ApkAssets **assets, size_t count,
                                        uint32_t ui_mode)
{
	struct AssetManager *am = AssetManager_new();
	CHECK(AssetManager_setApkAssets(am, assets, count, true, true), "setApkAssets failed");
	set_config(am, ui_mode);
	return am;
}

static uint32_t resolve_id(struct AssetManager *am, const char *spec)
{
	char *copy = strdup(spec);
	const char *name = copy;
	uint32_t id = 0;
	char *colon = strchr(copy, ':');
	if (colon) {
		*colon = '\0';
		id = AssetManager_getResourceId(am, colon + 1, "attr", name);
	}
	free(copy);
	return id;
}

int main(int argc, char **argv)
{
	if (argc < 5) {
		fprintf(stderr, "usage: %s <framework-res.apk> <app.apk> <package:style> <package:attr>...\n",
		        argv[0]);
		return 2;
	}
	const char *framework_path = argv[1];
	const char *app_path = argv[2];

	char *style_spec = strdup(argv[3]);
	char *style_colon = strchr(style_spec, ':');
	if (!style_colon) {
		fprintf(stderr, "style must be <package>:<name>\n");
		return 2;
	}
	*style_colon = '\0';
	const char *style_pkg = style_spec;
	const char *style_name = style_colon + 1;
	const size_t attr_count = argc - 4;

	/* The application's AssetManager: framework resources first, app last
	 * (ATL appends the app APK after the boot class path entries). */
	const struct ApkAssets *base_assets[] = {atl_load_apk_assets(framework_path, false),
	                                         atl_load_apk_assets(app_path, false)};
	CHECK(base_assets[0] && base_assets[1], "could not load the input APKs");
	if (!base_assets[0] || !base_assets[1])
		return 1;

	/* The cache must hand out one instance per path: that identity is what
	 * Theme::SetTo() matches when it remaps asset cookies. */
	CHECK(atl_load_apk_assets(framework_path, false) == base_assets[0] &&
	          atl_load_apk_assets(app_path, false) == base_assets[1],
	      "atl_load_apk_assets() returned a new instance for an already loaded path");

	struct AssetManager *base = new_manager(base_assets, 2, 0);

	const uint32_t style_res = AssetManager_getResourceId(base, style_name, "style", style_pkg);
	CHECK(style_res != 0, "style %s not found in %s", style_name, style_pkg);
	if (!style_res)
		return 1;

	uint32_t *ids = calloc(attr_count, sizeof(*ids));
	uint32_t *direct_values = calloc(attr_count * STYLE_NUM_ENTRIES, sizeof(*direct_values));
	uint32_t *copied_values = calloc(attr_count * STYLE_NUM_ENTRIES, sizeof(*copied_values));
	

	for (size_t i = 0; i < attr_count; i++) {
		ids[i] = resolve_id(base, argv[4 + i]);
		CHECK(ids[i] != 0, "attr %s not found", argv[4 + i]);
		if (!ids[i])
			return 1;
	}

	struct Theme *theme = AssetManager_newTheme(base);

	/* Negative control: an un-applied theme resolves nothing. */
	for (size_t i = 0; i < attr_count; i++) {
		CHECK(lookup(theme, ids[i]).cookie == -1, "un-applied theme unexpectedly has %s",
		      argv[4 + i]);
	}
	CHECK(resolve_attrs(theme, ids, attr_count, direct_values) == 0,
	      "an un-applied theme produced values for ResolveAttrs()");

	/* applyStyle() must pull in the whole parent chain. */
	Theme_applyStyle(theme, style_res, true);

	/* A configuration copy: a second AssetManager over the same APK files,
	 * selecting a different uiMode.  This is what ATL's
	 * AssetManager.createConfigurationCopy() + Context.createConfigurationContext()
	 * build. */
	struct AssetManager *copy = new_manager(base_assets, 2, 0x20 /* UI_MODE_NIGHT_YES */);
	struct Theme *copied = AssetManager_newTheme(copy);
	Theme_setTo(copied, theme);

	/* The source theme's attributes, and the TypedArray-facing values. */
	const size_t direct_count = resolve_attrs(theme, ids, attr_count, direct_values);
	for (size_t i = 0; i < attr_count; i++) {
		struct Resolved direct = lookup(theme, ids[i]);
		CHECK(direct.cookie >= 0, "applyStyle(%s) did not make %s resolvable (cookie %d)",
		      style_name, argv[4 + i], direct.cookie);
		CHECK(is_concrete(direct_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE]),
		      "%s resolved to an unresolved attribute in the source theme "
		      "(type 0x%02x data 0x%08x)",
		      argv[4 + i], direct_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE],
		      direct_values[i * STYLE_NUM_ENTRIES + STYLE_DATA]);
	}

	const size_t copied_count = resolve_attrs(copied, ids, attr_count, copied_values);
	CHECK(copied_count == direct_count,
	      "copying across configuration AssetManagers changed the number of resolved "
	      "attributes (%zu -> %zu)",
	      direct_count, copied_count);

	for (size_t i = 0; i < attr_count; i++) {
		const char *spec = argv[4 + i];
		struct Resolved direct = lookup(theme, ids[i]);
		struct Resolved via_copy = lookup(copied, ids[i]);

		CHECK(via_copy.cookie >= 0,
		      "Theme.setTo across configuration AssetManagers lost %s (destination cookie %d)",
		      spec, via_copy.cookie);
		CHECK(via_copy.cookie < 0 || (via_copy.value.dataType == direct.value.dataType &&
		                              via_copy.value.data == direct.value.data),
		      "Theme.setTo across configuration AssetManagers changed %s "
		      "(0x%02x/0x%08x != 0x%02x/0x%08x)",
		      spec, via_copy.value.dataType, via_copy.value.data, direct.value.dataType,
		      direct.value.data);

		/* TypedArray.getValue()/getDrawable() throw for TYPE_ATTRIBUTE, so the
		 * copied theme must hand out a concrete value as well. */
		const uint32_t copied_type = copied_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE];
		CHECK(is_concrete(copied_type),
		      "%s stays unresolved in the copied theme's TypedArray values "
		      "(type 0x%02x data 0x%08x)",
		      spec, copied_type, copied_values[i * STYLE_NUM_ENTRIES + STYLE_DATA]);
		CHECK(copied_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE] ==
		              direct_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE] &&
		          copied_values[i * STYLE_NUM_ENTRIES + STYLE_DATA] ==
		              direct_values[i * STYLE_NUM_ENTRIES + STYLE_DATA],
		      "the copied theme resolves %s to a different value "
		      "(0x%02x/0x%08x != 0x%02x/0x%08x)",
		      spec, copied_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE],
		      copied_values[i * STYLE_NUM_ENTRIES + STYLE_DATA],
		      direct_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE],
		      direct_values[i * STYLE_NUM_ENTRIES + STYLE_DATA]);

		fprintf(stderr,
		        "  %-44s type=0x%02x data=0x%08x cookie=%d"
		        " | copied cookie=%d type=0x%02x data=0x%08x | resolved type=0x%02x "
		        "data=0x%08x\n",
		        spec, direct.value.dataType, direct.value.data, direct.cookie,
		        via_copy.cookie, via_copy.value.dataType, via_copy.value.data,
		        direct_values[i * STYLE_NUM_ENTRIES + STYLE_TYPE],
		        direct_values[i * STYLE_NUM_ENTRIES + STYLE_DATA]);
	}

	/* The source theme must be untouched by the copy. */
	for (size_t i = 0; i < attr_count; i++) {
		CHECK(lookup(theme, ids[i]).cookie >= 0, "the copy mutated the source theme (%s)",
		      argv[4 + i]);
	}

	/* setTo() with an empty theme must clear the destination (the copy helper
	 * replaces the destination's style list with the source's, so this also
	 * checks that a subsequent copy does not resurrect stale styles). */
	struct Theme *empty = AssetManager_newTheme(base);
	Theme_setTo(copied, empty);
	CHECK(lookup(copied, ids[0]).cookie == -1, "setTo(empty) did not clear the destination theme");
	CHECK(resolve_attrs(copied, ids, attr_count, copied_values) == 0,
	      "a cleared theme still produced values for ResolveAttrs()");

	/* Documentation of the mechanism: had the copy loaded its own ApkAssets
	 * instances (the pre-M11 behaviour) raw Theme::setTo() would lose the
	 * app-package values as well.  Only assets shared between source and destination may contribute app attributes. */
	{
		const struct ApkAssets *fresh[] = {ApkAssets_load(framework_path, false),
		                                   ApkAssets_load(app_path, false)};
		struct AssetManager *independent = new_manager(fresh, 2, 0x20);
		struct Theme *independent_theme = AssetManager_newTheme(independent);
		Theme_setTo(independent_theme, theme);
		struct Resolved via_independent = lookup(independent_theme, ids[0]);
		CHECK(via_independent.cookie < 0, "independent app assets unexpectedly copied");

	}

	if (failures) {
		fprintf(stderr, "FAIL: %d theme-chain checks failed\n", failures);
		return 1;
	}
	printf("PASS: %s resolves %zu attribute(s) through its parent chain and survives "
	       "Theme.setTo across configuration AssetManagers\n",
	       style_name, attr_count);
	return 0;
}