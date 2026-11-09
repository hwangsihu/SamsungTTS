// Voice files served straight from their descriptors (in the APK or a voice pack) under a made-up folder, so the
// 2014 engines, which open voice files by path with fopen(), need no copies on disk. Files a pack stores compressed
// are served from memory instead.
#pragma once

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define VOICEFS_DIR "/voicefs/"

struct voicefs_blob {
    int refs;
    char* data;
};

struct voicefs_entry {
    char name[128];
    int fd;
    struct voicefs_blob* blob;
    off64_t offset;
    off64_t length;
};

struct voicefs_file {
    int fd;
    struct voicefs_blob* blob;
    off64_t offset;
    off64_t length;
    off64_t pos;
};

static struct voicefs_entry voicefs_entries[64];
static int voicefs_count;

static void voicefs_release(struct voicefs_blob* blob) {
    if (__atomic_sub_fetch(&blob->refs, 1, __ATOMIC_ACQ_REL) == 0) free(blob);
}

static void voicefs_clear(void) {
    for (int i = 0; i < voicefs_count; i++) {
        if (voicefs_entries[i].blob) voicefs_release(voicefs_entries[i].blob);
        else close(voicefs_entries[i].fd);
    }
    voicefs_count = 0;
}

static struct voicefs_entry* voicefs_add(const char* name) {
    if (voicefs_count == (int) (sizeof voicefs_entries / sizeof *voicefs_entries) ||
        strlen(name) >= sizeof voicefs_entries[0].name) return NULL;
    struct voicefs_entry* e = &voicefs_entries[voicefs_count];
    strcpy(e->name, name);
    e->fd = -1;
    e->blob = NULL;
    e->offset = 0;
    return e;
}

static int voicefs_mount(const char* name, int fd, off64_t offset, off64_t length) {
    struct voicefs_entry* e = voicefs_add(name);
    if (!e || (e->fd = dup(fd)) < 0) return -1;
    e->offset = offset;
    e->length = length;
    voicefs_count++;
    return 0;
}

// Mounts a file held in memory and returns the buffer for its length bytes, for the caller to fill.
static char* voicefs_mount_memory(const char* name, off64_t length) {
    struct voicefs_entry* e = voicefs_add(name);
    struct voicefs_blob* blob = e ? (struct voicefs_blob*) malloc(sizeof *blob + (size_t) length) : NULL;
    if (!blob) return NULL;
    blob->refs = 1;
    blob->data = (char*) (blob + 1);
    e->blob = blob;
    e->length = length;
    voicefs_count++;
    return blob->data;
}

static int voicefs_read(void* cookie, char* buf, int size) {
    struct voicefs_file* f = (struct voicefs_file*) cookie;
    off64_t left = f->length - f->pos;
    if (size > left) size = (int) left;
    if (size <= 0) return 0;
    if (f->blob) {
        memcpy(buf, f->blob->data + f->pos, (size_t) size);
        f->pos += size;
        return size;
    }
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
    if (f->blob) voicefs_release(f->blob);
    else close(f->fd);
    free(f);
    return 0;
}

// Engines join folder and file names with an extra slash at times (smt_ko_KR_x01.am//TTSDBAA.dat).
static int voicefs_matches(const char* name, const char* path) {
    for (;; name++, path++) {
        while (*path == '/' && path[1] == '/') path++;
        if (*name != *path) return 0;
        if (!*name) return 1;
    }
}

// A read-only stream for a mounted name under VOICEFS_DIR; NULL for anything else, so the caller can fall back
// to the real fopen().
static FILE* voicefs_open(const char* path, const char* mode) {
    size_t dir = sizeof VOICEFS_DIR - 1;
    if (mode[0] != 'r' || strchr(mode, '+') || strncmp(path, VOICEFS_DIR, dir) != 0) return NULL;
    const char* name = path + dir;
    while (*name == '/') name++;
    for (int i = 0; i < voicefs_count; i++) {
        struct voicefs_entry* e = &voicefs_entries[i];
        if (!voicefs_matches(e->name, name)) continue;
        struct voicefs_file* f = (struct voicefs_file*) malloc(sizeof *f);
        if (!f) return NULL;
        f->blob = e->blob;
        f->fd = e->blob ? -1 : dup(e->fd);
        f->offset = e->offset;
        f->length = e->length;
        f->pos = 0;
        if (!f->blob && f->fd < 0) {
            free(f);
            return NULL;
        }
        if (f->blob) __atomic_add_fetch(&f->blob->refs, 1, __ATOMIC_ACQ_REL);
        FILE* stream = funopen(f, voicefs_read, NULL, voicefs_seek, voicefs_close);
        if (!stream) {
            if (f->blob) voicefs_release(f->blob);
            else close(f->fd);
            free(f);
        }
        return stream;
    }
    return NULL;
}
