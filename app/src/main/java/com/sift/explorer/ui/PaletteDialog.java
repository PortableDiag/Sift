package com.sift.explorer.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sift.explorer.util.Palettes;
import com.sift.explorer.util.ThemePrefs;

/** The house palettes, in the picker's order, each with a swatch of its background and accent. */
public final class PaletteDialog {

    public interface Callback { void onChosen(String name); }

    private PaletteDialog() {}

    public static void show(Activity act, String current, Callback cb) {
        final Palettes.Palette[] all = Palettes.ALL;
        int checked = 0;
        for (int i = 0; i < all.length; i++) if (all[i].name.equals(current)) checked = i;

        ArrayAdapter<Palettes.Palette> adapter = new ArrayAdapter<Palettes.Palette>(
                act, android.R.layout.simple_list_item_single_choice, all) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                Palettes.Palette p = all[pos];
                String suffix = p.followsMode ? "" : p.light ? "  · light" : "";
                tv.setText(p.label + suffix);
                tv.setTypeface(p.mono ? android.graphics.Typeface.MONOSPACE : android.graphics.Typeface.DEFAULT);
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(act, p), null, null, null);
                tv.setCompoundDrawablePadding(dp(act, 16));
                return tv;
            }
        };

        new MaterialAlertDialogBuilder(act)
                .setTitle("Theme")
                .setSingleChoiceItems(adapter, checked, (d, which) -> {
                    d.dismiss();
                    cb.onChosen(all[which].name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A disc of the palette's background with its accent inside; the current theme's for Material You. */
    private static android.graphics.drawable.Drawable swatch(Activity act, Palettes.Palette p) {
        int bg = p.isDynamic() ? ThemePrefs.themeColor(act, com.google.android.material.R.attr.colorSurfaceVariant) : p.background;
        int fg = p.isDynamic() ? ThemePrefs.themeColor(act, androidx.appcompat.R.attr.colorPrimary) : p.accent;
        int outline = ThemePrefs.themeColor(act, com.google.android.material.R.attr.colorOutline);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(bg);
        outer.setStroke(dp(act, 1), outline);
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(fg);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, inner});
        int size = dp(act, 28), inset = dp(act, 9);
        ld.setLayerInset(1, inset, inset, inset, inset);
        ld.setBounds(0, 0, size, size);
        outer.setSize(size, size);
        return ld;
    }

    private static int dp(Activity a, int d) {
        return Math.round(d * a.getResources().getDisplayMetrics().density);
    }
}
