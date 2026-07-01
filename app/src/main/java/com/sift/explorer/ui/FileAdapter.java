package com.sift.explorer.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.sift.explorer.R;
import com.sift.explorer.fs.FileItem;
import com.sift.explorer.util.MimeUtils;
import com.sift.explorer.util.Thumbs;
import com.sift.explorer.util.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface Listener {
        void onItemClick(FileItem item, int position);
        void onItemLongClick(FileItem item, int position);
        void onItemMenu(FileItem item, View anchor, int position);
    }

    private static final int TYPE_LIST = 0, TYPE_GRID = 1;

    private final Context ctx;
    private final Listener listener;
    private final List<FileItem> items = new ArrayList<>();
    private final Set<FileItem> selected = new LinkedHashSet<>();
    private boolean grid = false;
    private boolean selectionMode = false;

    public FileAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        setHasStableIds(false);
    }

    public void setItems(List<FileItem> newItems, boolean grid) {
        this.grid = grid;
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setGrid(boolean g) { if (grid != g) { grid = g; notifyDataSetChanged(); } }
    public boolean isGrid() { return grid; }

    public boolean isSelectionMode() { return selectionMode; }
    public void setSelectionMode(boolean on) {
        selectionMode = on;
        if (!on) selected.clear();
        notifyDataSetChanged();
    }

    public void toggle(FileItem item) {
        if (selected.contains(item)) selected.remove(item); else selected.add(item);
        int idx = items.indexOf(item);
        if (idx >= 0) notifyItemChanged(idx);
    }

    public void selectAll() { selected.clear(); selected.addAll(items); notifyDataSetChanged(); }
    public void clearSelection() { selected.clear(); notifyDataSetChanged(); }
    public int selectedCount() { return selected.size(); }
    public boolean isSelected(FileItem i) { return selected.contains(i); }
    public List<FileItem> getSelected() { return new ArrayList<>(selected); }

    @Override public int getItemViewType(int position) { return grid ? TYPE_GRID : TYPE_LIST; }
    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_GRID ? R.layout.grid_file : R.layout.row_file;
        View v = LayoutInflater.from(ctx).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int position) {
        FileItem item = items.get(position);
        h.title.setText(item.name);

        if (h.subtitle != null) {
            if (item.isDirectory) {
                h.subtitle.setText(Utils.formatDate(item.lastModified));
            } else {
                String size = Utils.formatSize(ctx, item.size);
                String date = Utils.formatDate(item.lastModified);
                h.subtitle.setText(date.isEmpty() ? size : size + "  ·  " + date);
            }
        }

        // base icon
        h.icon.setImageResource(MimeUtils.iconFor(item));
        h.icon.setVisibility(View.VISIBLE);
        h.thumb.setVisibility(View.GONE);
        h.thumb.setImageDrawable(null);
        Glide.with(ctx).clear(h.thumb);

        // thumbnails for local content
        File local = item.asLocalFile();
        if (local != null) {
            if (MimeUtils.isImage(item) || MimeUtils.isVideo(item)) {
                h.thumb.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(local).centerCrop()
                        .placeholder(MimeUtils.iconFor(item))
                        .into(h.thumb);
            } else if (item.isDirectory) {
                int px = grid ? dp(120) : dp(44);
                Thumbs.loadFolderPreview(local, px, h.thumb);
            }
        }

        boolean sel = selected.contains(item);
        if (h.check != null) h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
        h.itemView.setActivated(sel);
        if (h.symlink != null) h.symlink.setVisibility(item.isSymlink ? View.VISIBLE : View.GONE);

        if (h.overflow != null) {
            h.overflow.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            h.overflow.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onItemMenu(items.get(p), v, p);
            });
        }

        h.itemView.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onItemClick(items.get(p), p);
        });
        h.itemView.setOnLongClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) listener.onItemLongClick(items.get(p), p);
            return true;
        });
    }

    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        Glide.with(ctx).clear(h.thumb);
        h.thumb.setTag(null);
    }

    private int dp(int d) { return Math.round(ctx.getResources().getDisplayMetrics().density * d); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageView icon;
        final ImageView thumb;
        final ImageView check;
        final ImageView symlink;
        final ImageView overflow;
        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            icon = v.findViewById(R.id.icon);
            thumb = v.findViewById(R.id.thumb);
            check = v.findViewById(R.id.check);
            symlink = v.findViewById(R.id.symlink);
            overflow = v.findViewById(R.id.overflow);
        }
    }
}
