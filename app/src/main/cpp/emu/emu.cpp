// Runs a 32-bit ARM Samsung TTS engine .so under dynarmic, with a tiny libc and a fake JNI,
// enough to load the library and let JNI_OnLoad register its native methods.
// PC harness: build with the pcbuild CMake, pass the .so path as argv[1].
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <ctime>
#include <map>
#include <string>
#include <vector>

#include <dynarmic/interface/A32/a32.h>
#include <dynarmic/interface/A32/config.h>

#ifdef __ANDROID__
#include "../voicefs.h"
#endif

using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

namespace {

// ---- emulated address space -------------------------------------------------
constexpr u32 ARENA_SIZE = 0x18000000;  // 384 MiB: engine image (~66 MiB bss) + heap + stack
constexpr u32 IMAGE_BASE = 0x00010000;
constexpr u32 EXTERN_DATA = 0x00001000;  // ctype tables, stack guard, __sF, cells
constexpr u32 IMPORT_THUNKS = 0x00004000;
constexpr u32 JNIENV_TABLE = 0x00005000;
constexpr u32 JNIENV_THUNKS = 0x00005400;
constexpr u32 JAVAVM_TABLE = 0x00006800;
constexpr u32 JAVAVM_THUNKS = 0x00006840;
constexpr u32 ENV_CELL = 0x00006a00;  // holds JNIENV_TABLE
constexpr u32 VM_CELL = 0x00006a04;   // holds JAVAVM_TABLE
constexpr u32 RETURN_TRAP = 0x00006b00;
constexpr u32 STACK_TOP = 0x17000000;

// SVC comment ranges
constexpr u32 SVC_RETURN = 0xFFFFFF;
constexpr u32 SVC_ENV = 0x010000;  // | slot
constexpr u32 SVC_VM = 0x020000;   // | slot

std::vector<u8> g_mem;
u32 g_heap = 0, g_heap_end = 0;
bool g_exited = false;
int g_exit_code = 0;

u8* host(u32 a) { return g_mem.data() + a; }
bool in_arena(u32 a, u32 n) { return a != 0 && u64(a) + n <= g_mem.size(); }
u32 r32(u32 a) { u32 v = 0; if (in_arena(a, 4)) std::memcpy(&v, host(a), 4); return v; }
void w32(u32 a, u32 v) { if (in_arena(a, 4)) std::memcpy(host(a), &v, 4); }
char* cstr(u32 a) { return a && a < g_mem.size() ? reinterpret_cast<char*>(host(a)) : const_cast<char*>(""); }

// ---- imports ----------------------------------------------------------------
struct Import { const char* name; };
std::vector<std::string> g_import_names;          // index -> name
std::map<std::string, u32> g_import_index;        // name -> index (thunk slot)
std::map<std::string, u32> g_data_syms;           // name -> emulated address

u32 import_thunk(const std::string& name) {
    auto it = g_import_index.find(name);
    if (it != g_import_index.end()) return IMPORT_THUNKS + it->second * 8;
    u32 idx = static_cast<u32>(g_import_names.size());
    g_import_names.push_back(name);
    g_import_index[name] = idx;
    u32 addr = IMPORT_THUNKS + idx * 8;
    w32(addr, 0xEF000000 | idx);  // svc #idx
    w32(addr + 4, 0xE12FFF1E);    // bx lr
    return addr;
}

// ---- simple first-fit heap with coalescing ----------------------------------
// header: [size incl header | inuse bit] ; blocks are 8-byte aligned.
u32 heap_alloc(u32 size) {
    if (size == 0) size = 1;
    size = (size + 7 + 8) & ~7u;  // + header, aligned
    for (u32 p = g_heap; p < g_heap_end;) {
        u32 h = r32(p);
        u32 bsize = h & ~1u;
        if (bsize == 0) break;
        if (!(h & 1) && bsize >= size) {
            if (bsize >= size + 16) {           // split
                w32(p + size, (bsize - size));  // free remainder
                w32(p, size | 1);
            } else {
                w32(p, bsize | 1);
            }
            return p + 8;
        }
        p += bsize;
    }
    return 0;
}
void heap_free(u32 ptr) {
    if (!ptr) return;
    u32 p = ptr - 8;
    u32 h = r32(p);
    w32(p, h & ~1u);
    // coalesce forward
    for (;;) {
        u32 bsize = r32(p) & ~1u;
        u32 nxt = p + bsize;
        if (nxt >= g_heap_end) break;
        u32 nh = r32(nxt);
        if (nh & 1) break;
        w32(p, bsize + (nh & ~1u));
    }
}
u32 heap_realloc(u32 ptr, u32 size) {
    if (!ptr) return heap_alloc(size);
    u32 old = (r32(ptr - 8) & ~1u) - 8;
    u32 n = heap_alloc(size);
    if (n) std::memcpy(host(n), host(ptr), old < size ? old : size);
    heap_free(ptr);
    return n;
}

// ---- printf-family ----------------------------------------------------------
struct VaArgs {
    Dynarmic::A32::Jit* jit;
    u32 sp;
    int index;      // logical word index over [r0..r3, stack...]
    bool mem = false;
    u32 base = 0;   // mem mode: va_list points here (for vsprintf)
    u32 word(int i) {
        if (mem) return r32(base + i * 4);
        if (i < 4) return jit->Regs()[i];
        return r32(sp + (i - 4) * 4);
    }
    u32 next32() { return word(index++); }
    u64 next64() {
        if (index & 1) index++;  // 8-byte alignment
        u32 lo = word(index++), hi = word(index++);
        return lo | (u64(hi) << 32);
    }
};

void do_format(std::string& out, const char* fmt, VaArgs& va) {
    for (const char* p = fmt; *p;) {
        if (*p != '%') { out.push_back(*p++); continue; }
        const char* start = p++;
        while (*p && std::strchr("-+ #0", *p)) p++;
        while (*p && (*p >= '0' && *p <= '9')) p++;
        if (*p == '.') { p++; while (*p >= '0' && *p <= '9') p++; }
        int len = 0;
        while (*p == 'l' || *p == 'h') { if (*p == 'l') len++; p++; }
        char conv = *p++;
        std::string spec(start, p);
        char buf[256];
        switch (conv) {
            case 'd': case 'i': case 'u': case 'x': case 'X': case 'o': case 'c': {
                if (len >= 2) { std::snprintf(buf, sizeof buf, spec.c_str(), (long long) (std::int64_t) va.next64()); }
                else { std::snprintf(buf, sizeof buf, spec.c_str(), (int) va.next32()); }
                out += buf; break;
            }
            case 'f': case 'F': case 'e': case 'g': case 'G': {
                union { u64 u; double d; } c{va.next64()};
                std::snprintf(buf, sizeof buf, spec.c_str(), c.d); out += buf; break;
            }
            case 'p': { std::snprintf(buf, sizeof buf, "0x%08x", va.next32()); out += buf; break; }
            case 's': { u32 a = va.next32(); out += cstr(a); break; }
            case '%': out.push_back('%'); break;
            default: out += spec; break;
        }
    }
}

// ---- files ------------------------------------------------------------------
constexpr u32 FILE_COOKIE = 0x40000000;
std::vector<FILE*> g_files;
FILE* file_of(u32 h) {
    if (h >= FILE_COOKIE && h - FILE_COOKIE < g_files.size()) return g_files[h - FILE_COOKIE];
    if (h >= EXTERN_DATA + 16 && h < EXTERN_DATA + 0x200) return stderr;  // __sF
    return nullptr;
}

// minimal sscanf: supports %d %u %x %f %g %s %c and literal/space matching
int do_sscanf(Dynarmic::A32::Jit& jit, const char* in, const char* fmt, int argidx) {
    VaArgs va{&jit, jit.Regs()[13], argidx};
    const char* s = in;
    int n = 0;
    for (const char* p = fmt; *p;) {
        if (std::isspace((unsigned char) *p)) { while (std::isspace((unsigned char) *s)) s++; p++; continue; }
        if (*p != '%') { if (*s != *p) break; s++; p++; continue; }
        p++;
        bool suppress = (*p == '*'); if (suppress) p++;
        while (std::isdigit((unsigned char) *p)) p++;
        char conv = *p++;
        while (std::isspace((unsigned char) *s)) s++;
        char* end = nullptr;
        if (conv == 'd' || conv == 'i') { long v = std::strtol(s, &end, 10); if (end == s) break; if (!suppress) { w32(va.next32(), u32(v)); n++; } s = end; }
        else if (conv == 'u') { unsigned long v = std::strtoul(s, &end, 10); if (end == s) break; if (!suppress) { w32(va.next32(), u32(v)); n++; } s = end; }
        else if (conv == 'x') { unsigned long v = std::strtoul(s, &end, 16); if (end == s) break; if (!suppress) { w32(va.next32(), u32(v)); n++; } s = end; }
        else if (conv == 'f' || conv == 'g' || conv == 'e') { float v = std::strtof(s, &end); if (end == s) break; if (!suppress) { u32 a = va.next32(); u32 b; std::memcpy(&b, &v, 4); w32(a, b); n++; } s = end; }
        else if (conv == 's') { u32 out = suppress ? 0 : va.next32(); while (*s && !std::isspace((unsigned char) *s)) { if (out) *host(out++) = u8(*s); s++; } if (out) *host(out) = 0; if (!suppress) n++; }
        else if (conv == 'c') { u32 out = suppress ? 0 : va.next32(); if (*s) { if (out) *host(out) = u8(*s); s++; if (!suppress) n++; } }
        else break;
    }
    return n;
}

// ---- libc shims -------------------------------------------------------------
double va_d(Dynarmic::A32::Jit& jit);

void shim(Dynarmic::A32::Jit& jit, const std::string& name) {
    auto& R = jit.Regs();
    auto ret = [&](u32 v) { R[0] = v; };
    auto ret64 = [&](u64 v) { R[0] = u32(v); R[1] = u32(v >> 32); };

    if (name == "malloc" || name == "_Znwj" || name == "_Znaj") { ret(heap_alloc(R[0])); return; }
    if (name == "calloc") { u32 n = R[0] * R[1], p = heap_alloc(n); if (p) std::memset(host(p), 0, n); ret(p); return; }
    if (name == "realloc") { ret(heap_realloc(R[0], R[1])); return; }
    if (name == "free" || name == "_ZdlPv" || name == "_ZdaPv") { heap_free(R[0]); return; }
    if (name == "memcpy" || name == "__memcpy_chk") { if (in_arena(R[0], R[2]) && in_arena(R[1], R[2])) std::memmove(host(R[0]), host(R[1]), R[2]); ret(R[0]); return; }
    if (name == "memmove") { if (in_arena(R[0], R[2]) && in_arena(R[1], R[2])) std::memmove(host(R[0]), host(R[1]), R[2]); ret(R[0]); return; }
    if (name == "memset") { if (in_arena(R[0], R[2])) std::memset(host(R[0]), int(R[1]), R[2]); ret(R[0]); return; }
    if (name == "memcmp") { ret(u32(std::memcmp(host(R[0]), host(R[1]), R[2]))); return; }
    if (name == "strlen" || name == "__strlen_chk") { ret(u32(std::strlen(cstr(R[0])))); return; }
    if (name == "strcmp") { ret(u32(std::strcmp(cstr(R[0]), cstr(R[1])))); return; }
    if (name == "strncmp") { ret(u32(std::strncmp(cstr(R[0]), cstr(R[1]), R[2]))); return; }
    if (name == "strcpy" || name == "__strcpy_chk") { std::strcpy(cstr(R[0]), cstr(R[1])); ret(R[0]); return; }
    if (name == "strncpy" || name == "__strncpy_chk") { std::strncpy(cstr(R[0]), cstr(R[1]), R[2]); ret(R[0]); return; }
    if (name == "strcat" || name == "__strcat_chk") { std::strcat(cstr(R[0]), cstr(R[1])); ret(R[0]); return; }
    if (name == "strncat" || name == "__strncat_chk") { std::strncat(cstr(R[0]), cstr(R[1]), R[2]); ret(R[0]); return; }
    if (name == "strchr") { char* s = cstr(R[0]); char* f = std::strchr(s, int(R[1])); ret(f ? R[0] + u32(f - s) : 0); return; }
    if (name == "strrchr") { char* s = cstr(R[0]); char* f = std::strrchr(s, int(R[1])); ret(f ? R[0] + u32(f - s) : 0); return; }
    if (name == "strstr") { char* s = cstr(R[0]); char* f = std::strstr(s, cstr(R[1])); ret(f ? R[0] + u32(f - s) : 0); return; }
    if (name == "strtod") { char* end = nullptr; double d = std::strtod(cstr(R[0]), &end); if (R[1]) w32(R[1], end ? R[0] + u32(end - cstr(R[0])) : R[0]); union { double d; u64 u; } c{d}; ret64(c.u); return; }
    if (name == "strtoul") { char* end = nullptr; unsigned long v = std::strtoul(cstr(R[0]), &end, int(R[2])); if (R[1]) w32(R[1], end ? R[0] + u32(end - cstr(R[0])) : R[0]); ret(u32(v)); return; }
    if (name == "atoi") { ret(u32(std::atoi(cstr(R[0])))); return; }
    if (name == "bsearch") { ret(0); return; }  // not used on load path
    if (name == "__aeabi_idiv") { int a = int(R[0]), b = int(R[1]); ret(b ? u32(a / b) : 0); return; }
    if (name == "__aeabi_idivmod") { int a = int(R[0]), b = int(R[1]); R[0] = b ? u32(a / b) : 0; R[1] = b ? u32(a % b) : 0; return; }
    if (name == "__aeabi_uidivmod") { u32 a = R[0], b = R[1]; R[0] = b ? a / b : 0; R[1] = b ? a % b : 0; return; }
    if (name == "cos") { union { double d; u64 u; } c{va_d(jit)}; c.d = std::cos(c.d); ret64(c.u); return; }
    if (name == "sin") { union { double d; u64 u; } c{va_d(jit)}; c.d = std::sin(c.d); ret64(c.u); return; }
    if (name == "exp") { union { double d; u64 u; } c{va_d(jit)}; c.d = std::exp(c.d); ret64(c.u); return; }
    if (name == "log") { union { double d; u64 u; } c{va_d(jit)}; c.d = std::log(c.d); ret64(c.u); return; }
    if (name == "pow") { double x = va_d(jit); VaArgs v{&jit, R[13], 2}; union { u64 u; double d; } y{v.next64()}; union { double d; u64 u; } r{std::pow(x, y.d)}; ret64(r.u); return; }
    if (name == "sqrt") { union { double d; u64 u; } c{va_d(jit)}; c.d = std::sqrt(c.d); ret64(c.u); return; }
    auto f_in = [&](int i) { float f; u32 b = R[i]; std::memcpy(&f, &b, 4); return f; };
    auto f_ret = [&](float f) { u32 b; std::memcpy(&b, &f, 4); R[0] = b; };
    if (name == "sinf") { f_ret(std::sin(f_in(0))); return; }
    if (name == "cosf") { f_ret(std::cos(f_in(0))); return; }
    if (name == "expf") { f_ret(std::exp(f_in(0))); return; }
    if (name == "logf") { f_ret(std::log(f_in(0))); return; }
    if (name == "sqrtf") { f_ret(std::sqrt(f_in(0))); return; }
    if (name == "powf") { f_ret(std::pow(f_in(0), f_in(1))); return; }
    if (name == "lrand48") { ret(u32(std::rand())); return; }
    if (name == "srand48") { std::srand(R[0]); return; }
    if (name == "property_get") {
        const char* key = cstr(R[0]); const char* def = R[2] ? cstr(R[2]) : "";
        std::string v = std::strstr(key, "manufacturer") ? "samsung" : def;
        std::strcpy(cstr(R[1]), v.c_str()); ret(u32(v.size())); return;
    }
    if (name == "__android_log_print") {
        VaArgs va{&jit, R[13], 3}; std::string s; do_format(s, cstr(R[2]), va);
        std::fprintf(stderr, "[log] %s\n", s.c_str()); ret(0); return;
    }
    if (name == "sprintf" || name == "__sprintf_chk") {
        u32 buf = R[0]; bool chk = name[2] == 's' ? false : true; (void) chk;
        VaArgs va{&jit, R[13], 2}; std::string s; do_format(s, cstr(R[1]), va);
        std::strcpy(cstr(buf), s.c_str()); ret(u32(s.size())); return;
    }
    if (name == "printf") { VaArgs va{&jit, R[13], 1}; std::string s; do_format(s, cstr(R[0]), va); std::fputs(s.c_str(), stdout); ret(u32(s.size())); return; }
    if (name == "vsprintf") { VaArgs va{&jit, 0, 0, true, R[2]}; std::string s; do_format(s, cstr(R[1]), va); std::strcpy(cstr(R[0]), s.c_str()); ret(u32(s.size())); return; }
    if (name == "puts") { std::puts(cstr(R[0])); ret(0); return; }
    if (name == "putchar") { std::putchar(int(R[0])); ret(R[0]); return; }
    if (name == "setjmp") { ret(0); return; }
    if (name == "exit") { g_exited = true; g_exit_code = int(R[0]); jit.HaltExecution(); return; }
    if (name == "__cxa_atexit" || name == "__aeabi_atexit" || name == "__cxa_finalize") { ret(0); return; }
    if (name == "pthread_mutex_init" || name == "pthread_mutex_destroy" || name == "pthread_mutex_lock" || name == "pthread_mutex_unlock") { ret(0); return; }
    if (name == "clock") { ret(u32(double(std::clock()) * (1000000.0 / CLOCKS_PER_SEC))); return; }  // bionic CLOCKS_PER_SEC
    // The unwinder entries are only reached when the engine throws a C++ exception.
    if (name == "__stack_chk_fail" || name == "__cxa_pure_virtual" || name == "__aeabi_unwind_cpp_pr0" || name == "__aeabi_unwind_cpp_pr1" ||
        name == "abort" || name == "raise" || name == "__cxa_begin_cleanup" || name == "__cxa_call_unexpected" ||
        name == "__cxa_type_match" || name == "__gnu_Unwind_Find_exidx") {
        std::fprintf(stderr, "[fatal shim] %s called\n", name.c_str()); g_exited = true; jit.HaltExecution(); return;
    }
    // ---- file I/O: emulated FILE* is a cookie into g_files; __sF range -> host std streams
    if (name == "fopen") {
        FILE* fp = nullptr;
#ifdef __ANDROID__
        fp = voicefs_open(cstr(R[0]), cstr(R[1]));
#endif
        if (!fp) fp = std::fopen(cstr(R[0]), cstr(R[1]));
        if (!fp) { ret(0); return; }
        g_files.push_back(fp); ret(FILE_COOKIE + u32(g_files.size() - 1)); return;
    }
    if (name == "fclose") { FILE* fp = file_of(R[0]); if (fp && R[0] >= FILE_COOKIE) { std::fclose(fp); g_files[R[0] - FILE_COOKIE] = nullptr; } ret(0); return; }
    if (name == "fread") { FILE* fp = file_of(R[3]); ret(fp && in_arena(R[0], R[1] * R[2]) ? u32(std::fread(host(R[0]), R[1], R[2], fp)) : 0); return; }
    if (name == "fwrite") { FILE* fp = file_of(R[3]); ret(fp ? u32(std::fwrite(host(R[0]), R[1], R[2], fp)) : 0); return; }
    if (name == "fseek") { FILE* fp = file_of(R[0]); ret(fp ? u32(std::fseek(fp, long(int(R[1])), int(R[2]))) : u32(-1)); return; }
    if (name == "ftell") { FILE* fp = file_of(R[0]); ret(fp ? u32(std::ftell(fp)) : u32(-1)); return; }
    if (name == "fgets") { FILE* fp = file_of(R[2]); ret(fp && std::fgets(cstr(R[0]), int(R[1]), fp) ? R[0] : 0); return; }
    if (name == "getc" || name == "__srget") { FILE* fp = file_of(R[0]); ret(fp ? u32(std::getc(fp)) : u32(-1)); return; }
    if (name == "fputs") { FILE* fp = file_of(R[1]); std::fputs(cstr(R[0]), fp ? fp : stderr); ret(0); return; }
    if (name == "fputc") { FILE* fp = file_of(R[1]); std::fputc(int(R[0]), fp ? fp : stderr); ret(R[0]); return; }
    if (name == "fprintf") { FILE* fp = file_of(R[0]); VaArgs va{&jit, R[13], 2}; std::string s; do_format(s, cstr(R[1]), va); std::fputs(s.c_str(), fp ? fp : stderr); ret(u32(s.size())); return; }
    if (name == "sscanf") { ret(do_sscanf(jit, cstr(R[0]), cstr(R[1]), 2)); return; }
    if (name == "fscanf") { std::fprintf(stderr, "[fscanf stub]\n"); ret(u32(-1)); return; }

    std::fprintf(stderr, "[unimpl shim] %s (r0=%08x r1=%08x r2=%08x)\n", name.c_str(), R[0], R[1], R[2]);
    ret(0);
}

double va_d(Dynarmic::A32::Jit& jit) {  // first double arg in r0:r1
    union { u64 u; double d; } c; c.u = jit.Regs()[0] | (u64(jit.Regs()[1]) << 32); return c.d;
}

// ---- JNI --------------------------------------------------------------------
struct Native { std::string name, sig; u32 fn; };
std::vector<Native> g_natives;
int g_next_handle = 1;

constexpr u32 JSTR = 0x50000000;   // + idx -> g_jstrings
constexpr u32 JBARR = 0x60000000;  // + idx -> g_byte_arrays
constexpr u32 JOARR = 0x70000000;  // + idx -> g_obj_arrays
std::vector<std::string> g_jstrings;
std::vector<std::pair<u32, u32>> g_byte_arrays;  // (emulated buf, length)
std::vector<std::vector<u32>> g_obj_arrays;      // element handles

u32 new_jstring(const std::string& s) { g_jstrings.push_back(s); return JSTR + u32(g_jstrings.size() - 1); }
u32 new_byte_array(u32 len) { u32 buf = heap_alloc(len); std::memset(host(buf), 0, len); g_byte_arrays.push_back({buf, len}); return JBARR + u32(g_byte_arrays.size() - 1); }
u32 new_obj_array(u32 n) { g_obj_arrays.emplace_back(n, 0u); return JOARR + u32(g_obj_arrays.size() - 1); }

void jni_env(Dynarmic::A32::Jit& jit, u32 slot) {
    auto& R = jit.Regs();
    switch (slot) {
        case 4: R[0] = 0x00010004; return;                        // GetVersion -> 1.4
        case 6: R[0] = u32(g_next_handle++); return;              // FindClass -> fake jclass
        case 167: R[0] = new_jstring(cstr(R[1])); return;         // NewStringUTF
        case 168: R[0] = R[1] >= JSTR ? u32(g_jstrings[R[1] - JSTR].size()) : 0; return;  // GetStringUTFLength
        case 169: {                                               // GetStringUTFChars(env,jstr,isCopy)
            const std::string& s = g_jstrings.at(R[1] - JSTR);
            u32 buf = heap_alloc(u32(s.size()) + 1);
            std::memcpy(host(buf), s.data(), s.size()); *host(buf + u32(s.size())) = 0;
            if (R[2]) w32(R[2], 1);
            R[0] = buf; return;
        }
        case 170: heap_free(R[2]); return;                        // ReleaseStringUTFChars(env,jstr,chars)
        case 171:                                                 // GetArrayLength
            R[0] = R[1] >= JOARR ? u32(g_obj_arrays[R[1] - JOARR].size())
                 : R[1] >= JBARR ? g_byte_arrays[R[1] - JBARR].second : 0; return;
        case 173: R[0] = g_obj_arrays.at(R[1] - JOARR).at(R[2]); return;        // GetObjectArrayElement
        case 174: g_obj_arrays.at(R[1] - JOARR).at(R[2]) = R[3]; return;        // SetObjectArrayElement
        case 176: R[0] = new_byte_array(R[1]); return;            // NewByteArray
        case 184: { static int c = 0; if (c++ < 2) std::fprintf(stderr, "[GetByteArrayElements arr=%08x]\n", R[1]); R[0] = g_byte_arrays.at(R[1] - JBARR).first; if (R[2]) w32(R[2], 0); return; }  // GetByteArrayElements
        case 192: return;                                         // ReleaseByteArrayElements (buffer is shared)
        case 196: {                                               // GetByteArrayRegion(env,arr,start,len,buf@sp)
            u32 b = g_byte_arrays.at(R[1] - JBARR).first, buf = r32(R[13]);
            if (in_arena(buf, R[3])) std::memcpy(host(buf), host(b + R[2]), R[3]); return;
        }
        case 208: {                                               // SetByteArrayRegion(env,arr,start,len,buf@sp)
            u32 b = g_byte_arrays.at(R[1] - JBARR).first, buf = r32(R[13]);
            if (in_arena(buf, R[3])) std::memcpy(host(b + R[2]), host(buf), R[3]); return;
        }
        case 215: {                                               // RegisterNatives(env,clazz,methods,n)
            u32 methods = R[2], n = R[3];
            for (u32 i = 0; i < n; i++) {
                u32 m = methods + i * 12;
                g_natives.push_back({cstr(r32(m)), cstr(r32(m + 4)), r32(m + 8)});
            }
            R[0] = 0; return;
        }
        default:
            static std::map<u32, int> seen;
            if (seen[slot]++ < 2) std::fprintf(stderr, "[jni env slot %u] stub (r1=%08x r2=%08x r3=%08x)\n", slot, R[1], R[2], R[3]);
            R[0] = 0; return;
    }
}

void jni_vm(Dynarmic::A32::Jit& jit, u32 slot) {
    auto& R = jit.Regs();
    switch (slot) {
        case 6:                                                   // GetEnv(vm, &env, ver)
        case 4:                                                   // AttachCurrentThread(vm, &env, args)
            w32(R[1], ENV_CELL); R[0] = 0; return;
        default:
            std::fprintf(stderr, "[jni vm slot %u] stub\n", slot);
            R[0] = 0; return;
    }
}

// ---- dynarmic callbacks -----------------------------------------------------
struct Env final : Dynarmic::A32::UserCallbacks {
    Dynarmic::A32::Jit* jit = nullptr;
    u64 ticks = 0;

