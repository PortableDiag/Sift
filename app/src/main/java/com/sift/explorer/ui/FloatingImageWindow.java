package com.sift.explorer.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.sift.explorer.R;

import java.io.File;

/**
 * One image floating over whatever is on screen: a title bar that drags it, a corner grip that
 * resizes it, and a {@link ZoomImageView} inside that pinch-zooms and pans as it does elsewhere.
 *
 * <p>Owned by {@link FloatingImageService} — the window belongs to the WindowManager, not to any
 * activity, so nothing here may hold an Activity context.
 */
class FloatingImageWindow {

    /** Never let a window shrink below this, or the grip becomes unhittable. */
    private static final int MIN_DP = 120;
    /** Height of the title bar in floating_image.xml — the part that is not image. */
    private static final int BAR_DP = 36;
    /** Decode cap. Big enough that zooming in still has detail, small enough to stay cheap. */
    private static final int DECODE_MAX = 1600;

    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final View root;
    private final float density;

    private Runnable onClose;
    private boolean added;

    FloatingImageWindow(Context ctx, File file, String name, int cascade) {
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.density = ctx.getResources().getDisplayMetrics().density;

        root = LayoutInflater.from(ctx).inflate(R.layout.floating_image, null, false);
        ((TextView) root.findViewById(R.id.title)).setText(name);

        Point screen = screenSize();
        int w = Math.min((int) (screen.x * 0.62f), dp(340));
        int h = Math.min((int) (screen.y * 0.42f), dp(420));

        params = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE keeps input going to whatever is underneath, so the window
                // floats over an app you are still using rather than stealing its keyboard.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Cascade so a second pop-out doesn't land exactly on the first.
        int step = dp(24) * cascade;
        params.x = Math.min(dp(16) + step, Math.max(0, screen.x - w));
        params.y = Math.min(dp(72) + step, Math.max(0, screen.y - h));

        root.findViewById(R.id.close).setOnClickListener(v -> close());
        root.findViewById(R.id.bar).setOnTouchListener(dragListener());
        root.findViewById(R.id.resize).setOnTouchListener(resizeListener());

        final ImageView image = root.findViewById(R.id.image);
        Glide.with(ctx.getApplicationContext())
                .load(file)
                .override(DECODE_MAX, DECODE_MAX)
                // The starting size is a guess; once the bitmap is decoded the window takes the
                // image's own shape, so a landscape photo doesn't sit in a portrait frame.
                .listener(new RequestListener<Drawable>() {
                    @Override public boolean onLoadFailed(@Nullable GlideException e,
                            Object model, Target<Drawable> target, boolean first) {
                        return false;
                    }
                    @Override public boolean onResourceReady(Drawable res, Object model,
                            Target<Drawable> target, DataSource source, boolean first) {
                        final int dw = res.getIntrinsicWidth(), dh = res.getIntrinsicHeight();
                        image.post(() -> fitToAspect(dw, dh));
                        return false;
                    }
                })
                .into(image);
    }

    /** Reshape the window to the image's aspect ratio, keeping its width and its top-left corner. */
    private void fitToAspect(int imageW, int imageH) {
        if (!added || imageW <= 0 || imageH <= 0) return;
        Point screen = screenSize();
        int bar = dp(BAR_DP);
        int wanted = bar + Math.round(params.width * (float) imageH / imageW);
        params.height = clamp(wanted, dp(MIN_DP), screen.y - params.y);
        wm.updateViewLayout(root, params);
    }

    /** Called when this window goes away, whether by its × or by Close all. */
    void setOnClose(Runnable r) { onClose = r; }

    /** @return true if the window is on screen. A revoked overlay permission throws here. */
    boolean add() {
        if (added) return true;
        try {
            wm.addView(root, params);
            added = true;
        } catch (RuntimeException e) {
            added = false;
        }
        return added;
    }

    void close() {
        if (!added) return;
        added = false;
        try { wm.removeView(root); } catch (IllegalArgumentException ignore) {}
        if (onClose != null) onClose.run();
    }

    /** Pull the window back on screen after a rotation or a display change. */
    void clampIntoScreen() {
        if (!added) return;
        Point screen = screenSize();
        params.width = Math.min(params.width, screen.x);
        params.height = Math.min(params.height, screen.y);
        params.x = Math.max(0, Math.min(params.x, screen.x - params.width));
        params.y = Math.max(0, Math.min(params.y, screen.y - params.height));
        wm.updateViewLayout(root, params);
    }

    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragListener() {
        return new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = params.x;   startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = screenSize();
                        params.x = clamp(startX + (int) (e.getRawX() - downX), 0, screen.x - params.width);
                        params.y = clamp(startY + (int) (e.getRawY() - downY), 0, screen.y - params.height);
                        wm.updateViewLayout(root, params);
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener resizeListener() {
        return new View.OnTouchListener() {
            float downX, downY;
            int startW, startH;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startW = params.width; startH = params.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = screenSize();
                        int min = dp(MIN_DP);
                        params.width = clamp(startW + (int) (e.getRawX() - downX), min, screen.x - params.x);
                        params.height = clamp(startH + (int) (e.getRawY() - downY), min, screen.y - params.y);
                        wm.updateViewLayout(root, params);
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    /**
     * The area a window may occupy. An overlay is laid out <em>inside</em> the system bars, so its
     * x/y are relative to that area — measuring against the raw display would let a window be
     * dragged off the bottom by the height of the status bar.
     */
    private Point screenSize() {
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            Rect b = m.getBounds();
            Insets bars = m.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            p.set(b.width() - bars.left - bars.right, b.height() - bars.top - bars.bottom);
        } else {
            wm.getDefaultDisplay().getSize(p);
        }
        return p;
    }

    private int dp(int v) { return (int) (v * density + 0.5f); }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
