package com.gtavsource.android;

/** Optional natives from libfoxmp.so. Safe to use when the lib is missing or a function is not enabled in native.cfg. */
final class FoxNative {
    static boolean loaded;
    static { try { System.loadLibrary("foxmp"); loaded = true; } catch (Throwable t) { loaded = false; } }
    static native int features();
    static native int call(String fn, long[] ints, float[] floats);
    static native String lastLog();

    static final int F_ENTER = 1, F_LEAVE = 2, F_WARP = 4, F_GOTO = 8, F_DRIVE = 16, F_ANIM = 32, F_AIM = 64, F_CLEAR = 128, F_ANY = 256;
    static boolean has(int bit) { try { return loaded && (features() & bit) != 0; } catch (Throwable t) { return false; } }
    static String log() { try { return loaded ? lastLog() : "libfoxmp not loaded"; } catch (Throwable t) { return "n/a"; } }
    /** generic safe call: ints go to x0..x7, floats to s0..s7 (AAPCS64) */
    static int safe(String fn, long[] i, float[] f) { try { return loaded ? call(fn, i, f) : -9; } catch (Throwable t) { return -9; } }
    // default argument templates (best guess, tune in tools/NATIVE_TUNING.md): ped handle first
    static int clearTasks(int ped) { return safe("CLEAR_PED_TASKS", new long[]{0, ped}, new float[8]); }
    static int goTo(int ped, float x, float y, float z, float speed) { return safe("TASK_GO_STRAIGHT_TO_COORD", new long[]{ped}, new float[]{x, y, z, speed}); }
    static int enterVehicle(int ped, int veh, int seat) { return safe("TASK_ENTER_VEHICLE", new long[]{ped, veh, seat}, new float[]{8f}); }
    static int leaveVehicle(int ped, int veh) { return safe("TASK_LEAVE_VEHICLE", new long[]{ped, veh}, new float[8]); }
    static int warpIntoVehicle(int ped, int veh, int seat) { return safe("TASK_WARP_PED_INTO_VEHICLE", new long[]{ped, veh, seat}, new float[8]); }
}
