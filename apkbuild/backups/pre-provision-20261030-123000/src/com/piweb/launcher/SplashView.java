package com.piweb.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

/**
 * 启动页：深色底 + Pi Web 标志 + 标题 + 流动进度条。
 * 纯 Canvas 绘制，不依赖资源文件，也无第三方库。
 */
public class SplashView extends View {

    private static final int BG = 0xFF0E1116;
    private static final int CYAN = 0xFF22D3EE;
    private static final int GREEN = 0xFF4ADE80;
    private static final int TRACK = 0xFF1E2733;
    private static final int TITLE = 0xFFE8EDF3;
    private static final int MUTED = 0xFF8B95A5;
    private static final int WARN = 0xFFF87171;

    private final Paint bgPaint = new Paint();
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF barRect = new RectF();

    private String status = "正在启动服务";
    private String[] errorLines = null;
    private float phase = 0f;
    private boolean animating = true;
    private long lastFrameMs = 0;
    private long startMs = 0;

    public SplashView(Context context) {
        super(context);
        setBackgroundColor(BG);

        bgPaint.setColor(BG);

        logoPaint.setStyle(Paint.Style.STROKE);
        logoPaint.setStrokeCap(Paint.Cap.ROUND);
        logoPaint.setStrokeJoin(Paint.Join.ROUND);
        logoPaint.setColor(CYAN);

        accentPaint.setColor(GREEN);

        titlePaint.setColor(TITLE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setFakeBoldText(true);

        statusPaint.setColor(MUTED);
        statusPaint.setTextAlign(Paint.Align.CENTER);

        trackPaint.setColor(TRACK);
        barPaint.setColor(CYAN);

        float d = getResources().getDisplayMetrics().density;
        titlePaint.setTextSize(26f * d);
        statusPaint.setTextSize(14f * d);
        logoPaint.setStrokeWidth(13f * d);
    }

    public void setStatus(String text) {
        this.status = text == null ? "" : text;
        this.errorLines = null;
        this.animating = true;
        this.startMs = 0;
        invalidate();
    }

    /** 错误信息：第一行为标题（红色），其余为说明（灰色）。 */
    public void setError(String detail) {
        this.errorLines = detail == null ? new String[]{"启动失败"} : detail.split("\n");
        this.animating = false;
        invalidate();
    }

    public void stop() {
        animating = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float d = getResources().getDisplayMetrics().density;

        canvas.drawRect(0, 0, w, h, bgPaint);

        long now = System.currentTimeMillis();
        if (startMs == 0) startMs = now;
        if (lastFrameMs == 0) lastFrameMs = now;
        float dt = Math.min(now - lastFrameMs, 100L) / 1000f;
        lastFrameMs = now;
        if (animating) {
            phase += dt / 1.5f;                 // 约 1.5 秒一个循环
            if (phase > 1f) phase -= 1f;
        }

        float cx = w / 2f;
        float blockTop = h / 2f - 96f * d;

        // ---- 标志（青色 > 与绿色横杠）----
        float L = 92f * d;
        float ly = blockTop + L / 2f;
        android.graphics.Path chevron = new android.graphics.Path();
        chevron.moveTo(cx - L * 0.26f, ly - L * 0.30f);
        chevron.lineTo(cx + L * 0.04f, ly);
        chevron.lineTo(cx - L * 0.26f, ly + L * 0.30f);
        canvas.drawPath(chevron, logoPaint);

        float barLeft = cx + L * 0.14f;
        float barTop = ly + L * 0.10f;
        RectF accent = new RectF(barLeft, barTop, barLeft + L * 0.30f, barTop + L * 0.14f);
        canvas.drawRoundRect(accent, L * 0.03f, L * 0.03f, accentPaint);

        // ---- 标题 ----
        float titleY = blockTop + L + 46f * d;
        canvas.drawText("Pi Web", cx, titleY, titlePaint);

        if (errorLines != null) {
            drawError(canvas, cx, titleY + 34f * d, d);
            return;
        }

        // ---- 状态文案（带跳动的省略号）----
        int dots = (int) (((now - startMs) / 420L) % 4);
        StringBuilder sb = new StringBuilder(status);
        for (int i = 0; i < dots; i++) sb.append('.');
        canvas.drawText(sb.toString(), cx, titleY + 32f * d, statusPaint);

        // ---- 流动进度条 ----
        float barW = 150f * d;
        float barH = 4f * d;
        float trackY = titleY + 56f * d;
        trackRect.set(cx - barW / 2f, trackY, cx + barW / 2f, trackY + barH);
        canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, trackPaint);

        float segW = barW * 0.34f;
        float travel = barW - segW;
        float eased = easeInOut(phase);
        float segLeft = trackRect.left + travel * eased;
        barRect.set(segLeft, trackY, segLeft + segW, trackY + barH);
        barPaint.setShader(new LinearGradient(
                barRect.left, 0f, barRect.right, 0f,
                new int[]{0x0022D3EE, CYAN, 0xFF4ADE80, 0x004ADE80},
                new float[]{0f, 0.25f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, barPaint);
        barPaint.setShader(null);

        if (animating) postInvalidateOnAnimation();
    }

    private void drawError(Canvas canvas, float cx, float top, float d) {
        statusPaint.setColor(WARN);
        float y = top;
        boolean first = true;
        for (String raw : errorLines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                y += 8f * d;
                continue;
            }
            statusPaint.setColor(first ? WARN : MUTED);
            Paint p = first ? statusPaint : statusPaint;
            float maxWidth = getWidth() - 80f * d;
            // 简易换行
            for (String part : wrap(line, p, maxWidth)) {
                canvas.drawText(part, cx, y, p);
                y += (first ? 22f : 21f) * d;
            }
            first = false;
        }
        statusPaint.setColor(MUTED);
        canvas.drawText("轻触屏幕重试", cx, y + 14f * d, statusPaint);
    }

    private java.util.List<String> wrap(String text, Paint p, float maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (p.measureText(text) <= maxWidth) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (p.measureText(cur.toString()) > maxWidth) {
                cur.setLength(cur.length() - 1);
                out.add(cur.toString());
                cur = new StringBuilder();
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private float easeInOut(float t) {
        return t < 0.5f ? 2f * t * t : 1f - 2f * (1f - t) * (1f - t);
    }
}
