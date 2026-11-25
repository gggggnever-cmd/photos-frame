# Settings redesign (2026-11-25)

The old Settings card was one list of 17 rows in the order they were built, squeezed to fit the screen.
The new one has two levels: a short first page of five groups, and one small page per group.

## Structure

```
Settings                      (▲ on the frame; opens with "What plays" lit)
├─ What plays          ›      shows: All albums, All photos, 10 minutes
│    Albums to play           OK opens the albums list (as before)
│    Photos to show           All photos / Favourites / Places only / Highlights
│      Best photos per trip   only with Highlights
│    Photo order              hidden with Highlights
│      Which day              only with "One day at a time"
│    Change photo every
├─ How it looks        ›      shows: Mat · medium, print look Strong
│    Picture size             Full screen / Wood frame / Mat · thin, medium, wide
│    Print look
│    Style                    (+ the one-line note for Cut-out collage)
├─ TV screen           ›      shows: Brightness Auto (now -40), Warm, off after 30 minutes
│    TV picture for photos    Leave as is / Photo preset
│      Brightness             only with Photo preset
│      Colour                 only with Photo preset
│    TV off if remote unused
├─ Photos on the TV    ›      shows: 3 hidden, space 20 GB
│    Add more photos          OK
│    Hidden photos            OK brings them back
│    Space for photos
└─ YouTube corner             OK shows the phone code (no page of its own: it is one action)
```

## Keys

- First page: ▲▼ move, OK (or ▶) opens the group, Back closes Settings.
- A group's page: ▲▼ move, ◀▶ change, OK acts (or steps the value on), Back returns to the first page
  with that group still lit. Back again closes. The albums list still returns to "What plays".
- The title reads "Settings › What plays" so it is clear where Back leads. Key hints moved under the
  rows (as on the albums card) so the title has room.
- A held OK or Back no longer repeats (it would open a page and then act on its first row). Held ▲▼◀▶
  still repeat.

## Why

- Five groups answer five plain questions: what plays, how it looks, the TV itself, the photos stored
  on the TV, the music corner. Each setting has one obvious home.
- Each group's row shows what it is set to, so the first page doubles as an overview; often nothing
  needs opening.
- The most-used group is first and lit on opening: ▲, OK lands on "Albums to play".
- Pages instead of one scrolling list: at most 5 rows a page, so nothing scrolls off, the furthest
  setting is about 7 presses away (was up to 16), and rows are back to full size (18 sp, 10 dp
  padding; summaries 15 sp). Every page has the same height, so the card does not jump. The card is
  about 330 dp tall on the 540 dp screen, 640 dp wide.
- Rows that depend on the row above are set in a step, instead of three leading spaces.

## Wording

Row names and values are unchanged (the owner approved them in v0.15). New words:

| Old | New |
|---|---|
| (no groups) | What plays · How it looks · TV screen · Photos on the TV |
| Key hint "Back Close" on every row | "Back Close" on the first page, "Back Settings" on a group's page |
| "   Which day" etc. (spaces) | "Which day" set in a step |

## Unchanged

Prefs keys, value lists and indices; when `settingsChanged()` is called; TV picture rows call
`TvPicture.frameOn`; auto-off saves without redrawing the frame; the conditions for Which day, Best
photos per trip, Photo order, Brightness and Colour.

## Code

- `SettingsPanel.java`: rows carry a page number (`PAGE_OF`); `shown()` first checks the page, then
  the old conditions. `show(page, focus)` switches page, title and key hints.
- `MainActivity.java`: passes "key is repeating" to `panel.onKey`; `onBackPressed` sends Back to the
  panel instead of closing it outright.
