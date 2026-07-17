package com.sift.explorer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import com.sift.explorer.util.DiskUsage.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic DiskUsage-style columns. Each opened directory level is a column; children are
 * stacked vertically with height proportional to their share of the parent. Tap a folder to
 * drill in (a new column appears and the view pans right), tap a file to surface its details,
 * and drag/fling to pan across levels. The root column can show a trailing "Free space" band.
 */
public class DiskUsageView extends View {

    /** Tableau-10 palette — legible on both light and dark surfaces. */
    private static final int[] PALETTE = {
            0xFF4E79A7, 0xFFF28E2B, 0xFF59A14F, 0xFFE15759, 0xFF76B7B2,
            0xFFEDC948, 0xFFB07AA1, 0xFFFF9DA7, 0xFF9C755F, 0xFFBAB0AC
    };
    private static final int FREE_COLOR = 0x40808080;

    public interface Listener {
        /** The opened directory changed (drilled in or up). */
        void onNodeOpened(Node node);
        /** A file rectangle was tapped. */
        void onFileTapped(Node node);
    }

    private Node root;
    private Node freeSpace;                 // synthetic; only shown in the root column
    private final List<Node> openPath = new ArrayList<>();
    private Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float colW;
    private final float gap;
    private final float pad;
    private final float lineH;

    private final OverScroller scroller;
    private final GestureDetector gestures;
    private int scrollX;

    public DiskUsageView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        colW = 168 * density;
        gap = 1.5f * density;
        pad = 6 * density;
        text.setTextSize(13 * density);
        lineH = text.descent() - text.ascent();
        scroller = new OverScroller(ctx);
        gestures = new GestureDetector(ctx, new Gestures());
        setBackgroundColor(Color.TRANSPARENT);  // gaps between rects show the parent surface
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Volume free space to show as a trailing band in the root column, or <= 0 for none. */
    public void setFreeSpace(long freeBytes) {
        freeSpace = freeBytes > 0 ? com.sift.explorer.util.DiskUsage.freeSpaceNode(freeBytes) : null;
        invalidate();
    }

    public void setRoot(Node r) {
        this.root = r;
        openPath.clear();
        openPath.add(r);
        scrollX = 0;
        if (listener != null) listener.onNodeOpened(r);
        invalidate();
    }

    /** The directory currently at the deepest opened level. */
    public Node currentNode() { return openPath.isEmpty() ? null : openPath.get(openPath.size() - 1); }

    /** Pops one level; returns false when already at the scan root. */
    public boolean goUp() {
        if (openPath.size() <= 1) return false;
        openPath.remove(openPath.size() - 1);
        animateToEnd();
        if (listener != null) listener.onNodeOpened(currentNode());
        invalidate();
        return true;
    }

