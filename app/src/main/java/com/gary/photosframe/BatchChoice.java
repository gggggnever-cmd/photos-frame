package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The one screen after each pick (2026-11-22): where does this batch go? The first row is the default
 * (OK or Back accepts it): the album or trip the photos' dates overlap, or a new album by date. Then
 * "Name this album" (when the default has no name yet), "New album…" (type a name; the whole batch
 * becomes a membership album), and the membership albums to add the batch to.
 * ▲▼ move, OK chooses; typing uses the TV keyboard (it has a mic), OK saves, Back returns to the choices.
 */
final class BatchChoice extends LinearLayout {
    static final int DEFAULT = 0, NAME = 1, NEW = 2, ALBUM = 3;
    private static final int VISIBLE_ROWS = 4;

    static final class Option {
        final int kind;
        final String label, info, albumId, name;   // albumId/name: the album this row adds to, if any
        Option(int kind, String label, String info, String albumId, String name) {
            this.kind = kind; this.label = label; this.info = info; this.albumId = albumId; this.name = name;
        }
    }

    interface Listener { void chosen(Option o, String typedName); }

    private final Listener listener;
    private final LinearLayout coverRow, list;
    private final ScrollView scroll;
    private final TextView heading, hint;
    private final EditText field;
    private final View keysList, keysType;
    private final List<Option> options = new ArrayList<>();
    private final List<LinearLayout> rows = new ArrayList<>();
    private int focus;
    private Option typing;   // the row whose name is being typed, or null while choosing

    BatchChoice(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        setOrientation(VERTICAL);
        coverRow = new LinearLayout(ctx);
        coverRow.setOrientation(HORIZONTAL);
        addView(coverRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(72)));
        addView(Ui.gap(ctx, 12));
        heading = Ui.text(ctx, "", Ui.BODY, Ui.SOFT);
        addView(heading);
        addView(Ui.gap(ctx, 10));
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);   // rows fade at the edges: there is more to scroll to
        scroll.setFadingEdgeLength(dp(24));
        list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(6), dp(2), dp(6), dp(2));   // room for the focused row to grow
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        field = new EditText(ctx);
        field.setSingleLine(true);
        Ui.field(field);                         // a light box with dark text
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, ev) -> { save(); return true; });
        field.setVisibility(GONE);
        addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(Ui.gap(ctx, 10));
        hint = Ui.text(ctx, "", Ui.HINT, Ui.SOFT);
        hint.setLineSpacing(0, 1.3f);
        addView(hint);
        addView(Ui.gap(ctx, 12));
        keysList = Ui.keys(ctx, "▲▼", "Move", "OK", "Choose", "Back", "Use the first");
        keysType = Ui.keys(ctx, "OK", "Save", "Back", "Other choices");
        addView(keysList);
        addView(keysType);
        setVisibility(GONE);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Shows the choices for one batch; the first option is the default. */
    void open(String headingText, List<Bitmap> covers, List<Option> opts) {
        heading.setText(headingText);
        coverRow.removeAllViews();
        for (Bitmap bmp : covers) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.shape(getContext(), 0x14F1EDE4, 10));   // rounded corners: the picture is clipped to this shape
            iv.setClipToOutline(true);
            LayoutParams lp = new LayoutParams(dp(96), dp(72));
            lp.rightMargin = dp(8);
            coverRow.addView(iv, lp);
        }
        coverRow.setVisibility(covers.isEmpty() ? GONE : VISIBLE);
        options.clear();
        options.addAll(opts);
        list.removeAllViews();
        rows.clear();
        for (Option o : options) addRow(o);
        // Up to four rows show at once; more scroll.
        scroll.getLayoutParams().height = options.size() > VISIBLE_ROWS ? dp(VISIBLE_ROWS * 44) : LayoutParams.WRAP_CONTENT;
        focus = 0;
        choosing();
        setVisibility(VISIBLE);
        Ui.fadeIn(this);
    }

    /** A remote key. Returns true if used; while typing, the text box gets the keys (OK saves). */
    boolean onKey(int code) {
        if (typing != null) {
            if (code == KeyEvent.KEYCODE_ENTER) { save(); return true; }
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(0, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(options.size() - 1, focus + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: pick(options.get(focus)); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
            default: return false;
        }
        refresh();
        return true;
    }

    /** Back: while typing, return to the choices; otherwise take the default. */
    void back() {
        if (typing != null) choosing();
        else finish(options.get(0), null);
    }

    private void pick(Option o) {
        if (o.kind == NAME || o.kind == NEW) typeName(o);
        else finish(o, null);
    }

    private void choosing() {
        typing = null;
        hideKeyboard();
        field.setVisibility(GONE);
        scroll.setVisibility(VISIBLE);
        keysList.setVisibility(VISIBLE);
        keysType.setVisibility(GONE);
        hint.setText("They download in the background. A new album keeps these photos together.");
        refresh();
    }

    private void typeName(Option o) {
        typing = o;
        scroll.setVisibility(GONE);
        keysList.setVisibility(GONE);
        keysType.setVisibility(VISIBLE);
        field.setText("");
        field.setHint(o.kind == NEW ? "Album name, e.g. Mom & Dad" : "Name, e.g. Egypt");
        hint.setText(o.kind == NEW ? "Say or type the new album's name and press OK. All these photos go into it."
                : "Say or type a name and press OK.");
        field.setVisibility(VISIBLE);
        field.requestFocus();
        postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    /** OK in the text box. An empty name goes back to the choices rather than making a nameless album. */
    private void save() {
        if (typing == null) return;
        String name = field.getText().toString().trim();
        if (name.isEmpty()) { choosing(); return; }
        finish(typing, name);
    }

    private void finish(Option o, String name) {
        hideKeyboard();
        typing = null;
        field.clearFocus();
        setVisibility(GONE);
        listener.chosen(o, name);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void addRow(Option o) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        row.addView(Ui.text(getContext(), o.label, Ui.ROW, Ui.INK), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView info = Ui.text(getContext(), o.info, 15, Ui.SOFT);
        LayoutParams ip = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ip.leftMargin = dp(12);
        row.addView(info, ip);
        rows.add(row);
        list.addView(row);
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) Ui.focus(rows.get(i), i == focus);
        if (rows.isEmpty()) return;
        View f = rows.get(focus);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, f.getTop() - dp(44))));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
