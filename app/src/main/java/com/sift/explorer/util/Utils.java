package com.sift.explorer.util;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Utils {

    public static String formatSize(Context ctx, long bytes) {
        if (bytes < 0) return "";
        return Formatter.formatShortFileSize(ctx, bytes);
    }

    public static String formatDate(long millis) {
        if (millis <= 0) return "";
        long now = System.currentTimeMillis();
        if (now - millis < DateUtils.WEEK_IN_MILLIS && millis <= now) {
            return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new Date(millis));
    }

    public static String formatDateFull(long millis) {
        if (millis <= 0) return "Unknown";
        return new SimpleDateFormat("EEE, MMM d yyyy  HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** Cheap pluraliser. */
    public static String plural(int n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }
}
