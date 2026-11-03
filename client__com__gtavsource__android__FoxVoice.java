package com.gtavsource.android;

import android.media.*;
import java.util.*;

/** 16 kHz mono voice, mu-law 8 bit (16 KB/s), 20 ms frames, per-speaker jitter buffer. Relayed by the server inside the same world only. */
final class FoxVoice {
    static final int RATE = 16000, FRAME = 320;
    private final FoxNet net; volatile boolean mic, deaf, run = true; private AudioRecord rec; private short vseq;
    private final Map<Integer, Spk> spk = new HashMap<>();
    static final class Spk { AudioTrack t; int buffered; long last; }
    volatile int speakingMask; // bit0 = someone talks

    FoxVoice(FoxNet n) { net = n; }
    void start() { new Thread(this::capture, "fox-mic").start(); new Thread(this::reaper, "fox-vreap").start(); }
    void stop() { run = false; try { if (rec != null) rec.release(); } catch (Exception e) {} synchronized (spk) { for (Spk s : spk.values()) try { s.t.release(); } catch (Exception e) {} spk.clear(); } }

    private void capture() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        short[] pcm = new short[FRAME]; byte[] pk = new byte[7 + FRAME];
        while (run) {
            try {
                if (!mic || net.pid == 0) { if (rec != null) { rec.release(); rec = null; } Thread.sleep(120); continue; }
                if (rec == null) { rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8)); rec.startRecording(); }
                int n = 0; while (n < FRAME && mic && run) { int r = rec.read(pcm, n, FRAME - n); if (r <= 0) break; n += r; }
                if (n < FRAME) continue;
                long e = 0; for (short s : pcm) e += Math.abs(s);
                if (e / FRAME < 60) continue; // simple noise gate -> saves bandwidth
                pk[0] = 'V'; int p = net.pid; pk[1] = (byte) p; pk[2] = (byte) (p >> 8); pk[3] = (byte) (p >> 16); pk[4] = (byte) (p >> 24); pk[5] = (byte) vseq; pk[6] = (byte) (vseq++ >> 8);
                for (int i = 0; i < FRAME; i++) pk[7 + i] = lin2ulaw(pcm[i]);
                net.sendUdp(pk, pk.length);
            } catch (Throwable t) { try { Thread.sleep(300); } catch (Exception e) {} if (rec != null) { try { rec.release(); } catch (Exception e) {} rec = null; } }
        }
    }

    void onUdp(byte[] d, int n) {
        if (deaf || n != 7 + FRAME || d[0] != 'V') return;
        int pid = (d[1] & 255) | (d[2] & 255) << 8 | (d[3] & 255) << 16 | (d[4] & 255) << 24;
        short[] out = new short[FRAME]; for (int i = 0; i < FRAME; i++) out[i] = ulaw2lin(d[7 + i]);
        Spk s;
        synchronized (spk) {
            s = spk.get(pid);
            if (s == null) {
                s = new Spk();
                int bs = Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 2 * 6);
                s.t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bs, AudioTrack.MODE_STREAM);
                spk.put(pid, s);
            }
        }
        try {
            s.t.write(out, 0, FRAME); s.buffered++; s.last = System.currentTimeMillis();
            if (s.buffered == 3) s.t.play();   // prebuffer 60 ms against jitter
            speakingMask = 1;
        } catch (Throwable t) {}
    }
    private void reaper() {
        while (run) { try { Thread.sleep(500); } catch (Exception e) { return; }
            long now = System.currentTimeMillis(); boolean any = false;
            synchronized (spk) { for (Iterator<Spk> it = spk.values().iterator(); it.hasNext(); ) { Spk s = it.next();
                if (now - s.last > 1500) { try { s.t.release(); } catch (Exception e) {} it.remove(); } else if (now - s.last < 400) any = true; } }
            if (!any) speakingMask = 0; }
    }
    static byte lin2ulaw(short s) {
        int sign = (s >> 8) & 0x80; int v = s; if (sign != 0) v = -v; if (v > 32635) v = 32635; v += 0x84;
        int exp = 7; for (int m = 0x4000; (v & m) == 0 && exp > 0; m >>= 1) exp--;
        int man = (v >> (exp + 3)) & 0x0F; return (byte) ~(sign | (exp << 4) | man);
    }
    static short ulaw2lin(byte u) {
        u = (byte) ~u; int sign = u & 0x80, exp = (u >> 4) & 7, man = u & 15; int v = ((man << 3) + 0x84) << exp; v -= 0x84; return (short) (sign != 0 ? -v : v);
    }
}
