package com.gtavsource.android;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** World save transfer. Host uploads its SGTA5* slots; a guest downloads them and plays the host's world as a separate profile.
 *  The guest's own saves are backed up first and restored when leaving the world. */
final class FoxSave {
    interface Progress { void on(String phase, int pct); }
    static final File ROOT = new File("/storage/emulated/0/Games/GTAV");
    static final Pattern SLOT = Pattern.compile("(?i)SGTA5[0-9a-z_-]{1,32}(?:\\.bak)?");
    static File backup() { return new File(ROOT, "foxmp/own_backup.zip"); }

    static List<File> slots() {
        List<File> r = new ArrayList<>(); walk(ROOT, 0, r); return r;
    }
    private static void walk(File d, int depth, List<File> r) {
        File[] fs = d.listFiles(); if (fs == null || depth > 3) return;
        for (File f : fs) { if (f.isDirectory()) { if (!f.getName().equals("foxmp")) walk(f, depth + 1, r); } else if (SLOT.matcher(f.getName()).matches()) r.add(f); }
    }
    private static void zipTo(List<File> files, File zip) throws IOException {
        zip.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : files) { String rel = f.getAbsolutePath().substring(ROOT.getAbsolutePath().length() + 1); z.putNextEntry(new ZipEntry(rel)); try (FileInputStream in = new FileInputStream(f)) { copy(in, z); } z.closeEntry(); }
        }
    }
    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
    private static void unzip(File zip) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e; while ((e = z.getNextEntry()) != null) {
                File out = new File(ROOT, e.getName());
                if (!out.getCanonicalPath().startsWith(ROOT.getCanonicalPath() + File.separator) || !SLOT.matcher(out.getName()).matches()) continue; // zip-slip + only save slots
                out.getParentFile().mkdirs(); try (FileOutputStream o = new FileOutputStream(out)) { copy(z, o); }
            }
        }
    }

    static void upload(String host, int port, String me, Progress p) throws IOException {
        List<File> s = slots(); if (s.isEmpty()) throw new IOException("save slot (SGTA5*) paoa jay ni: " + ROOT);
        p.on("zip", 5); File tmp = new File(ROOT, "foxmp/host_upload.zip"); zipTo(s, tmp);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/save/" + me).openConnection();
        c.setDoOutput(true); c.setRequestMethod("POST"); c.setFixedLengthStreamingMode(tmp.length()); c.setConnectTimeout(8000);
        long total = tmp.length(), sent = 0;
        try (OutputStream o = c.getOutputStream(); FileInputStream in = new FileInputStream(tmp)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) { o.write(b, 0, n); sent += n; p.on("upload", (int) (10 + 90 * sent / Math.max(1, total))); }
        }
        if (c.getResponseCode() != 200) throw new IOException("upload fail " + c.getResponseCode());
        p.on("done", 100);
    }

    /** returns true if installed */
    static boolean downloadAndInstall(String host, int port, String world, Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/save/" + world).openConnection(); c.setConnectTimeout(8000);
        if (c.getResponseCode() != 200) return false;
        long total = c.getContentLengthLong(), got = 0; File tmp = new File(ROOT, "foxmp/guest_download.zip"); tmp.getParentFile().mkdirs();
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) { o.write(b, 0, n); md.update(b, 0, n); got += n; p.on("download", (int) (90 * got / Math.max(1, total))); }
        }
        StringBuilder h = new StringBuilder(); for (byte x : md.digest()) h.append(String.format("%02x", x));
        String want = c.getHeaderField("X-SHA256"); if (want != null && !want.equalsIgnoreCase(h.toString())) throw new IOException("download corrupt (sha mismatch)");
        p.on("setup", 92);
        if (!backup().exists()) zipTo(slots(), backup());         // own progress is kept safe, first time only
        for (File f : slots()) f.delete();
        unzip(tmp); tmp.delete(); p.on("done", 100); return true;
    }

    /** put own saves back (leave world) */
    static boolean restoreOwn() throws IOException {
        if (!backup().exists()) return false;
        for (File f : slots()) f.delete(); unzip(backup()); backup().delete(); return true;
    }
}
