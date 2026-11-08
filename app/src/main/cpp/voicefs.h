// Voice files served straight from their descriptors (in the APK or a voice pack) under a made-up folder, so the
// 2014 engines, which open voice files by path with fopen(), need no copies on disk.
#pragma once

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define VOICEFS_DIR "/voicefs/"

struct voicefs_entry {
    char name[128];
    int fd;
    off64_t offset;
    off64_t length;
};

struct voicefs_file {
    int fd;
    off64_t offset;
    off64_t length;
    off64_t pos;
};

static struct voicefs_entry voicefs_entries[64];
static int voicefs_count;

static void voicefs_clear(void) {
    for (int i = 0; i < voicefs_count; i++) close(voicefs_entries[i].fd);
    voicefs_count = 0;
}

static int voicefs_mount(const char* name, int fd, off64_t offset, off64_t length) {
    if (voicefs_count == (int) (sizeof voicefs_entries / sizeof *voicefs_entries) ||
        strlen(name) >= sizeof voicefs_entries[0].name) return -1;
    struct voicefs_entry* e = &voicefs_entries[voicefs_count];
    e->fd = dup(fd);
    if (e->fd < 0) return -1;
    strcpy(e->name, name);
    e->offset = offset;
    e->length = length;
    voicefs_count++;
    return 0;
}

static int voicefs_read(void* cookie, char* buf, int size) {
    struct voicefs_file* f = (struct voicefs_file*) cookie;
    off64_t left = f->length - f->pos;
    if (size > left) size = (int) left;
    if (size <= 0) return 0;
    ssize_t n = pread64(f->fd, buf, (size_t) size, f->offset + f->pos);
    if (n > 0) f->pos += n;
    return (int) n;
}

static fpos_t voicefs_seek(void* cookie, fpos_t offset, int whence) {
    struct voicefs_file* f = (struct voicefs_file*) cookie;
    off64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? f->pos : f->length;
    if (base + offset < 0) {
        errno = EINVAL;
        return -1;
    }
    f->pos = base + offset;
    return (fpos_t) f->pos;
}

static int voicefs_close(void* cookie) {
    struct voicefs_file* f = (struct voicefs_file*) cookie;
    close(f->fd);
    free(f);
    return 0;
}

// A read-only stream for a mounted name under VOICEFS_DIR; NULL for anything else, so the caller can fall back
// to the real fopen().
static FILE* voicefs_open(const char* path, const char* mode) {
    size_t dir = sizeof VOICEFS_DIR - 1;
    if (mode[0] != 'r' || strchr(mode, '+') || strncmp(path, VOICEFS_DIR, dir) != 0) return NULL;
    for (int i = 0; i < voicefs_count; i++) {
        if (strcmp(voicefs_entries[i].name, path + dir) != 0) continue;
        struct voicefs_file* f = (struct voicefs_file*) malloc(sizeof *f);
        if (!f) return NULL;
        f->fd = dup(voicefs_entries[i].fd);
        f->offset = voicefs_entries[i].offset;
        f->length = voicefs_entries[i].length;
        f->pos = 0;
        FILE* stream = f->fd < 0 ? NULL : funopen(f, voicefs_read, NULL, voicefs_seek, voicefs_close);
        if (!stream) {
            if (f->fd >= 0) close(f->fd);
            free(f);
        }
        return stream;
    }
    return NULL;
}