    u8 MemoryRead8(u32 a) override { return in_arena(a, 1) ? *host(a) : 0; }
    u16 MemoryRead16(u32 a) override { u16 v = 0; if (in_arena(a, 2)) std::memcpy(&v, host(a), 2); return v; }
    u32 MemoryRead32(u32 a) override { return r32(a); }
    u64 MemoryRead64(u32 a) override { u64 v = 0; if (in_arena(a, 8)) std::memcpy(&v, host(a), 8); return v; }
    void MemoryWrite8(u32 a, u8 v) override { if (in_arena(a, 1)) *host(a) = v; }
    void MemoryWrite16(u32 a, u16 v) override { if (in_arena(a, 2)) std::memcpy(host(a), &v, 2); }
    void MemoryWrite32(u32 a, u32 v) override { w32(a, v); }
    void MemoryWrite64(u32 a, u64 v) override { if (in_arena(a, 8)) std::memcpy(host(a), &v, 8); }

    void InterpreterFallback(u32 pc, size_t) override { std::fprintf(stderr, "[fallback] pc=%08x\n", pc); }
    void ExceptionRaised(u32 pc, Dynarmic::A32::Exception) override { std::fprintf(stderr, "[exception] pc=%08x\n", pc); g_exited = true; jit->HaltExecution(); }
    void AddTicks(u64 t) override { ticks += t; }
    u64 GetTicksRemaining() override { return 0x10000000; }

