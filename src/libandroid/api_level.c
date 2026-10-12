/* NDK API-level query, missing from ATL's libc shim.
 *
 * android/api-level.h documents android_get_device_api_level() as returning the
 * device's API level, or -1 when it cannot be determined; bionic answers it from
 * the ro.build.version.sdk system property. ATL publishes its selected API level
 * through exactly that property: main-executable/main.c (
 * sync_android_api_level) reads Build.VERSION.SDK_INT and hands it to
 * bionic_set_android_sdk_version(), which bionic's libc exposes as
 * ro.build.version.sdk (bionic_translation libc.c, asserted by its own
 * tests/mobile/test-bionic.c). Reading the same property here keeps the native
 * answer identical to the Java one and avoids a second, hardcoded API level, and
 * keeps working when ATL selects the level from the manifest rather than a flag.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdlib.h>

#define ATL_PROP_VALUE_MAX 92 // bionic's PROP_VALUE_MAX

int bionic_android_get_device_api_level(void)
{
	static int (*property_get)(const char *, char *);
	if (!property_get)
		property_get = (int (*)(const char *, char *))dlsym(RTLD_DEFAULT, "__system_property_get");
	if (!property_get) // no property store: the level is unknown, not zero
		return -1;

	char value[ATL_PROP_VALUE_MAX];
	if (property_get("ro.build.version.sdk", value) <= 0)
		return -1;
	return atoi(value);
}
