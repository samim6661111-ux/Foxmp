package com.gtavsource.android;

import android.app.Activity;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Core (headless). Created from the launcher menu or the game; the in-game overlay is attached from GTAVActivity.onCreate. */
public final class FoxMp implements FoxNet.Listener, FoxUi.Actions {
    private static FoxMp inst;
    public static synchronized FoxMp get(Context c) { if (inst == null) inst = new FoxMp(c.getApplicationContext()); FoxModel.ctx = c.getApplicationContext(); return inst; }
    /** hook target: GTAVActivity.onCreate */
    public static synchronized void start(Activity a) { FoxMp m = get(a); if (m.overlayAct != a) { m.overlayAct = a; new FoxUi(a, m, m.sync); } }

    final FoxSync sync = new FoxSync(); private FoxNet net; private FoxVoice voice; private volatile boolean tickRun; private Activity overlayAct;
    private volatile String ip = "", me = ""; private long lastTime;

    private FoxMp(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("foxmp", 0);
        FoxModel.ip = sp.getString("ip", "192.168.0.1"); FoxModel.name = sp.getString("name", "player");
    }

    // ---- actions (used by overlay + launcher page)
    public void connect(String ip, String name) {
        if (net != null) net.stop(); this.ip = ip; me = name; FoxModel.ip = ip; FoxModel.name = name; FoxModel.status = "connecting...";
        FoxModel.ctx.getSharedPreferences("foxmp", 0).edit().putString("ip", ip).putString("name", name).apply(); FoxModel.changed();
        net = new FoxNet(ip, name, this); voice = new FoxVoice(net); voice.mic = FoxModel.mic; voice.deaf = FoxModel.deaf; net.start(); voice.start();
    }
    public void disconnect() { tickRun = false; if (net != null) net.stop(); if (voice != null) voice.stop(); sync.clearAll(); net = null; FoxModel.connected = false; FoxModel.status = "offline"; FoxModel.changed(); }
    public void tx(String t, Object... kv) { if (net != null) net.tx(t, kv); }
    public void setMic(boolean on) { FoxModel.mic = on; if (voice != null) voice.mic = on; FoxModel.changed(); }
    public void setDeaf(boolean on) { FoxModel.deaf = on; if (voice != null) voice.deaf = on; FoxModel.changed(); }
    public void setMission(boolean on) { FoxModel.mission = on; sync.missionMode = on; tx("mission", "on", on); FoxModel.toast(on ? "Mission mode: onno der kache lukano, nijer progress e khelo" : "Mission mode off"); FoxModel.changed(); }
    public void chat(String msg) { tx("chat", "msg", msg); }
    public void invite(String who) { tx("invite", "to", who); }
    public void kick(String who) { tx("kick", "name", who); }
    public void host() {
        new Thread(() -> { try { FoxSave.upload(ip, net.httpPort, me, FoxModel::progress); FoxModel.toast("Save upload hoyeche. Friend ra Join korle download hobe."); tx("world.join", "host", me); }
            catch (Exception e) { FoxModel.progress("done", 100); FoxModel.toast("Upload fail: " + e.getMessage()); } }).start();
    }
    public void leave() {
        tx("world.leave"); sync.clearAll();
        new Thread(() -> { try { if (FoxSave.restoreOwn()) FoxModel.toast("Tomar nijer save ferot dewa hoyeche. Game e save load koro."); } catch (Exception e) { FoxModel.toast("Restore fail: " + e.getMessage()); } }).start();
    }

    // ---- network callbacks
    public void onState(boolean up) {
        FoxModel.connected = up; FoxModel.status = up ? "online" : "reconnecting..."; if (up) { me = net.name; FoxModel.me = me; startTicker(); } else sync.clearAll();
        FoxModel.changed();
    }
    public void onUdp(byte[] d, int n) { sync.onUdp(d, n); if (voice != null) voice.onUdp(d, n); }
    public void onMsg(JSONObject m) {
        switch (m.optString("t")) {
            case "friends": FoxModel.friends = m.optJSONArray("friends"); FoxModel.pending = m.optJSONArray("pending"); FoxModel.changed(); break;
            case "notice": case "err": FoxModel.toast(m.optString("msg")); break;
            case "chat": FoxModel.addChat(m.optString("from") + ": " + m.optString("msg")); break;
            case "invite": FoxModel.toast(m.optString("from") + " tomake invite korechhe - Friends theke Join dao"); FoxModel.addChat("[invite] " + m.optString("from")); break;
            case "kicked": FoxModel.toast(m.optString("by") + " tomake world theke kick koreche"); sync.clearAll(); break;
            case "time": if (!FoxModel.world.equals(me)) sync.setTime(m.optInt("h"), m.optInt("m")); break;
            case "roster": {
                JSONArray ps = m.optJSONArray("players"); FoxModel.roster = ps; FoxModel.world = m.optString("world"); Set<Integer> ids = new HashSet<>(); Map<Integer, String> nm = new HashMap<>(); Map<Integer, Boolean> ms = new HashMap<>();
                for (int i = 0; i < ps.length(); i++) { JSONObject p = ps.optJSONObject(i); ids.add(p.optInt("pid")); nm.put(p.optInt("pid"), p.optString("name")); ms.put(p.optInt("pid"), p.optBoolean("mission")); }
                sync.setRoster(ids, nm, ms, net.pid); FoxModel.changed(); break; }
            case "world.joined": {
                String host = m.optString("host"); FoxModel.world = host; FoxModel.changed();
                if (!host.equals(me) && m.optBoolean("save")) installWorld(host); else if (!host.equals(me)) FoxModel.toast(host + " er save upload kora nei; tar shathe tomar nijer save e khelte parbe.");
                break; }
        }
    }
    private void installWorld(String host) {
        new Thread(() -> { try { boolean ok = FoxSave.downloadAndInstall(ip, net.httpPort, host, FoxModel::progress);
            FoxModel.toast(ok ? "Setup complete! Game e " + host + " er save load koro, tarpor same map e khelo." : "Save pawa jay ni"); }
            catch (Exception e) { FoxModel.progress("done", 100); FoxModel.toast("Download fail: " + e.getMessage()); } }).start();
    }
    private synchronized void startTicker() {
        if (tickRun) return; tickRun = true;
        new Thread(() -> { while (tickRun) { try { FoxNet n = net; if (n == null) break; byte[] p = sync.tick(n.pid, n.up);
                if (p != null && n.up) n.sendUdp(p, p.length);
                long now = System.currentTimeMillis(); if (sync.snapOk && FoxModel.world.equals(me) && now - lastTime > 10000) { lastTime = now; tx("time", "h", sync.hour, "m", sync.minute); }
                Thread.sleep(50); } catch (Throwable t) { try { Thread.sleep(200); } catch (Exception e) {} } } }, "fox-tick").start();
    }
}