    void CallSVC(u32 swi) override {
        if (swi == SVC_RETURN) { jit->HaltExecution(); return; }
        if (swi & SVC_VM) { jni_vm(*jit, swi & 0xFFFF); return; }
        if (swi & SVC_ENV) { jni_env(*jit, swi & 0xFFFF); return; }
        if (swi < g_import_names.size()) shim(*jit, g_import_names[swi]);
        else std::fprintf(stderr, "[svc %06x] unknown\n", swi);
    }
};

Env g_env;
Dynarmic::A32::Jit* g_jit = nullptr;

// run an ARM function with AAPCS args (first 4 in r0-r3, rest on the stack); returns r0
u32 call_argv(u32 addr, std::vector<u32> args) {
    auto& R = g_jit->Regs();
    u32 sp = STACK_TOP;
    if (args.size() > 4) {
        u32 extra = u32(args.size() - 4);
        sp -= ((extra + 1) & ~1u) * 4;  // keep 8-byte alignment
        for (u32 i = 0; i < extra; i++) w32(sp + i * 4, args[4 + i]);
    }
    for (int i = 0; i < 4; i++) R[i] = i < int(args.size()) ? args[i] : 0;
    R[13] = sp;
    R[14] = RETURN_TRAP;
    u32 cpsr = g_jit->Cpsr();
    if (addr & 1) cpsr |= (1u << 5); else cpsr &= ~(1u << 5);
    g_jit->SetCpsr(cpsr);
    R[15] = addr & ~1u;
    g_jit->Run();
    return R[0];
}
u32 call_arm(u32 addr, u32 a0 = 0, u32 a1 = 0, u32 a2 = 0, u32 a3 = 0) {
    return call_argv(addr, {a0, a1, a2, a3});
}

// ---- ELF loader -------------------------------------------------------------
#pragma pack(push, 1)
struct Ehdr { u8 ident[16]; u16 type, machine; u32 version, entry, phoff, shoff, flags; u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx; };
struct Phdr { u32 type, offset, vaddr, paddr, filesz, memsz, flags, align; };
struct Sym { u32 name; u32 value, size; u8 info, other; u16 shndx; };
#pragma pack(pop)

bool load_elf(const std::vector<u8>& f) {
    auto* eh = reinterpret_cast<const Ehdr*>(f.data());
    if (std::memcmp(eh->ident, "\x7f""ELF", 4) != 0 || eh->machine != 40) { std::fprintf(stderr, "not ARM ELF\n"); return false; }
    const u32 base = IMAGE_BASE;
    const Phdr* ph = reinterpret_cast<const Phdr*>(f.data() + eh->phoff);
    u32 dynoff = 0, dyncount = 0;
    for (int i = 0; i < eh->phnum; i++) {
        if (ph[i].type == 1) {  // PT_LOAD
            std::memcpy(host(base + ph[i].vaddr), f.data() + ph[i].offset, ph[i].filesz);
        } else if (ph[i].type == 2) {  // PT_DYNAMIC
            dynoff = ph[i].vaddr; dyncount = ph[i].memsz / 8;
        }
    }
    // parse .dynamic (already in emulated memory)
    u32 symtab = 0, strtab = 0, rel = 0, relsz = 0, jmprel = 0, pltrelsz = 0, initarr = 0, initarrsz = 0;
    for (u32 i = 0; i < dyncount; i++) {
        u32 tag = r32(base + dynoff + i * 8), val = r32(base + dynoff + i * 8 + 4);
        switch (tag) {
            case 6: symtab = val; break; case 5: strtab = val; break;
            case 17: rel = val; break; case 18: relsz = val; break;
            case 23: jmprel = val; break; case 2: pltrelsz = val; break;
            case 25: initarr = val; break; case 27: initarrsz = val; break;
        }
    }
    auto name_of = [&](u32 symidx) -> std::string {
        Sym s; std::memcpy(&s, host(base + symtab + symidx * 16), 16);
        return std::string(cstr(base + strtab + s.name));
    };
    auto is_func = [&](u32 symidx) {
        Sym s; std::memcpy(&s, host(base + symtab + symidx * 16), 16);
        return (s.info & 0xf) == 2;  // STT_FUNC
    };
    auto resolve = [&](u32 symidx) -> u32 {
        std::string nm = name_of(symidx);
        if (is_func(symidx) || nm.rfind("__aeabi", 0) == 0 || nm.rfind("_Zn", 0) == 0 || nm.rfind("_Zd", 0) == 0)
            return import_thunk(nm);
        // data symbol
        auto it = g_data_syms.find(nm);
        if (it != g_data_syms.end()) return it->second;
        std::fprintf(stderr, "[data sym] %s unmapped -> extern cell\n", nm.c_str());
        return EXTERN_DATA;  // fallback
    };
    auto apply = [&](u32 off, u32 sz) {
        for (u32 o = 0; o < sz; o += 8) {
            u32 r_offset = r32(base + off + o), r_info = r32(base + off + o + 4);
            u32 type = r_info & 0xff, sym = r_info >> 8;
            u32 loc = base + r_offset;
            switch (type) {
                case 23: w32(loc, r32(loc) + base); break;               // R_ARM_RELATIVE
                case 2: w32(loc, resolve(sym) + r32(loc)); break;        // R_ARM_ABS32
                case 21: case 22: w32(loc, resolve(sym)); break;         // GLOB_DAT / JUMP_SLOT
                default: std::fprintf(stderr, "[reloc] unknown type %u\n", type); break;
            }
        }
    };
    apply(rel, relsz);
    apply(jmprel, pltrelsz);
    // run init array
    for (u32 i = 0; i < initarrsz / 4; i++) {
        u32 fn = r32(base + initarr + i * 4);
        if (fn && fn != 0xffffffff) call_arm(fn);
    }
    return true;
}

void setup_tables() {
    // extern data symbols
    g_data_syms["__stack_chk_guard"] = EXTERN_DATA;        w32(EXTERN_DATA, 0xdeadc0de);
    g_data_syms["__isthreaded"] = EXTERN_DATA + 8;         w32(EXTERN_DATA + 8, 0);
    g_data_syms["__sF"] = EXTERN_DATA + 16;                // 3 dummy FILE slots (unused on load path)
    // BSD ctype table: pointer indexes (base+1)[c]
    u32 ctype = EXTERN_DATA + 0x200;
    g_data_syms["_ctype_"] = ctype;
    for (int c = -1; c < 256; c++) {
        u8 m = 0; int uc = c & 0xff;
        if (c >= 0) {
            if (std::isupper(uc)) m |= 0x01; if (std::islower(uc)) m |= 0x02;
            if (std::isdigit(uc)) m |= 0x04; if (std::isspace(uc)) m |= 0x08;
            if (std::ispunct(uc)) m |= 0x10; if (std::iscntrl(uc)) m |= 0x20;
            if (std::isxdigit(uc)) m |= 0x40; if (uc == ' ') m |= 0x80;
        }
        *host(ctype + 1 + c) = m;
    }
    u32 tol = EXTERN_DATA + 0x400, tou = EXTERN_DATA + 0x800;
    g_data_syms["_tolower_tab_"] = tol; g_data_syms["_toupper_tab_"] = tou;
    for (int c = -1; c < 256; c++) {
        w32(tol + (1 + c) * 4, c < 0 ? u32(c) : u32(std::tolower(c & 0xff)));
        w32(tou + (1 + c) * 4, c < 0 ? u32(c) : u32(std::toupper(c & 0xff)));
    }
    // JNIEnv table: 240 slots -> per-slot thunks
    for (u32 i = 0; i < 240; i++) {
        u32 t = JNIENV_THUNKS + i * 8;
        w32(t, 0xEF000000 | (SVC_ENV | i)); w32(t + 4, 0xE12FFF1E);
        w32(JNIENV_TABLE + i * 4, t);
    }
    w32(ENV_CELL, JNIENV_TABLE);
    // JavaVM table: 8 slots
    for (u32 i = 0; i < 8; i++) {
        u32 t = JAVAVM_THUNKS + i * 8;
        w32(t, 0xEF000000 | (SVC_VM | i)); w32(t + 4, 0xE12FFF1E);
        w32(JAVAVM_TABLE + i * 4, t);
    }
    w32(VM_CELL, JAVAVM_TABLE);
    w32(RETURN_TRAP, 0xEF000000 | SVC_RETURN);  // svc #return
}

// reusable driver state
u32 g_jni_onload = 0, g_thiz = 0, g_pcm_ba = 0;
Dynarmic::A32::Jit* g_owned_jit = nullptr;

u32 find_native(const char* nm) { for (auto& n : g_natives) if (n.name == nm) return n.fn; return 0; }

}  // namespace

