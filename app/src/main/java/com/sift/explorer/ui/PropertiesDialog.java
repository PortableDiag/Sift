package com.sift.explorer.ui;

import android.content.Context;
import android.os.Handler;
import android.text.format.Formatter;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sift.explorer.fs.FileItem;
import com.sift.explorer.util.Utils;

import java.util.List;
import java.util.concurrent.ExecutorService;

/** Shows name/path/size/modified/permissions; folder sizes are summed in the background. */
public class PropertiesDialog {

    public static void show(Context ctx, List<FileItem> items, ExecutorService io, Handler main) {
        final TextView tv = new TextView(ctx);
        int pad = Math.round(ctx.getResources().getDisplayMetrics().density * 20);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);

        StringBuilder sb = new StringBuilder();
        if (items.size() == 1) {
            FileItem it = items.get(0);
            sb.append("Name\n").append(it.name).append("\n\n");
            sb.append("Path\n").append(it.path).append("\n\n");
            sb.append("Type\n").append(it.isDirectory ? "Folder" : (it.getExtension().isEmpty()
                    ? "File" : it.getExtension().toUpperCase() + " file")).append("\n\n");
            if (it.isSymlink) sb.append("Link\nSymbolic link\n\n");
            sb.append("Modified\n").append(Utils.formatDateFull(it.lastModified)).append("\n\n");
            if (it.permissions != null) sb.append("Permissions\n").append(it.permissions).append("\n\n");
            sb.append("Size\n").append(it.isDirectory ? "calculating…"
                    : Formatter.formatFileSize(ctx, it.size));
        } else {
            sb.append(items.size()).append(" items selected\n\nTotal size\ncalculating…");
        }
        tv.setText(sb.toString());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle("Properties")
                .setView(tv)
                .setPositiveButton("Close", null)
                .show();

        io.execute(() -> {
            final long total = sumSize(items);
            final int[] counts = countTree(items);
            main.post(() -> {
                if (!dialog.isShowing()) return;
                String sizeStr = Formatter.formatFileSize(ctx, total);
                String text = tv.getText().toString().replace("calculating…", sizeStr);
                if (items.size() > 1 || items.get(0).isDirectory) {
                    text += "\n\nContents\n" + Utils.plural(counts[0], "file")
                            + ", " + Utils.plural(counts[1], "folder");
                }
                tv.setText(text);
            });
        });
    }

    private static long sumSize(List<FileItem> items) {
        long t = 0;
        for (FileItem i : items) t += i.isDirectory ? dirSize(i) : i.size;
        return t;
    }

    private static long dirSize(FileItem dir) {
        long t = 0;
        try { for (FileItem k : dir.fs.list(dir.path)) t += k.isDirectory ? dirSize(k) : k.size; }
        catch (Exception ignore) {}
        return t;
    }

    private static int[] countTree(List<FileItem> items) {
        int files = 0, folders = 0;
        for (FileItem i : items) {
            if (i.isDirectory) {
                folders++;
                try { int[] sub = countTree(i.fs.list(i.path)); files += sub[0]; folders += sub[1]; }
                catch (Exception ignore) {}
            } else files++;
        }
        return new int[]{files, folders};
    }
}
