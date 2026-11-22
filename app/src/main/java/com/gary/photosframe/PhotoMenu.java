package com.gary.photosframe;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The small menu opened by holding OK on the frame: favourite or hide the photo on screen.
 * With 2-3 photos side by side, ◀ ▶ chooses which one (it is outlined on screen).
 * ▲▼ choose an action, OK does it, Back closes.
 */
final class PhotoMenu extends LinearLayout {
    interface Host {
        boolean isFavourite(String id);
        void favourite(String id);
        void hide(String id);
        Boolean isPlace(String id);         // true: shows in Places only; false: has people; null: not checked yet
        void togglePlace(String id);
        void highlight(int index);
        String tripLabel(String id);        // "Nov 2025 · 12 days", or null
        void renameTripOf(String id);
        void closeMenu();
    }

    private static final int ROW_PHOTO = 0, ROW_FAV = 1, ROW_HIDE = 2, ROW_PLACE = 3, ROW_TRIP = 4, ROW_CANCEL = 5, ROWS = 6;
    private static final String[] POSITIONS_2 = {"Left", "Right"}, POSITIONS_3 = {"Left", "Middle", "Right"};

    private final Host host;
    private final LinearLayout[] rows = new LinearLayout[ROWS];
    private final TextView[] values = new TextView[ROWS];
    private final View keysOne, keysMany;   // the remote's hints: for one photo, and for 2-3 side by side
    private List<String> photos;
    private int chosen, focus;

    PhotoMenu(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(28), dp(24), dp(28), dp(22));
        setBackground(Ui.card(ctx));
        addView(Ui.title(ctx, "This photo", Ui.TITLE));
        addView(Ui.gap(ctx, 12));
        String[] names = {"Which photo", "Favourite", "Hide this photo", "In \"Places only\"", "Name this trip", "Close"};
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            TextView label = Ui.text(ctx, names[i], Ui.ROW, Ui.INK);
            values[i] = Ui.text(ctx, "", Ui.ROW, Ui.SOFT);
            row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(values[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            rows[i] = row;
            addView(row);
        }
        addView(Ui.gap(ctx, 14));
        keysOne = Ui.keys(ctx, "▲▼", "Move", "OK", "Choose", "Back", "Close");
        keysMany = Ui.keys(ctx, "◀ ▶", "Photo", "▲▼", "Move", "OK", "Choose", "Back", "Close");
        addView(keysOne);
        addView(keysMany);
    }

    int cardWidth() { return dp(520); }

    /** Every way of showing the card (open, coming back from another card) fades it in. */
    @Override public void setVisibility(int visibility) {
        boolean was = getVisibility() == VISIBLE;
        super.setVisibility(visibility);
        if (visibility == VISIBLE && !was) Ui.fadeIn(this);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens for the photos on screen (left to right). Does nothing if there are none. */
    void open(List<String> onScreen) {
        if (onScreen.isEmpty()) return;
        photos = onScreen;
        chosen = 0;
        focus = ROW_FAV;
        rows[ROW_PHOTO].setVisibility(photos.size() > 1 ? VISIBLE : GONE);
        keysMany.setVisibility(photos.size() > 1 ? VISIBLE : GONE);
        keysOne.setVisibility(photos.size() > 1 ? GONE : VISIBLE);
        setVisibility(VISIBLE);
        refresh();
    }

    void close() {
        setVisibility(GONE);
        host.highlight(-1);
    }

    boolean onKey(int code) {
        int first = photos.size() > 1 ? ROW_PHOTO : ROW_FAV;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(first, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(ROW_CANCEL, focus + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (photos.size() > 1) chosen = Math.max(0, chosen - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (photos.size() > 1) chosen = Math.min(photos.size() - 1, chosen + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                String id = photos.get(chosen);
                if (focus == ROW_FAV) host.favourite(id);
                else if (focus == ROW_HIDE) host.hide(id);
                else if (focus == ROW_PLACE) host.togglePlace(id);
                host.closeMenu();
                if (focus == ROW_TRIP) host.renameTripOf(id);
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                host.closeMenu();
                return true;
        }
        refresh();
        return true;
    }

    private void refresh() {
        String[] pos = photos.size() == 2 ? POSITIONS_2 : POSITIONS_3;
        values[ROW_PHOTO].setText(photos.size() > 1
                ? Ui.arrows(pos[Math.min(chosen, pos.length - 1)], chosen > 0, chosen < photos.size() - 1) : "");
        boolean fav = host.isFavourite(photos.get(chosen));
        values[ROW_FAV].setText(fav ? "Yes  ·  OK to remove" : "No  ·  OK to add");
        Boolean place = host.isPlace(photos.get(chosen));
        values[ROW_PLACE].setText(place == null ? "not sorted yet" : place ? "Yes" : "No");
        String trip = host.tripLabel(photos.get(chosen));
        values[ROW_TRIP].setText(trip == null ? "" : trip.replace("  ·  ", " · "));
        for (int i = 0; i < ROWS; i++) {
            Ui.focus(rows[i], i == focus);
            values[i].setTextColor(i == focus ? Ui.ACCENT : Ui.SOFT);
        }
        host.highlight(photos.size() > 1 ? chosen : -1);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
