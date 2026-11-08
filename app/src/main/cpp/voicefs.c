// For the native 2014 engines (32-bit processes): points a loaded engine's fopen() import at voicefs, so the
// engine reads its voice files from their descriptors instead of from copies.
#define _GNU_SOURCE  // for funopen
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <stdint.h>
#include <sys/mman.h>

#include "voicefs.h"

static FILE* voicefs_fopen(const char* path, const char* mode) {
    FILE* stream = voicefs_open(path, mode);
    return stream ? stream : fopen(path, mode);
}

struct library {
    const char* suffix;
    ElfW(Addr) base;
    const ElfW(Phdr)* phdr;
    ElfW(Half) phnum;
};

static int find_library(struct dl_phdr_info* info, size_t size, void* data) {
    struct library* lib = data;
    size_t n = info->dlpi_name ? strlen(info->dlpi_name) : 0, m = strlen(lib->suffix);
    if (n < m || strcmp(info->dlpi_name + n - m, lib->suffix) != 0) return 0;
    lib->base = info->dlpi_addr;
    lib->phdr = info->dlpi_phdr;
    lib->phnum = info->dlpi_phnum;
    return 1;
}

static int redirect(const struct library* lib, const ElfW(Rel)* rel, size_t count, const ElfW(Sym)* symtab,
                    const char* strtab, uintptr_t relro_start, uintptr_t relro_end) {
    uintptr_t page = (uintptr_t) sysconf(_SC_PAGESIZE);
    int patched = 0;
    for (size_t i = 0; i < count; i++) {
        unsigned type = ELF32_R_TYPE(rel[i].r_info);
        if (type != R_ARM_JUMP_SLOT && type != R_ARM_GLOB_DAT && type != R_ARM_ABS32) continue;
        if (strcmp(strtab + symtab[ELF32_R_SYM(rel[i].r_info)].st_name, "fopen") != 0) continue;
        uintptr_t slot = lib->base + rel[i].r_offset;
        // The GOT is read-only after loading when it sits in the RELRO segment; elsewhere it is writable data.
        int relro = slot >= relro_start && slot < relro_end;
        void* start = (void*) (slot & ~(page - 1));
        if (relro && mprotect(start, page, PROT_READ | PROT_WRITE) != 0) continue;
        *(void**) slot = (void*) voicefs_fopen;
        if (relro) mprotect(start, page, PROT_READ);
        patched++;
    }
    return patched;
}

JNIEXPORT jboolean JNICALL Java_com_hwangsihu_smt_engine_VoiceFs_nRedirect(JNIEnv* env, jclass cls, jstring library) {
    const char* name = (*env)->GetStringUTFChars(env, library, NULL);
    char suffix[128];
    snprintf(suffix, sizeof suffix, "/%s", name);
    (*env)->ReleaseStringUTFChars(env, library, name);
    struct library lib = {suffix, 0, NULL, 0};
    if (!dl_iterate_phdr(find_library, &lib)) return JNI_FALSE;

    const ElfW(Dyn)* dyn = NULL;
    uintptr_t relro_start = 0, relro_end = 0;
    for (int i = 0; i < lib.phnum; i++) {
        if (lib.phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn)*) (lib.base + lib.phdr[i].p_vaddr);
        if (lib.phdr[i].p_type == PT_GNU_RELRO) {
            relro_start = lib.base + lib.phdr[i].p_vaddr;
            relro_end = relro_start + lib.phdr[i].p_memsz;
        }
    }
    if (!dyn) return JNI_FALSE;
    const ElfW(Sym)* symtab = NULL;
    const char* strtab = NULL;
    const ElfW(Rel)* jmprel = NULL;
    const ElfW(Rel)* rel = NULL;
    size_t jmprel_size = 0, rel_size = 0;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB: symtab = (const ElfW(Sym)*) (lib.base + dyn->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char*) (lib.base + dyn->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = (const ElfW(Rel)*) (lib.base + dyn->d_un.d_ptr); break;
            case DT_PLTRELSZ: jmprel_size = dyn->d_un.d_val; break;
            case DT_REL: rel = (const ElfW(Rel)*) (lib.base + dyn->d_un.d_ptr); break;
            case DT_RELSZ: rel_size = dyn->d_un.d_val; break;
            default: break;
        }
    }
    if (!symtab || !strtab) return JNI_FALSE;
    int patched = 0;
    if (jmprel) patched += redirect(&lib, jmprel, jmprel_size / sizeof *jmprel, symtab, strtab, relro_start, relro_end);
    if (rel) patched += redirect(&lib, rel, rel_size / sizeof *rel, symtab, strtab, relro_start, relro_end);
    return patched > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_VoiceFs_nMount(JNIEnv* env, jclass cls, jstring name, jint fd,
                                                                    jlong offset, jlong length) {
    const char* n = (*env)->GetStringUTFChars(env, name, NULL);
    voicefs_mount(n, fd, offset, length);
    (*env)->ReleaseStringUTFChars(env, name, n);
}

JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_VoiceFs_nUnmountAll(JNIEnv* env, jclass cls) {
    voicefs_clear();
}
