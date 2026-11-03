package com.gtavsource.android;

import org.json.*;
import java.nio.*;
import java.util.*;

/** Reads own state from the game trainer snapshot, sends it, and drives remote players (peds) with trainer commands. */
final class FoxSync {
    static final class Remote {
        int pid; String name = "?"; float x, y, z, h, tx, ty, tz, th; int veh, hp; long seen; int ent; boolean mission, spawned;
        int vmodel, seat, flags, vent; boolean vspawned, seated; float lx, ly, lz; long lastMove;
    }
    // ---- vehicle support: command names are probed at runtime because the trainer command list is not known
    static final String[] VEH_CMDS = {"vehicle.spawn", "veh.spawn", "car.spawn", "vehicle.create"};
    String vehCmd; boolean vehProbeFailed; final StringBuilder diag = new StringBuilder();
    static final String[] CARS = {"adder","sultan","zentorno","infernus","banshee","bati","faggio","sanchez","bmx","blista","dominator","comet2","turismor","t20","elegy2","kuruma","buzzard","maverick","frogger","dinghy","marquis","jester","carbonrizzo","akuma","hakuchou","police","police2","taxi","bus","tailgater","buffalo","schafter2","oracle","sentinel","felon","futo","issi2","panto","prairie","rebel","bison","sandking","insurgent","rhino","savage","hydra","lazer","cargobob","annihilator","mesa","baller","cavalcade","granger","patriot","stretch","burrito","speedo","rumpo","youga","journey","phantom","packer","hauler","tractor","flatbed","tow truck","dune","bodhi2","blazer","vigero","gauntlet","ruiner","stalion","picador","tornado","voodoo","manana","peyote","pigalle","btype","coquette","feltzer2","fq2","habanero","landstalker","radi","seminole","serrano","surge","washington","zion","exemplar","felon2","jackal","ninef","ninef2","rapidgt","rapidgt2","surano","carbonrs","double","bagger","daemon","hexer","nemesis","pcj","ruffian","vader","vindicator","shotaro","vacca","bullet","cheetah","entityxf","fmj","osiris","reaper","sheava","pfister811","zorrusso","gp1","tyrus","raptor","windsor","windsor2","jester2","massacro","massacro2","dubsta","dubsta2","fusilade","specter","schwarzer","kalahari","brawler","trophytruck","slamvan","virgo","sabregt","buccaneer","chino","dukes","faction","hotknife","phoenix","picador","sadler","stanier","stratum","intruder","premier","primo","regina","romero","stinger","stingergt","superd","tampa","vacca","voltic","zentorno"};
    static int joaat(String k) { int h = 0; for (char c : k.toLowerCase().toCharArray()) { h += c; h += (h << 10); h ^= (h >>> 6); } h += (h << 3); h ^= (h >>> 11); h += (h << 15); return h; }
    static final Map<Integer, String> CAR_BY_HASH = new HashMap<>();
    static { for (String c : CARS) CAR_BY_HASH.put(joaat(c), c); }
    static String carName(int hash) { String n = CAR_BY_HASH.get(hash); return n != null ? n : "sultan"; }
    int mvmodel, mseat, mflags;
    static final int FL_AIM = 1, FL_SHOOT = 2, FL_JUMP = 4, FL_SPRINT = 8, FL_DEAD = 16, FL_ENGINE = 32;
    final Map<Integer, Remote> remotes = new HashMap<>();
    final Set<Integer> known = new HashSet<>();
    final ArrayDeque<int[]> waiting = new ArrayDeque<>(); // {pid, kind 0=ped 1=vehicle}
    float mx, my, mz, mh; int mveh, mhp, hour, minute; boolean snapOk; private short seq; private boolean primed;
    String model = "a_m_y_hipster_01";
    volatile boolean missionMode;

    private static TrainerBridge.Endpoint ep() { return TrainerBridge.NATIVE; }
    private int submit(String c, String a, int i, int j, float... f) {
        try { return ep().submit(c, a, i, j, f); } catch (Throwable t) { return -1; }
    }

    void setTime(int h, int m) { submit("time.set", "", 0, 0, (float) h, (float) m, 0f); }

    JSONObject snapshot() {
        try { String s = ep().snapshot(); return s == null ? null : new JSONObject(s); } catch (Throwable t) { return null; }
    }