// Load a 32-bit engine image and run JNI_OnLoad + initialize. Fully resets state, so it can be
// called again for a different engine (only one engine is active at a time).
bool emu_init(const u8* data, size_t sz) {
    g_import_names.clear(); g_import_index.clear(); g_data_syms.clear();
    g_natives.clear(); g_jstrings.clear(); g_byte_arrays.clear(); g_obj_arrays.clear(); g_files.clear();
    g_exited = false; g_exit_code = 0; g_next_handle = 1; g_pcm_ba = 0;
    g_mem.assign(ARENA_SIZE, 0);
    u32 image_end = IMAGE_BASE + u32(sz) + 0x3000000;  // + bss headroom
    g_heap = (image_end + 0xfff) & ~0xfffu; g_heap_end = STACK_TOP - 0x800000;
    w32(g_heap, g_heap_end - g_heap);

    delete g_owned_jit;  // fresh Jit so no stale translation cache from a previous engine
    static Dynarmic::A32::UserConfig cfg;
    cfg.callbacks = &g_env;
    g_owned_jit = new Dynarmic::A32::Jit(cfg);
    g_jit = g_owned_jit; g_env.jit = g_owned_jit;

    setup_tables();
    std::vector<u8> file(data, data + sz);
    if (!load_elf(file)) return false;

    const Ehdr* eh = reinterpret_cast<const Ehdr*>(data);
    const Phdr* ph = reinterpret_cast<const Phdr*>(data + eh->phoff);
    u32 dynoff = 0, dyncount = 0;
    for (int i = 0; i < eh->phnum; i++) if (ph[i].type == 2) { dynoff = ph[i].vaddr; dyncount = ph[i].memsz / 8; }
    u32 symtab = 0, strtab = 0, hash = 0;
    for (u32 i = 0; i < dyncount; i++) { u32 tag = r32(IMAGE_BASE + dynoff + i * 8), val = r32(IMAGE_BASE + dynoff + i * 8 + 4); if (tag == 6) symtab = val; else if (tag == 5) strtab = val; else if (tag == 4) hash = val; }
    g_jni_onload = 0;
    u32 nchain = r32(IMAGE_BASE + hash + 4);
    for (u32 i = 0; i < nchain; i++) {
        Sym s; std::memcpy(&s, host(IMAGE_BASE + symtab + i * 16), 16);
        if (s.value && std::strcmp(cstr(IMAGE_BASE + strtab + s.name), "JNI_OnLoad") == 0) { g_jni_onload = IMAGE_BASE + s.value; break; }
    }
    if (!g_jni_onload) return false;
    call_arm(g_jni_onload, VM_CELL, 0);
    g_thiz = u32(g_next_handle++);
    call_argv(find_native("initialize"), {ENV_CELL, g_thiz});
    return !g_exited && find_native("setLanguage") != 0;
}