    // ---- drawing ---------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        if (root == null) return;
        int h = getHeight();
        clampScroll();
        for (int i = 0; i < openPath.size(); i++) {
            float left = i * colW - scrollX;
            if (left > getWidth() || left + colW < 0) continue;   // off-screen column
            drawColumn(canvas, openPath.get(i), i, left, h);
        }
    }

    private void drawColumn(Canvas canvas, Node owner, int depth, float left, int colHeight) {
        Node[] kids = owner.children;
        if (kids == null) return;
        boolean rootCol = depth == 0 && freeSpace != null;
        long denom = owner.size + (rootCol ? freeSpace.size : 0);
        if (denom <= 0) return;

        Node opened = depth + 1 < openPath.size() ? openPath.get(depth + 1) : null;
        float y = 0;
        int count = kids.length + (rootCol ? 1 : 0);
        for (int idx = 0; idx < count; idx++) {
            Node n = idx < kids.length ? kids[idx] : freeSpace;
            float frac = (float) ((double) n.size / denom);
            float bandH = frac * colHeight;
            float top = y;
            y += bandH;
            if (bandH < gap) continue;                    // sub-pixel sliver: occupies space, not drawn

            float bottom = y - gap;
            int base = n.isFreeSpace ? FREE_COLOR : PALETTE[(idx + depth) % PALETTE.length];
            int color = n.isFreeSpace ? base : (n.isDir ? base : withAlpha(base, 140));
            fill.setColor(color);
            canvas.drawRect(left + gap, top + gap, left + colW - gap, bottom, fill);

            if (n == opened) {                            // outline the drilled-into child
                fill.setColor(0xFFFFFFFF);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(2 * density);
                canvas.drawRect(left + gap + density, top + gap + density,
                        left + colW - gap - density, bottom - density, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            drawLabel(canvas, n, left, top + gap, bottom, color);
        }
    }

    private void drawLabel(Canvas canvas, Node n, float left, float top, float bottom, int bg) {
        float avail = bottom - top;
        if (avail < 15 * density) return;                 // too short for any text
        text.setColor(luminance(bg) > 0.55 ? 0xFF101010 : 0xFFFFFFFF);
        float textLeft = left + pad + gap;
        float textW = colW - 2 * (pad + gap);
        CharSequence name = TextUtils.ellipsize(n.name, text, textW, TextUtils.TruncateAt.MIDDLE);
        float baseY = top + pad - text.ascent();
        canvas.drawText(name, 0, name.length(), textLeft, baseY, text);
        if (avail >= 15 * density + lineH) {              // room for a size line
            String sz = Formatter.formatFileSize(getContext(), n.size);
            int save = text.getColor();
            text.setColor(luminance(bg) > 0.55 ? 0xCC000000 : 0xCCFFFFFF);
            canvas.drawText(sz, textLeft, baseY + lineH, text);
            text.setColor(save);
        }
    }

    // ---- interaction -----------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            postInvalidateOnAnimation();
        }
    }

    private class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            scrollX += (int) dx;
            clampScroll();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            scroller.fling(scrollX, 0, (int) -vx, 0, 0, maxScroll(), 0, 0);
            postInvalidateOnAnimation();
            return true;
        }

        @Override public boolean onSingleTapUp(MotionEvent e) {
            handleTap(e.getX(), e.getY());
            return true;
        }
    }

    private void handleTap(float x, float y) {
        if (root == null) return;
        int depth = (int) ((scrollX + x) / colW);
        if (depth < 0 || depth >= openPath.size()) return;
        Node owner = openPath.get(depth);
        Node hit = nodeAt(owner, depth, y);
        if (hit == null || hit.isFreeSpace) return;
        if (hit.isDir) {
            while (openPath.size() > depth + 1) openPath.remove(openPath.size() - 1);
            openPath.add(hit);
            animateToEnd();
            if (listener != null) listener.onNodeOpened(hit);
            invalidate();
        } else if (listener != null) {
            listener.onFileTapped(hit);
        }
    }

    private Node nodeAt(Node owner, int depth, float y) {
        Node[] kids = owner.children;
        if (kids == null) return null;
        boolean rootCol = depth == 0 && freeSpace != null;
        long denom = owner.size + (rootCol ? freeSpace.size : 0);
        if (denom <= 0) return null;
        int colHeight = getHeight();
        float cursor = 0;
        int count = kids.length + (rootCol ? 1 : 0);
        for (int idx = 0; idx < count; idx++) {
            Node n = idx < kids.length ? kids[idx] : freeSpace;
            cursor += (float) ((double) n.size / denom) * colHeight;
            if (y <= cursor) return n;
        }
        return null;
    }

    // ---- scroll helpers --------------------------------------------------

    private int maxScroll() { return Math.max(0, (int) (openPath.size() * colW - getWidth())); }

    private void clampScroll() {
        if (scrollX < 0) scrollX = 0;
        int max = maxScroll();
        if (scrollX > max) scrollX = max;
    }

    private void animateToEnd() {
        int target = maxScroll();
        scroller.forceFinished(true);
        scroller.startScroll(scrollX, 0, target - scrollX, 0, 250);
        postInvalidateOnAnimation();
    }

    // ---- color utils -----------------------------------------------------

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static double luminance(int color) {
        int a = Color.alpha(color);
        // blend toward mid-grey for translucent fills so text contrast is judged on the visible color
        double r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        double f = a / 255.0;
        r = r * f + 128 * (1 - f);
        g = g * f + 128 * (1 - f);
        b = b * f + 128 * (1 - f);
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }
}
