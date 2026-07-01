package com.sift.explorer.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds 2x2 collage previews for local folders that contain images, off the
 * main thread, with an in-memory LRU cache. Glide handles single-file image and
 * video thumbnails directly in the adapter.
 */
public class Thumbs {

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };

    private static final String[] IMG_EXT = {"jpg","jpeg","png","webp","gif","bmp","heic"};

    /** Loads (or builds) a folder collage into {@code iv}; falls back to nothing if none. */
    public static void loadFolderPreview(File dir, int sizePx, ImageView iv) {
        final String key = dir.getAbsolutePath() + "@" + dir.lastModified() + "#" + sizePx;
        iv.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) { iv.setImageBitmap(cached); iv.setVisibility(ImageView.VISIBLE); return; }
        EXEC.execute(() -> {
            Bitmap bmp = buildCollage(dir, sizePx);
            if (bmp != null) {
                CACHE.put(key, bmp);
                MAIN.post(() -> {
                    if (key.equals(iv.getTag())) {
                        iv.setImageBitmap(bmp);
                        iv.setVisibility(ImageView.VISIBLE);
                    }
                });
            }
        });
    }

    private static Bitmap buildCollage(File dir, int size) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<File> imgs = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && isImage(f.getName())) { imgs.add(f); if (imgs.size() == 4) break; }
        }
        if (imgs.isEmpty()) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(Color.parseColor("#22000000"));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (imgs.size() == 1) {
            drawInto(c, imgs.get(0), new Rect(0, 0, size, size), p);
        } else {
            int half = size / 2;
            int gap = 1;
            Rect[] cells = {
                    new Rect(0, 0, half - gap, half - gap),
                    new Rect(half + gap, 0, size, half - gap),
                    new Rect(0, half + gap, half - gap, size),
                    new Rect(half + gap, half + gap, size, size)
            };
            for (int i = 0; i < cells.length; i++) {
                File f = imgs.get(i % imgs.size());
                drawInto(c, f, cells[i], p);
            }
        }
        return out;
    }

    private static void drawInto(Canvas c, File f, Rect dst, Paint p) {
        try {
            int w = dst.width(), h = dst.height();
            Bitmap full = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts(f, w, h));
            if (full == null) return;
            Bitmap sq = ThumbnailUtils.extractThumbnail(full, w, h);
            c.drawBitmap(sq, null, dst, p);
            if (sq != full) full.recycle();
        } catch (Throwable ignore) {}
    }

    private static android.graphics.BitmapFactory.Options opts(File f, int w, int h) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        int reqW = Math.max(1, w), reqH = Math.max(1, h);
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        android.graphics.BitmapFactory.Options out = new android.graphics.BitmapFactory.Options();
        out.inSampleSize = sample;
        return out;
    }

    private static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String e = name.substring(dot + 1).toLowerCase();
        for (String x : IMG_EXT) if (x.equals(e)) return true;
        return false;
    }
}
