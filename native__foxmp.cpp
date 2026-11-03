// libfoxmp.so - safe bridge to libgtav.so internal natives (arm64).
// Every function is: (1) located by fixed offset from libgtav.so's load base, (2) verified against the
// prologue bytes captured from the reference build, (3) disabled unless listed in native.cfg, (4) called
// inside a SIGSEGV/SIGBUS guard so a wrong signature logs an error instead of killing the game.
#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <setjmp.h>
#include <signal.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <mutex>
#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FoxMP", __VA_ARGS__)
#else
#define LOG(...) (printf(__VA_ARGS__), printf("\n"))
#endif

struct Fn { const char* name; uintptr_t off; uint8_t sig[8]; int bit; bool ok; bool enabled; };
// offsets/prologues come from libgtav.so sha256 b7f73614fa4299ff... (reference build)
static Fn T[] = {
 {"TASK_ENTER_VEHICLE",            0x1b2ff58, {0xff,0xc3,0x02,0xd1,0xe8,0x2b,0x00,0xfd},   1},
 {"TASK_LEAVE_VEHICLE",            0x1b30158, {0xff,0x43,0x01,0xd1,0xfd,0x7b,0x02,0xa9},   2},
 {"TASK_WARP_PED_INTO_VEHICLE",    0x1b3cfb0, {0xff,0x43,0x01,0xd1,0xfd,0x7b,0x01,0xa9},   4},
 {"TASK_GO_STRAIGHT_TO_COORD",     0x1b30830, {0xff,0x03,0x02,0xd1,0xec,0x13,0x00,0xfd},   8},
 {"TASK_VEHICLE_DRIVE_TO_COORD",   0x1b302a4, {0xff,0x03,0x02,0xd1,0xea,0x1b,0x00,0xfd},  16},
 {"TASK_PLAY_ANIM",                0x1b33740, {0xff,0x03,0x05,0xd1,0xea,0x63,0x00,0xfd},  32},
 {"TASK_AIM_GUN_AT_COORD",         0x1b368f4, {0xff,0x83,0x02,0xd1,0xfd,0x7b,0x05,0xa9},  64},
 {"CLEAR_PED_TASKS",               0x214b850, {0xff,0x03,0x03,0xd1,0xfd,0x7b,0x0a,0xa9}, 128},
 {"TASK_GO_TO_COORD_ANY_MEANS",    0x1b33260, {0xff,0xc3,0x01,0xd1,0xe9,0x23,0x02,0x6d}, 256},
};
static uintptr_t g_base = 0; static std::string g_log; static std::mutex g_mu; static int g_mask = 0; static bool g_init = false;
static void note(const char* fmt, ...) { char b[256]; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b, fmt, a); va_end(a); LOG("%s", b); std::lock_guard<std::mutex> l(g_mu); g_log += b; g_log += "\n"; if (g_log.size() > 8000) g_log.erase(0, 4000); }

static int phdr_cb(struct dl_phdr_info* i, size_t, void*) {
    const char* n = i->dlpi_name ? strrchr(i->dlpi_name, '/') : nullptr; n = n ? n + 1 : (i->dlpi_name ? i->dlpi_name : "");
    if (!strcmp(n, "libgtav.so")) { g_base = i->dlpi_addr; return 1; } return 0;
}
static std::string cfgPath() { return "/storage/emulated/0/Games/GTAV/foxmp/native.cfg"; }
static bool listed(const char* name) {  // native.cfg: one function name per line to enable, '#' comments
    FILE* f = fopen(cfgPath().c_str(), "r"); if (!f) return false; char ln[160]; bool ok = false;
    while (fgets(ln, sizeof ln, f)) { if (ln[0] == '#') continue; size_t n = strcspn(ln, "\r\n"); ln[n] = 0; if (!strcmp(ln, name)) ok = true; } fclose(f); return ok;
}
static void init() {
    if (g_init) return; g_init = true; dl_iterate_phdr(phdr_cb, nullptr);
    if (!g_base) { note("libgtav.so not found - natives off"); return; }
    for (auto& f : T) {
        f.ok = memcmp((void*)(g_base + f.off), f.sig, 8) == 0; f.enabled = f.ok && listed(f.name);
        if (f.enabled) g_mask |= f.bit; note("%s @%lx prologue %s %s", f.name, (unsigned long)(g_base + f.off), f.ok ? "OK" : "MISMATCH (other build?)", f.enabled ? "ENABLED" : "off");
    }
}
static thread_local sigjmp_buf* t_jmp = nullptr;
static void crash(int) { if (t_jmp) siglongjmp(*t_jmp, 1); }
typedef long (*Gen)(long, long, long, long, long, long, long, long, float, float, float, float, float, float, float, float);

static jint callFn(const char* name, const long* I, const float* F) {
    init(); for (auto& f : T) if (!strcmp(f.name, name)) {
        if (!f.enabled) return -2;
        struct sigaction sa{}, oa{}, ob{}; sa.sa_handler = crash; sigemptyset(&sa.sa_mask); sigaction(SIGSEGV, &sa, &oa); sigaction(SIGBUS, &sa, &ob);
        sigjmp_buf jb; t_jmp = &jb; long r = -1;
        if (sigsetjmp(jb, 1) == 0) { r = ((Gen)(g_base + f.off))(I[0], I[1], I[2], I[3], I[4], I[5], I[6], I[7], F[0], F[1], F[2], F[3], F[4], F[5], F[6], F[7]); }
        else { note("%s CRASHED (wrong args) - disabled", name); f.enabled = false; g_mask &= ~f.bit; r = -3; }
        t_jmp = nullptr; sigaction(SIGSEGV, &oa, nullptr); sigaction(SIGBUS, &ob, nullptr); return (jint)r;
    } return -4;
}
extern "C" {
JNIEXPORT jint JNICALL Java_com_gtavsource_android_FoxNative_features(JNIEnv*, jclass) { init(); return g_mask; }
JNIEXPORT jstring JNICALL Java_com_gtavsource_android_FoxNative_lastLog(JNIEnv* e, jclass) { init(); std::lock_guard<std::mutex> l(g_mu); return e->NewStringUTF(g_log.c_str()); }
JNIEXPORT jint JNICALL Java_com_gtavsource_android_FoxNative_call(JNIEnv* e, jclass, jstring fn, jlongArray ia, jfloatArray fa) {
    long I[8] = {0}; float F[8] = {0}; jlong ti[8] = {0}; jfloat tf[8] = {0};
    if (ia) { jsize n = e->GetArrayLength(ia); if (n > 8) n = 8; e->GetLongArrayRegion(ia, 0, n, ti); }
    if (fa) { jsize n = e->GetArrayLength(fa); if (n > 8) n = 8; e->GetFloatArrayRegion(fa, 0, n, tf); }
    for (int i = 0; i < 8; i++) { I[i] = (long)ti[i]; F[i] = tf[i]; }
    const char* s = e->GetStringUTFChars(fn, nullptr); jint r = callFn(s, I, F); e->ReleaseStringUTFChars(fn, s); return r;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { init(); return JNI_VERSION_1_6; }
}