int emu_set_language(const char* dir, const char* lang, const char* country, const char* type, int num, int dict) {
    return int(call_argv(find_native("setLanguage"), {ENV_CELL, g_thiz, new_jstring(dir), new_jstring(lang),
                                                       new_jstring(country), new_jstring(type), u32(num), u32(dict)}));
}
void emu_supervisor_input(const char* text, const char* lang, const char* country) {
    call_argv(find_native("supervisor_InputText"), {ENV_CELL, g_thiz, new_jstring(text), new_jstring(lang), new_jstring(country)});
}
bool emu_next_utterance(std::string& out) {
    u32 arr = new_obj_array(3);
    // r3 = 0: the newer engines take an "other engine" flag here; the Note 2 one ignores it
    if (!call_argv(find_native("supervisor_GetUtterance"), {ENV_CELL, g_thiz, arr, 0})) return false;
    u32 e0 = g_obj_arrays[arr - JOARR][0];
    out = (e0 >= JSTR) ? g_jstrings[e0 - JSTR] : std::string();
    return true;
}
void emu_speak(const char* text) { call_argv(find_native("speak"), {ENV_CELL, g_thiz, new_jstring(text)}); }
void emu_set_parameters(int speed, int pitch, float spectrum, int effect) {
    u32 s; std::memcpy(&s, &spectrum, 4);
    call_argv(find_native("setParameters"), {ENV_CELL, g_thiz, u32(speed), u32(pitch), s, u32(effect)});
}
int emu_get_data(u8* out, int cap) {
    if (!g_pcm_ba) g_pcm_ba = new_byte_array(160);
    u32 code = call_argv(find_native("getSynthesizedData"), {ENV_CELL, g_thiz, g_pcm_ba});
    int n = cap < 160 ? cap : 160;
    if (out) std::memcpy(out, host(g_byte_arrays[g_pcm_ba - JBARR].first), n);
    return int(code);
}
int emu_sampling_rate() { u32 f = find_native("getSamplingRate"); return f ? int(call_argv(f, {ENV_CELL, g_thiz})) : 16000; }
bool emu_exited() { return g_exited; }