    /** one 20 Hz tick; returns packet to send (or null) */
    byte[] tick(int pid, boolean inWorld) {
        JSONObject s = snapshot();
        snapOk = s != null && s.optBoolean("ready", true);
        if (s == null) return null;
        mx = (float) s.optDouble("x"); my = (float) s.optDouble("y"); mz = (float) s.optDouble("z"); mh = (float) s.optDouble("heading");
        mveh = s.optInt("vehicle"); mhp = s.optInt("health"); hour = s.optInt("hour"); minute = s.optInt("minute");
        JSONArray ents = s.optJSONArray("entities");
        readExtras(s, ents);
        if (!primed) { primed = true; if (ents != null) for (int i = 0; i < ents.length(); i++) known.add(ents.optJSONObject(i).optInt("id")); }
        if (inWorld) { bindSpawns(ents); drive(); }
        if (pid == 0) return null;
        ByteBuffer b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'J').putInt(pid).putShort(seq++).putFloat(mx).putFloat(my).putFloat(mz).putFloat(mh).putInt(mveh).putShort((short) mhp).put((byte) (missionMode ? 1 : 0))
         .putInt(mvmodel).put((byte) mseat).put((byte) mflags);
        return b.array();
    }

    void onUdp(byte[] d, int n) {
        if (n < 30 || d[0] != 'J') return;
        ByteBuffer b = ByteBuffer.wrap(d, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        b.get(); int pid = b.getInt(); b.getShort();
        synchronized (remotes) {
            Remote r = remotes.get(pid); if (r == null) { r = new Remote(); r.pid = pid; remotes.put(pid, r); }
            r.tx = b.getFloat(); r.ty = b.getFloat(); r.tz = b.getFloat(); r.th = b.getFloat(); r.veh = b.getInt(); r.hp = b.getShort(); r.mission = b.get() != 0;
            if (n >= 36) { r.vmodel = b.getInt(); r.seat = b.get(); r.flags = b.get() & 0xff; } else { r.vmodel = 0; r.seat = 0; r.flags = 0; }
            if (!r.spawned && r.x == 0 && r.y == 0) { r.x = r.tx; r.y = r.ty; r.z = r.tz; }
            r.seen = System.currentTimeMillis();
        }
    }

    /** keep only players the server roster says are in my world */
    void setRoster(Set<Integer> pids, Map<Integer, String> names, Map<Integer, Boolean> mission, int me) {
        synchronized (remotes) {
            for (Iterator<Map.Entry<Integer, Remote>> it = remotes.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Remote> e = it.next();
                if (!pids.contains(e.getKey()) || e.getKey() == me) { delRemote(e.getValue()); it.remove(); }
            }
            for (int p : pids) if (p != me) {
                Remote r = remotes.get(p); if (r == null) { r = new Remote(); r.pid = p; remotes.put(p, r); }
                r.name = names.get(p); r.mission = Boolean.TRUE.equals(mission.get(p));
            }
        }
    }

    void clearAll() {
        synchronized (remotes) { for (Remote r : remotes.values()) delRemote(r); remotes.clear(); waiting.clear(); }
    }

    /** own vehicle model / seat / action flags. Every field is optional: whatever the snapshot has is used. */
    private void readExtras(JSONObject s, JSONArray ents) {
        int vm = firstInt(s, "vehicleModel", "vehicle_model", "vehicleHash", "vehModel");
        if (vm == 0 && mveh != 0 && ents != null) for (int i = 0; i < ents.length(); i++) { JSONObject e = ents.optJSONObject(i); if (e != null && e.optInt("id") == mveh) { vm = firstInt(e, "model", "hash", "modelHash"); break; } }
        if (vm == 0 && mveh != 0) { String nm = s.optString("vehicleName", s.optString("vehicle_name", "")); if (!nm.isEmpty()) vm = joaat(nm); }
        mvmodel = mveh != 0 ? vm : 0;
        mseat = mveh != 0 ? s.optInt("seat", s.optInt("vehicleSeat", -1)) : -2;   // -1 driver, 0.. passengers, -2 on foot
        int f = 0;
        if (s.optBoolean("aiming") || s.optBoolean("isAiming")) f |= FL_AIM;
        if (s.optBoolean("shooting") || s.optBoolean("isShooting")) f |= FL_SHOOT;
        if (s.optBoolean("jumping") || s.optBoolean("inAir")) f |= FL_JUMP;
        if (s.optBoolean("sprinting") || s.optBoolean("running")) f |= FL_SPRINT;
        if (mhp <= 0 && s.has("health")) f |= FL_DEAD;
        if (s.optBoolean("engine", true)) f |= FL_ENGINE;
        mflags = f;
    }
    private static int firstInt(JSONObject o, String... k) { for (String x : k) if (o.has(x)) { long v = o.optLong(x); if (v != 0) return (int) v; } return 0; }
    private static boolean isType(JSONObject e, String want) { String t = e.optString("type", e.optString("kind", "")).toLowerCase(); return t.isEmpty() || t.contains(want); }

    private void delRemote(Remote r) {
        if (r.ent > 0) { submit("entity.delete", "", r.ent, 0, new float[0]); known.remove(r.ent); }
        if (r.vent > 0) { submit("entity.delete", "", r.vent, 0, new float[0]); known.remove(r.vent); }
        r.ent = 0; r.vent = 0; r.spawned = false; r.vspawned = false; r.seated = false;
    }
    private void dropVehicle(Remote r) {
        if (r.vent > 0) { submit("entity.delete", "", r.vent, 0, new float[0]); known.remove(r.vent); }
        r.vent = 0; r.vspawned = false; r.seated = false;
        if (r.ent > 0) FoxNative.safe("CLEAR_PED_TASKS", new long[]{0, r.ent}, new float[8]);
    }
    /** try the known vehicle spawn command names; remember the first one that works */
    private int spawnVehicle(String model) {
        if (vehCmd != null) return submit(vehCmd, model, 0, 0, new float[0]);
        if (vehProbeFailed) return -1;
        for (String c : VEH_CMDS) {
            int rc = submit(c, model, 0, 0, new float[0]);
            diag.append(c).append("=").append(rc).append(' ');
            if (rc > 0) { vehCmd = c; FoxModel.toast("Vehicle sync ON (" + c + ")"); return rc; }
        }
        vehProbeFailed = true; FoxModel.toast("Gari spawn command pawa jay ni - friend ghost hishebe cholbe. Diag: " + diag);
        return -1;
    }

    private void bindSpawns(JSONArray ents) {
        if (ents == null || waiting.isEmpty()) return;
        for (int i = 0; i < ents.length() && !waiting.isEmpty(); i++) {
            JSONObject e = ents.optJSONObject(i); if (e == null) continue;
            int id = e.optInt("id");
            if (known.contains(id)) continue;
            int[] w = waiting.peek(); boolean veh = w[1] == 1;
            if (!isType(e, veh ? "veh" : "ped")) continue;           // skip traffic of the wrong kind
            waiting.poll(); known.add(id);
            synchronized (remotes) {
                Remote r = remotes.get(w[0]);
                if (r == null) { submit("entity.delete", "", id, 0, new float[0]); continue; }
                if (veh) { r.vent = id; r.vspawned = true; submit("entity.toggle", "invincible", id, 0, 1f); }
                else { r.ent = id; r.spawned = true; submit("entity.toggle", "invincible", id, 0, 1f); }
            }
        }
    }

    private boolean queued(int pid, int kind) { for (int[] w : waiting) if (w[0] == pid && w[1] == kind) return true; return false; }

    private void drive() {
        long now = System.currentTimeMillis(); int budget = 20;
        synchronized (remotes) {
            for (Remote r : remotes.values()) {
                boolean gone = r.mission || now - r.seen > 6000 || (r.tx == 0 && r.ty == 0);
                if (gone) { if (r.ent > 0 || r.vent > 0) if (r.mission || now - r.seen > 6000) delRemote(r); continue; }
                if (r.ent == 0) {
                    if (!r.spawned && !queued(r.pid, 0) && waiting.size() < 4 && known.size() < 250 && submit("ped.spawn", model, 0, 0, new float[0]) > 0) { waiting.add(new int[]{r.pid, 0}); r.spawned = true; }
                    continue;
                }
                float k = 0.55f; r.x += (r.tx - r.x) * k; r.y += (r.ty - r.y) * k; r.z += (r.tz - r.z) * k; r.h = r.th;
                boolean inCar = r.veh != 0 && r.seat > -2;

                // ---- get in / out of a vehicle
                if (inCar && r.vent == 0 && !vehProbeFailed) {
                    if (!r.vspawned && !queued(r.pid, 1) && waiting.size() < 4 && spawnVehicle(carName(r.vmodel)) > 0) { waiting.add(new int[]{r.pid, 1}); r.vspawned = true; }
                } else if (!inCar && r.vent > 0) { dropVehicle(r); }
                if (!inCar) r.vspawned = r.vent > 0;

                if (budget-- > 0) {
                    if (r.vent > 0 && inCar) {
                        // vehicle follows the remote player; the ped is warped in (native) or rides on top of it (fallback)
                        submit("entity.move", "", r.vent, 0, r.x, r.y, r.z, 0f);
                        submit("entity.rotate", "", r.vent, 0, 0f, 0f, r.h);
                        if (!r.seated && FoxNative.has(FoxNative.F_WARP)) { FoxNative.warpIntoVehicle(r.ent, r.vent, r.seat); r.seated = true; }
                        if (!r.seated) submit("entity.move", "", r.ent, 0, r.x, r.y, r.z + 0.4f, 0f);
                    } else {
                        submit("entity.move", "", r.ent, 0, r.x, r.y, r.z, 0f);
                        if (budget > 8) submit("entity.rotate", "", r.ent, 0, 0f, 0f, r.h);
                    }
                }
                // ---- on-foot activities
                if (!inCar) {
                    if ((r.flags & (FL_AIM | FL_SHOOT)) != 0 && FoxNative.has(FoxNative.F_AIM) && now - r.lastMove > 400) {
                        r.lastMove = now; double a = Math.toRadians(r.h);
                        FoxNative.safe("TASK_AIM_GUN_AT_COORD", new long[]{r.ent}, new float[]{r.x - (float) Math.sin(a) * 20f, r.y + (float) Math.cos(a) * 20f, r.z + 1f, -1f});
                    }
                    if ((r.flags & FL_DEAD) != 0) submit("entity.toggle", "ragdoll", r.ent, 0, 1f);
                }
            }
        }
    }
}
