package com.gtavsource.android;
/** COMPILE-ONLY stub of the game's real class (not packaged into the APK). */
public final class TrainerBridge {
    interface Endpoint {
        String snapshot();
        int submit(String cmd, String arg, int a, int b, float... f);
    }
    static final Endpoint NATIVE = null;
    static String error(int code) { return ""; }
}
