package com.gary.photosframe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Android switches the art-button helper (ArtKeyService) off whenever the app is force-stopped, and
 * apps can't prevent that (seen 2026-11-20 after the TV was wall-mounted). So when the frame or the
 * screensaver opens and the helper is off, a line at the bottom says so for 10 seconds, at most once a
 * day; pressing OK during those seconds opens the TV's Accessibility settings. Only the owner can switch
 * the helper back on there. Never shown over other apps or TV channels.
 */
final class ArtKeyReminder {
    static final long SHOW_MS = 10_000;
    private static final String PREFS = "art_key_reminder", DAY = "shown_day";

    static boolean helperOn(Context ctx) {
        AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return true;
        List<AccessibilityServiceInfo> on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : on) {
            if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                    && ctx.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    /** True if the reminder should show now (helper off, not yet shown today). Marks today as used. */
    static boolean due(Context ctx, boolean force) {
        if (!force && helperOn(ctx)) return false;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!force && today.equals(p.getString(DAY, ""))) return false;
        p.edit().putString(DAY, today).apply();
        return true;
    }

    /** Adds the reminder line to the bottom of root and fades it out after SHOW_MS. */
    static TextView show(Context ctx, FrameLayout root) {
        TextView t = Ui.text(ctx, "The art button opens T-Exhibition again  ·  press OK to fix it", 18, Ui.INK);
        Ui.pill(t);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(ctx, 28);
        root.addView(t, lp);
        // Fades in, stays, fades out; gone from the screen SHOW_MS after it appeared, as before.
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(300).withEndAction(() ->
                t.animate().alpha(0f).setStartDelay(SHOW_MS - 300).setDuration(800)
                        .withEndAction(() -> root.removeView(t)));
        return t;
    }

    /** Opens the helper's own settings page if the TV allows it, otherwise the Accessibility list. */
    static void openSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(ctx, ArtKeyService.class).flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception e) {
            Log.i("ArtKeyReminder", "details page not allowed, opening the list: " + e);
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w("ArtKeyReminder", "no accessibility settings page: " + e);
        }
    }
}
