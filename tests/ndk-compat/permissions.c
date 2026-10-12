// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
int chmod_tree(const char *, int);
static int mode(const char *path) { struct stat st; assert(!stat(path,&st)); return st.st_mode & 0777; }
int main(void) {
    char root[] = "/tmp/atl-permissions-XXXXXX";
    assert(mkdtemp(root)); assert(!chdir(root));
    assert(!mkdir("lib",0700)); assert(!mkdir("lib/sub",0700));
    int fd=open("lib/sub/aapt2",O_CREAT|O_WRONLY,0600); assert(fd>=0); close(fd);
    fd=open("private",O_CREAT|O_WRONLY,0600); assert(fd>=0); close(fd);
    assert(!symlink("../private","lib/link"));
    assert(!chmod_tree("lib",0755));
    assert(mode("lib/sub/aapt2")==0755 && mode("lib/sub")==0755);
    assert(mode("private")==0600);
    assert(!chmod_tree("lib/link",0755)); assert(mode("private")==0600);
    unlink("lib/link"); unlink("private"); unlink("lib/sub/aapt2");
    rmdir("lib/sub"); rmdir("lib"); chdir("/"); rmdir(root);
    puts("PASS: native library permissions preserve symlink targets");
}
