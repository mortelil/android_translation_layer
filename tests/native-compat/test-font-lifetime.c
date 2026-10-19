// SPDX-License-Identifier: GPL-3.0-only
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
typedef struct AFont AFont;
typedef struct ASystemFontIterator ASystemFontIterator;
ASystemFontIterator *ASystemFontIterator_open(void);
AFont *ASystemFontIterator_next(ASystemFontIterator *);
void ASystemFontIterator_close(ASystemFontIterator *);
const char *AFont_getFontFilePath(const AFont *);
void AFont_close(AFont *);
int main(void) {
 for(int i=0;i<10;i++) {
  ASystemFontIterator *it=ASystemFontIterator_open(); assert(it);
  AFont *a=ASystemFontIterator_next(it),*b=ASystemFontIterator_next(it);assert(a && b);
  char *path=strdup(AFont_getFontFilePath(a)); assert(path && path[0]=='/');
  AFont_close(b); ASystemFontIterator_close(it);
  assert(strcmp(path,AFont_getFontFilePath(a))==0 && access(path,R_OK)==0);
  AFont_close(a);free(path);
 }
 puts("PASS: fonts and iterators have independent lifetime in both close orders");
}
