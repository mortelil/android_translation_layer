/* SPDX-License-Identifier: GPL-3.0-only */
#include <EGL/egl.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>

/* Dynamic EGL/GL lookups must use the same adapters as native relocations.
 * In particular, host EGL cannot consume ATL virtual surfaces. */
void (*bionic_eglGetProcAddress(char const *procname))(void)
{
	if (!procname) return NULL;
	char wrapped[256];
	if (strlen(procname) + sizeof("bionic_") <= sizeof(wrapped)) {
		snprintf(wrapped, sizeof(wrapped), "bionic_%s", procname);
		void *symbol = dlsym(RTLD_DEFAULT, wrapped);
		if (symbol) return (void (*)(void))symbol;
	}
	return eglGetProcAddress(procname);
}
