package com.sift.explorer.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.sift.explorer.R;
import com.sift.explorer.fs.FileItem;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Swipeable, pinch-zoomable gallery of the images in a folder. */
public class ImageViewerActivity extends AppCompatActivity {

    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    private ViewPager2 pager;
    private MaterialToolbar toolbar;
    private List<FileItem> items;

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, ImageViewerActivity.class));
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_image_viewer);
        items = ImageGallery.items;
        if (items == null || items.isEmpty()) { finish(); return; }
        int start = Math.max(0, Math.min(ImageGallery.startIndex, items.size() - 1));

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.viewer_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share) { shareCurrent(); return true; }
            return false;
        });

        pager = findViewById(R.id.pager);
        pager.setAdapter(new Adapter());
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateTitle(position); }
        });
        pager.setCurrentItem(start, false);
        updateTitle(start);
    }

    private void updateTitle(int pos) {
        FileItem it = items.get(pos);
        toolbar.setTitle(it.name);
        toolbar.setSubtitle((pos + 1) + " / " + items.size());
    }

    private File cacheFileFor(FileItem item) {
        File dir = new File(getCacheDir(), "imgview");
        dir.mkdirs();
        return new File(dir, Integer.toHexString(item.path.hashCode()) + "_" + item.name);
    }

    private void download(FileItem item, File dest) throws Exception {
        InputStream in = item.fs.read(item);
        OutputStream out = new java.io.FileOutputStream(dest);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        } finally { in.close(); out.close(); }
    }

    private void shareCurrent() {
        final FileItem item = items.get(pager.getCurrentItem());
        exec.execute(() -> {
            try {
                File f = item.asLocalFile();
                if (f == null) { f = cacheFileFor(item); if (!f.exists()) download(item, f); }
                final File file = f;
                main.post(() -> {
                    if (destroyed) return;
                    try {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("image/*");
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Share"));
                    } catch (Exception ignore) {}
                });
            } catch (Exception ignore) {}
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        exec.shutdownNow();
    }

    private void bind(VH h, FileItem item) {
        h.image.setTag(item.path);
        File local = item.asLocalFile();
        if (local != null) {
            h.loading.setVisibility(View.GONE);
            Glide.with(h.image).load(local).into(h.image);
            return;
        }
        File cache = cacheFileFor(item);
        if (cache.exists()) {
            h.loading.setVisibility(View.GONE);
            Glide.with(h.image).load(cache).into(h.image);
            return;
        }
        h.loading.setVisibility(View.VISIBLE);
        exec.execute(() -> {
            try {
                download(item, cache);
                main.post(() -> {
                    if (destroyed || !item.path.equals(h.image.getTag())) return;
                    h.loading.setVisibility(View.GONE);
                    Glide.with(h.image).load(cache).into(h.image);
                });
            } catch (Exception e) {
                main.post(() -> { if (!destroyed) h.loading.setVisibility(View.GONE); });
            }
        });
    }

    private class Adapter extends RecyclerView.Adapter<VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.image_page, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) { bind(h, items.get(pos)); }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onViewRecycled(@NonNull VH h) { Glide.with(h.image).clear(h.image); }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ZoomImageView image;
        final View loading;
        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.image);
            loading = v.findViewById(R.id.loading);
        }
    }
}