#ifdef EMU_MAIN
int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: emu <engine.so> [dataDir] [text] [out.wav]\n"); return 2; }
    FILE* f = std::fopen(argv[1], "rb");
    if (!f) { std::perror("open"); return 2; }
    std::fseek(f, 0, SEEK_END); long sz = std::ftell(f); std::fseek(f, 0, SEEK_SET);
    std::vector<u8> file(sz); if (std::fread(file.data(), 1, sz, f) != size_t(sz)) { std::fclose(f); return 2; }
    std::fclose(f);

    if (!emu_init(file.data(), file.size())) { std::fprintf(stderr, "emu_init failed (exited=%d)\n", emu_exited()); return 1; }
    std::fprintf(stderr, "loaded, %zu natives\n", g_natives.size());
    for (auto& n : g_natives) std::fprintf(stderr, "  %s%s\n", n.name.c_str(), n.sig.c_str());

    // emu <engine.so> [dataDir] [text] [out.wav] [lang country type number] [spectrum effect]
    const char* data_dir = argc >= 3 ? argv[2] : "C:/SamsungTTS/app/src/main/assets/voices/note2/ko_KR_f01";
    const char* text_utf = argc >= 4 && argv[3][0] ? argv[3] : "\xec\x95\x88\xeb\x85\x95\xed\x95\x98\xec\x84\xb8\xec\x9a\x94";
    const char* lang = argc >= 9 ? argv[5] : "kor";
    const char* country = argc >= 9 ? argv[6] : "KOR";
    const char* type = argc >= 9 ? argv[7] : "f";
    int number = argc >= 9 ? std::atoi(argv[8]) : 1;
    float spectrum = argc >= 11 ? float(std::atof(argv[9])) : 0.42f;
    int effect = argc >= 11 ? std::atoi(argv[10]) : -1;
    std::fprintf(stderr, "setLanguage = %d\n", emu_set_language(data_dir, lang, country, type, number, 0));

    emu_supervisor_input(text_utf, lang, country);
    std::string utter, u;
    while (emu_next_utterance(u)) utter += u;
    emu_speak(utter.empty() ? text_utf : utter.c_str());
    emu_set_parameters(100, 100, spectrum, effect);

    std::vector<u8> pcm(160);
    std::vector<u8> all;
    for (int i = 0; i < 100000 && !emu_exited(); i++) {
        int code = emu_get_data(pcm.data(), 160);
        if (code == 0) all.insert(all.end(), pcm.begin(), pcm.end());
        else if (code == 2 || code == 3) continue;
        else break;
    }
    std::fprintf(stderr, "PCM bytes = %zu (%.2f s), exited=%d\n", all.size(), all.size() / 2.0 / emu_sampling_rate(), emu_exited());

    const char* out = argc >= 5 ? argv[4] : "emu_out.wav";
    FILE* w = std::fopen(out, "wb");
    if (w) {
        u32 dlen = u32(all.size()), rate = u32(emu_sampling_rate()); u16 one = 1, bps = 16, blk = 2; u32 br = rate * 2, rlen = 36 + dlen, fmtlen = 16;
        std::fwrite("RIFF", 1, 4, w); std::fwrite(&rlen, 4, 1, w); std::fwrite("WAVEfmt ", 1, 8, w);
        std::fwrite(&fmtlen, 4, 1, w); std::fwrite(&one, 2, 1, w); std::fwrite(&one, 2, 1, w);
        std::fwrite(&rate, 4, 1, w); std::fwrite(&br, 4, 1, w); std::fwrite(&blk, 2, 1, w); std::fwrite(&bps, 2, 1, w);
        std::fwrite("data", 1, 4, w); std::fwrite(&dlen, 4, 1, w); std::fwrite(all.data(), 1, dlen, w);
        std::fclose(w); std::fprintf(stderr, "wrote %s\n", out);
    }
    return 0;
}
#endif

