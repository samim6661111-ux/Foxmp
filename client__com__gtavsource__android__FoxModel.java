package com.gtavsource.android;

import android.content.Context;
import android.os.*;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared UI state. Launcher page (FoxMenu) and in-game overlay (FoxUi) both render from here. */
final class FoxModel {
    static volatile boolean connected, mic, deaf, mission;
    static volatile String me = "", world = "", status = "offline", ip = "", name = "", progressPhase = "";
    static volatile int progressPct;
    static volatile JSONArray friends = new JSONArray(), pending = new JSONArray(), roster = new JSONArray();
    static final ArrayList<String> chat = new ArrayList<>();
    static volatile Context ctx;
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler H = new Handler(Looper.getMainLooper());

    static void changed() { H.post(() -> { for (Runnable r : listeners) try { r.run(); } catch (Throwable t) { } }); }
    static void toast(String m) { Context c = ctx; if (c != null) H.post(() -> Toast.makeText(c, m, Toast.LENGTH_LONG).show()); }
    static void progress(String phase, int pct) { progressPhase = phase; progressPct = pct; changed(); }
    static void addChat(String line) { synchronized (chat) { chat.add(line); while (chat.size() > 6) chat.remove(0); } changed(); }
    static String chatText() { synchronized (chat) { StringBuilder b = new StringBuilder(); for (String s : chat) b.append(s).append('\n'); return b.toString().trim(); } }
}
