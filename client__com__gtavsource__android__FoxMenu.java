package com.gtavsource.android;

import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;

/** "Multiplayer" page of the game's own menu (launcher + pause menu). Built from native views, so the HTML launcher mirrors it automatically. */
final class FoxMenu {
    static final int PAGE = 10;
    private static GameMenuActivity cur; private static boolean hooked;

    static void build(GameMenuActivity a, LinearLayout c) {
        cur = a; final FoxMp mp = FoxMp.get(a);
        if (!hooked) { hooked = true; FoxModel.listeners.add(FoxMenu::refresh); }
        c.addView(MenuStyle.text(a, "Real-time multiplayer: friends der shathe ek world e khelo, voice chat shoho.", 12, 0xFFAAAAAA));
        MenuStyle.detailRow(c, "Status", FoxModel.status + (FoxModel.connected ? "  (" + FoxModel.me + ")" : ""));
        if (!FoxModel.connected) {
            MenuStyle.detailRow(c, "Server IP", FoxModel.ip);
            GtavTools.button(c, "Server IP bodlao", () -> a.textInput("Server IP", FoxModel.ip, v -> { FoxModel.ip = v.trim(); FoxModel.changed(); }));
            MenuStyle.detailRow(c, "Tomar naam", FoxModel.name);
            GtavTools.button(c, "Naam bodlao", () -> a.textInput("Tomar naam", FoxModel.name, v -> { FoxModel.name = v.trim(); FoxModel.changed(); }));
            GtavTools.button(c, "Connect", () -> mp.connect(FoxModel.ip, FoxModel.name));
            return;
        }
        boolean mine = FoxModel.world.equals(FoxModel.me);
        GtavTools.heading(c, "Session", mine ? "Tomar nijer world" : FoxModel.world + " er world");
        JSONArray ro = FoxModel.roster;
        for (int i = 0; i < ro.length(); i++) {
            JSONObject p = ro.optJSONObject(i); final String n = p.optString("name");
            MenuStyle.detailRow(c, n + (n.equals(FoxModel.me) ? " (tumi)" : ""), p.optBoolean("mission") ? "mission khelche" : "world e ache");
            if (mine && !n.equals(FoxModel.me)) GtavTools.button(c, "Kick " + n, () -> mp.kick(n));
        }
        GtavTools.heading(c, "Friends", "Request, join, invite");
        JSONArray fr = FoxModel.friends;
        for (int i = 0; i < fr.length(); i++) {
            JSONObject f = fr.optJSONObject(i); final String n = f.optString("name"); boolean on = f.optBoolean("online");
            MenuStyle.detailRow(c, n, on ? "online - " + f.optString("world") + " world" : "offline");
            if (on) { GtavTools.button(c, "Join " + n + " er world", () -> mp.tx("world.join", "host", n)); GtavTools.button(c, "Invite " + n, () -> mp.invite(n)); }
        }
        JSONArray pe = FoxModel.pending;
        for (int i = 0; i < pe.length(); i++) {
            final String n = pe.optString(i); MenuStyle.detailRow(c, n, "friend request pathiyeche");
            GtavTools.button(c, "Accept " + n, () -> mp.tx("friend.accept", "from", n)); GtavTools.button(c, "Reject " + n, () -> mp.tx("friend.reject", "from", n));
        }
        GtavTools.button(c, "Friend add koro", () -> a.textInput("Friend er naam", "", v -> mp.tx("friend.request", "to", v.trim())));
        GtavTools.heading(c, "World", "Host er save sync");
        GtavTools.button(c, "Amar save upload koro (host)", mp::host);
        if (!mine) GtavTools.button(c, "World theke ber hao (amar save ferot)", mp::leave);
        if (!FoxModel.progressPhase.isEmpty() && !(FoxModel.progressPhase.equals("done") && FoxModel.progressPct >= 100)) MenuStyle.detailRow(c, FoxModel.progressPhase, FoxModel.progressPct + "%");
        GtavTools.heading(c, "Voice & mission", "Same world e jara, tara shunbe");
        MenuStyle.toggle(c, "Microphone", "Voice chat", FoxModel.mic, b -> mp.setMic(b));
        MenuStyle.toggle(c, "Deafen", "Onno der voice bondho", FoxModel.deaf, b -> mp.setDeaf(b));
        MenuStyle.toggle(c, "Mission mode", "Alada khelo, onno der kache lukano", FoxModel.mission, b -> mp.setMission(b));
        GtavTools.heading(c, "Chat", "");
        String log = FoxModel.chatText(); if (!log.isEmpty()) c.addView(MenuStyle.text(a, log, 12, 0xFFDDDDDD));
        GtavTools.button(c, "Message pathao", () -> a.textInput("Message", "", v -> { if (!v.trim().isEmpty()) mp.chat(v.trim()); }));
        GtavTools.button(c, "Disconnect", mp::disconnect);
    }

    private static void refresh() {
        final GameMenuActivity a = cur;
        if (a == null || a.isFinishing() || GtavPages.curPage != PAGE) return;
        if (FoxModel.progressPhase.equals("download") || FoxModel.progressPhase.equals("upload")) { if (FoxModel.progressPct % 10 != 0) return; }
        a.showPage(PAGE);
    }
}