#ifdef __ANDROID__
#include <jni.h>
namespace {
std::string jstr(JNIEnv* e, jstring s) { if (!s) return {}; const char* c = e->GetStringUTFChars(s, nullptr); std::string r = c ? c : ""; e->ReleaseStringUTFChars(s, c); return r; }
}
extern "C" {
JNIEXPORT jboolean JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nCreate(JNIEnv* e, jobject, jbyteArray img) {
    jsize n = e->GetArrayLength(img);
    std::vector<u8> b(static_cast<size_t>(n));
    e->GetByteArrayRegion(img, 0, n, reinterpret_cast<jbyte*>(b.data()));
    return emu_init(b.data(), b.size()) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nSetLanguage(JNIEnv* e, jobject, jstring dir, jstring lang, jstring country, jstring type, jint num, jint dict) {
    return emu_set_language(jstr(e, dir).c_str(), jstr(e, lang).c_str(), jstr(e, country).c_str(), jstr(e, type).c_str(), num, dict);
}
JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nSupervisorInput(JNIEnv* e, jobject, jstring text, jstring lang, jstring country) {
    emu_supervisor_input(jstr(e, text).c_str(), jstr(e, lang).c_str(), jstr(e, country).c_str());
}
JNIEXPORT jboolean JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nNextUtterance(JNIEnv* e, jobject, jobjectArray out) {
    std::string u;
    if (!emu_next_utterance(u)) return JNI_FALSE;
    e->SetObjectArrayElement(out, 0, e->NewStringUTF(u.c_str()));
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nSpeak(JNIEnv* e, jobject, jstring text) { emu_speak(jstr(e, text).c_str()); }
JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nSetParameters(JNIEnv*, jobject, jint speed, jint pitch, jfloat spectrum, jint effect) { emu_set_parameters(speed, pitch, spectrum, effect); }
JNIEXPORT jint JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nGetSynthesizedData(JNIEnv* e, jobject, jbyteArray buf) {
    jsize cap = e->GetArrayLength(buf);
    int want = cap < 160 ? cap : 160;
    std::vector<u8> tmp(static_cast<size_t>(want));
    int code = emu_get_data(tmp.data(), want);
    e->SetByteArrayRegion(buf, 0, want, reinterpret_cast<jbyte*>(tmp.data()));
    return code;
}
JNIEXPORT jint JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nSamplingRate(JNIEnv*, jobject) { return emu_sampling_rate(); }
JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nMount(JNIEnv* e, jobject, jstring name, jint fd, jlong offset, jlong length) {
    voicefs_mount(jstr(e, name).c_str(), fd, offset, length);
}
JNIEXPORT void JNICALL Java_com_hwangsihu_smt_engine_EmulatedEngine_nUnmountAll(JNIEnv*, jobject) { voicefs_clear(); }
}
#endif
