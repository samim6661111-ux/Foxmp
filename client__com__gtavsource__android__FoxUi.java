package com.gtavsource.android;

import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.os.*;
import static com.gtavsource.android.FoxModel.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Floating FOX button + panel: login, friend requests (accept/reject), join/host world, voice, mission mode, progress bar. */
final class FoxUi {
    interface Actions {
        void connect(String ip, String name); void disconnect(); void tx(String t, Object... kv);
        void host(); void leave(); void setMic(boolean on); void setDeaf(boolean on); void setMission(boolean on);
    }
    final Activity a; final Actions act; final float d; private FrameLayout root; private LinearLayout panel; private TextView progressTxt; private ProgressBar bar;
    private final android.content.SharedPreferences sp;

    FoxUi(Activity a, Actions act, FoxSync sync) {
        this.a = a; this.act = act; d = a.getResources().getDisplayMetrics().density; sp = a.getSharedPreferences("foxmp", 0);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            root = new FrameLayout(a); root.setClickable(false);
            FoxRadar radar = new FoxRadar(a, sync);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); rp.setMargins((int) (8 * d), (int) (8 * d), 0, 0); root.addView(radar, rp);
            Button fab = btn("FOX", 0xFFFF8A00); FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams((int) (64 * d), (int) (40 * d), Gravity.TOP | Gravity.END); fp.setMargins(0, (int) (8 * d), (int) (8 * d), 0);
            root.addView(fab, fp);
            panel = new LinearLayout(a); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(0xEE12171E); panel.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            ScrollView sv = new ScrollView(a); sv.addView(panel); sv.setVisibility(View.GONE);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams((int) (320 * d), (int) (300 * d), Gravity.TOP | Gravity.END); pp.setMargins(0, (int) (52 * d), (int) (8 * d), 0); root.addView(sv, pp);
            fab.setOnClickListener(v -> { sv.setVisibility(sv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); render(); });
            LinearLayout pr = new LinearLayout(a); pr.setOrientation(LinearLayout.VERTICAL); pr.setBackgroundColor(0xDD000000); pr.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            progressTxt = new TextView(a); progressTxt.setTextColor(Color.WHITE); bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100); pr.addView(progressTxt); pr.addView(bar); pr.setVisibility(View.GONE); pr.setTag("progress");
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams((int) (320 * d), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); gp.setMargins(0, 0, 0, (int) (24 * d)); root.addView(pr, gp);
            a.addContentView(root, new ViewGroup.LayoutParams(-1, -1)); render(); FoxModel.listeners.add(() -> { render(); progressView(); });
        }, 2500);
    }
    private Button btn(String t, int color) { Button b = new Button(a); b.setText(t); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); b.setAllCaps(false); b.setTextSize(12); b.setPadding(4, 0, 4, 0); return b; }
    private TextView tv(String t, int color, int sp) { TextView v = new TextView(a); v.setText(t); v.setTextColor(color); v.setTextSize(sp); return v; }
    private EditText et(String hint, String val) { EditText e = new EditText(a); e.setHint(hint); e.setText(val); e.setTextColor(Color.WHITE); e.setHintTextColor(0x88FFFFFF); e.setSingleLine(); e.setInputType(InputType.TYPE_CLASS_TEXT); return e; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, (int) (36 * d)); }

    private void progressView() {
        if (root == null) return; View pr = root.findViewWithTag("progress"); if (pr == null) return; String phase = progressPhase; int pct = progressPct;
        pr.setVisibility(phase.isEmpty() || ("done".equals(phase) && pct >= 100) ? View.GONE : View.VISIBLE);
        progressTxt.setText(phase.equals("upload") ? "Host save upload: " + pct + "%" : phase.equals("download") ? "World download: " + pct + "%" : phase.equals("setup") ? "Setup hocche..." : phase.equals("zip") ? "Save pack hocche..." : "Complete");
        bar.setProgress(pct);
    }

    void render() {
        a.runOnUiThread(() -> {
            if (panel == null) return; panel.removeAllViews();
            panel.addView(tv("FoxMP v4  -  " + status, 0xFFFF8A00, 14));
            if (!connected) {
                EditText ip = et("Server IP", sp.getString("ip", "192.168.0.1")), nm = et("Tomar naam", sp.getString("name", "player"));
                panel.addView(ip); panel.addView(nm); Button c = btn("Connect", 0xFF2E7D32);
                c.setOnClickListener(v -> { sp.edit().putString("ip", ip.getText().toString().trim()).putString("name", nm.getText().toString().trim()).apply(); act.connect(ip.getText().toString().trim(), nm.getText().toString().trim()); });
                panel.addView(c); return;
            }
            panel.addView(tv("Tumi: " + me + "   World: " + (world.equals(me) ? "nijer" : world + " er"), Color.WHITE, 13));
            LinearLayout add = row(); EditText to = et("Friend er naam", ""); add.addView(to, new LinearLayout.LayoutParams(0, -2, 1)); Button sb = btn("Request", 0xFF1565C0);
            sb.setOnClickListener(v -> { act.tx("friend.request", "to", to.getText().toString().trim()); to.setText(""); }); add.addView(sb, wrap()); panel.addView(add);
            for (int i = 0; i < pending.length(); i++) { final String who = pending.optString(i); LinearLayout r = row(); r.addView(tv(who + " request", 0xFFFFD54F, 13), new LinearLayout.LayoutParams(0, -2, 1));
                Button ok = btn("Accept", 0xFF2E7D32), no = btn("Reject", 0xFFC62828); ok.setOnClickListener(v -> act.tx("friend.accept", "from", who)); no.setOnClickListener(v -> act.tx("friend.reject", "from", who)); r.addView(ok, wrap()); r.addView(no, wrap()); panel.addView(r); }
            panel.addView(tv("Friends", 0xFF90CAF9, 12));
            for (int i = 0; i < friends.length(); i++) { JSONObject f = friends.optJSONObject(i); final String n = f.optString("name"); boolean on = f.optBoolean("online"); LinearLayout r = row();
                r.addView(tv((on ? "● " : "○ ") + n + (on && !f.optString("world").isEmpty() ? "  [" + f.optString("world") + " world]" : ""), on ? 0xFF3DDC84 : 0xFF888888, 13), new LinearLayout.LayoutParams(0, -2, 1));
                if (on) { Button j = btn("Join", 0xFFFF8A00); j.setOnClickListener(v -> act.tx("world.join", "host", n)); r.addView(j, wrap()); } panel.addView(r); }
            LinearLayout w = row(); Button h = btn("Host save upload", 0xFF6A1B9A), l = btn("Leave world", 0xFF455A64); h.setOnClickListener(v -> act.host()); l.setOnClickListener(v -> act.leave()); w.addView(h, wrap()); w.addView(l, wrap()); panel.addView(w);
            LinearLayout v2 = row(); Button m = btn(mic ? "Mic ON" : "Mic OFF", mic ? 0xFF2E7D32 : 0xFF616161), dd = btn(deaf ? "Deaf ON" : "Deaf OFF", deaf ? 0xFFC62828 : 0xFF616161), ms = btn(mission ? "Mission ON" : "Mission OFF", mission ? 0xFFEF6C00 : 0xFF616161);
            m.setOnClickListener(v -> { mic = !mic; act.setMic(mic); render(); }); dd.setOnClickListener(v -> { deaf = !deaf; act.setDeaf(deaf); render(); }); ms.setOnClickListener(v -> { mission = !mission; act.setMission(mission); render(); });
            v2.addView(m, wrap()); v2.addView(dd, wrap()); v2.addView(ms, wrap()); panel.addView(v2);
            panel.addView(tv("Ei world e:", 0xFF90CAF9, 12));
            for (int i = 0; i < roster.length(); i++) { JSONObject p = roster.optJSONObject(i); panel.addView(tv("• " + p.optString("name") + (p.optBoolean("mission") ? "  (mission khelche)" : ""), Color.WHITE, 12)); }
            Button dc = btn("Disconnect", 0xFFB71C1C); dc.setOnClickListener(v -> act.disconnect()); panel.addView(dc);
        });
    }
}
