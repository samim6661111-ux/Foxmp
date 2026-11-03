package com.gtavsource.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Mini radar: shows friends in your world around you (heading-up). Replaces map blips / name tags until native blips are verified. */
final class FoxRadar extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final FoxSync s; static final float RANGE = 250f; private final float d;
    FoxRadar(Context c, FoxSync s) { super(c); this.s = s; d = c.getResources().getDisplayMetrics().density; p.setTextSize(10 * d); }
    @Override protected void onMeasure(int w, int h) { int n = (int) (130 * d); setMeasuredDimension(n, n); }
    @Override protected void onDraw(Canvas c) {
        float r = getWidth() / 2f; p.setStyle(Paint.Style.FILL); p.setColor(0x99101820); c.drawCircle(r, r, r - 2, p);
        p.setStyle(Paint.Style.STROKE); p.setColor(0xFFFF8A00); p.setStrokeWidth(2 * d); c.drawCircle(r, r, r - 2, p);
        p.setStrokeWidth(1); p.setColor(0x55FFFFFF); c.drawCircle(r, r, (r - 2) / 2, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawCircle(r, r, 3 * d, p);
        double a = Math.toRadians(s.mh);
        synchronized (s.remotes) {
            for (FoxSync.Remote m : s.remotes.values()) {
                if (m.tx == 0 && m.ty == 0) continue;
                float dx = m.tx - s.mx, dy = m.ty - s.my; float dist = (float) Math.hypot(dx, dy);
                // rotate world delta into heading-up screen space
                double rx = dx * Math.cos(a) + dy * Math.sin(a), ry = -dx * Math.sin(a) + dy * Math.cos(a);
                float k = Math.min(dist, RANGE) / RANGE * (r - 10 * d); double ang = Math.atan2(ry, rx);
                float px = r + (float) Math.cos(ang) * k, py = r - (float) Math.sin(ang) * k;
                p.setColor(m.mission ? 0xFF888888 : 0xFF3DDC84); c.drawCircle(px, py, 4 * d, p);
                p.setColor(0xFFFFFFFF); String t = (m.name == null ? "?" : m.name) + " " + (int) dist + "m";
                c.drawText(t, Math.min(Math.max(px - p.measureText(t) / 2, 2), getWidth() - p.measureText(t) - 2), py - 6 * d, p);
            }
        }
    }
}
