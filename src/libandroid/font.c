// SPDX-License-Identifier: GPL-3.0-only
/*
 * AFont / ASystemFontIterator, the NDK font API of API 29. libxul.so references
 * AFont_close, AFont_getFontFilePath and ASystemFontIterator_open/_next/_close
 * as weak symbols, so the bionic linker lets them resolve to zero and a call
 * through them would jump to address 0; implementing them replaces that crash
 * with the real contract.
 *
 * Prototypes are the frozen platform headers (tag android-17.0.0_r1, commit
 * 94b4c163), env/art_standalone/libandroidfw/include/android/font.h:88-121 and
 * system_fonts.h:26-32: AFont_close does nothing for NULL, AFont_getFontFilePath
 * returns "an absolute path to the current font file", and that path must be
 * valid until AFont_close, and "the font file returned is guaranteed to be
 * opened with O_RDONLY".
 *
 * AOSP's iterator walks the configured system fonts (the <font> entries of
 * /system/etc/fonts.xml, SystemFonts::buildFontSetLocked, minikin
 * libs/minikin/SystemFonts.cpp). ATL has no Android font configuration; the
 * fonts this host actually has are the files under the FHS font directories,
 * which is what is enumerated here. Each AFont therefore carries the real
 * absolute path of one of those files and its own O_RDONLY descriptor, and the
 * iterator reports one font per file.
 *
 * FHS font directories are trees, not flat directories: a distro package
 * installs its family into a subdirectory (/usr/share/fonts/roboto/*.ttf,
 * .../dejavu/*.ttf), so the scan descends into subdirectories. A flat scan of
 * the directory roots finds no font at all on such a host, which leaves the
 * caller's font list empty -- libxul.so reacts to that with
 * MOZ_CRASH("No font files found") instead of starting. Symlinks are not
 * followed while descending (a symlinked directory is the usual way a font
 * tree becomes cyclic), while a symlinked font file is still enumerated
 * because its target is a regular file; the descent is also depth-bounded for
 * the same reason.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

struct AFont;
struct ASystemFontIterator;
typedef struct AFont AFont;
typedef struct ASystemFontIterator ASystemFontIterator;

struct AFont {
	int fd;
	char *path;
};

struct ASystemFontIterator {
	char **paths;
	size_t count;
	size_t index;
};

static const char *const atl_font_directories[] = {
	"/usr/share/fonts",
	"/usr/local/share/fonts",
};

static int atl_font_file(const char *name) {
	const char *dot = strrchr(name, '.');
	if (!dot) return 0;
	return !strcmp(dot, ".ttf") || !strcmp(dot, ".otf") || !strcmp(dot, ".ttc") || !strcmp(dot, ".otc");
}

/* Depth cap for the descent below a font directory root. Font trees are one
 * or two levels deep; the cap only bounds a pathological or cyclic tree. */
#define ATL_FONT_MAX_DEPTH 8

/* Whether the scan may descend into PATH: a real directory, never a symlink
 * (the usual way a font tree becomes cyclic) and never a hidden one
 * (fontconfig keeps per-directory bookkeeping in dotted entries). */
static int atl_font_subdirectory(const char *path) {
	struct stat st;
	if (lstat(path, &st) || !S_ISDIR(st.st_mode)) return 0;
	const char *base = strrchr(path, '/');
	return base != NULL && base[1] != '\0' && base[1] != '.';
}

static void atl_font_scan(const char *directory, char ***paths, size_t *count, size_t *capacity, int depth) {
	DIR *dir = opendir(directory);
	if (!dir) return;
	struct dirent *entry;
	while ((entry = readdir(dir))) {
		if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
		char *child;
		if (asprintf(&child, "%s/%s", directory, entry->d_name) < 0) continue;
		if (depth < ATL_FONT_MAX_DEPTH && atl_font_subdirectory(child)) {
			atl_font_scan(child, paths, count, capacity, depth + 1);
			free(child);
			continue;
		}
		struct stat st;
		if (stat(child, &st) || !S_ISREG(st.st_mode) || !atl_font_file(entry->d_name)) {
			free(child);
		} else {
			if (*count == *capacity) {
				size_t grown = *capacity ? *capacity * 2 : 32;
				char **bigger = realloc(*paths, grown * sizeof(**paths));
				if (!bigger) { free(child); break; }
				*paths = bigger;
				*capacity = grown;
			}
			(*paths)[(*count)++] = child;
		}
	}
	closedir(dir);
}

static int atl_font_path_compare(const void *left, const void *right) {
	return strcmp(*(char *const *)left, *(char *const *)right);
}

ASystemFontIterator *ASystemFontIterator_open(void) {
	ASystemFontIterator *iterator = calloc(1, sizeof(*iterator));
	if (!iterator) return NULL;
	size_t capacity = 0;
	for (size_t i = 0; i < sizeof(atl_font_directories) / sizeof(*atl_font_directories); i++)
		atl_font_scan(atl_font_directories[i], &iterator->paths, &iterator->count, &capacity, 0);
	if (iterator->count > 1)
		qsort(iterator->paths, iterator->count, sizeof(*iterator->paths), atl_font_path_compare);
	return iterator;
}

void ASystemFontIterator_close(ASystemFontIterator *iterator) {
	if (!iterator) return;
	for (size_t i = 0; i < iterator->count; i++) free(iterator->paths[i]);
	free(iterator->paths);
	free(iterator);
}

AFont *ASystemFontIterator_next(ASystemFontIterator *iterator) {
	while (iterator->index < iterator->count) {
		char *path = iterator->paths[iterator->index++];
		int fd = open(path, O_RDONLY | O_CLOEXEC);
		/* A font whose file cannot be opened cannot honour the header's O_RDONLY
		 * guarantee, so it is skipped rather than returned unusably. */
		if (fd < 0) continue;
		AFont *font = calloc(1, sizeof(*font));
		if (!font) { close(fd); return NULL; }
		font->fd = fd;
		font->path = strdup(path);
		if (!font->path) { close(fd); free(font); return NULL; }
		return font;
	}
	return NULL;
}

void AFont_close(AFont *font) {
	if (!font) return;
	close(font->fd);
	free(font->path);
	free(font);
}

const char *AFont_getFontFilePath(const AFont *font) {
	return font->path;
}
