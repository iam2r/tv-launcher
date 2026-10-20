# Shelf Home

`shelf_mode` (on by default in this fork) turns Home into one row of nest slots.

## Layout

- Each slot is a **150×84dp card**, 15dp corners, with a 16sp name underneath at 70 % white; focused, the
  name goes full white and the card gets a 3dp white ring and a 1.06 zoom (no overshoot, no blur).
- The row is parked near the bottom of the screen by a `weight=1` spacer above the grid, not by the grid's
  top padding: a padding applied in `onLayoutChange` only exists from the second layout pass, which showed
  as the row sitting under the clock for a moment at startup and then dropping into place.
- An empty slot is a dashed card with a `+`; pressing it opens the picker for that slot.
- `slot_count` (1–8) is how many slots exist. `dock_apps` seeds them the first time; after that
  `home_slots.txt` in the app's private files directory is the source of truth (one package per line, an
  empty line for an empty slot).

## Keys

| Key | On a slot | In the drawer |
|---|---|---|
| OK | open the app, or the picker on an empty slot | open / pick / enter the system-apps folder |
| MENU | picker for this slot | app actions (Uninstall; nothing for system apps) |
| Up | the toolbar (Wi-Fi, Settings, Inputs, File share) | from the first row: leave the folder, then the drawer |
| Down | the drawer | next row |
| Left/Right | move between slots | wrap to the neighbouring row |

## Icon pipeline (`SlotCard`)

The order matters; it is what makes a row of unrelated apps look like a set:

1. **TV banner** — if the app ships `android:banner`, the banner *is* the card (16:9 artwork, drawn
   `centerCrop`). Measured against Dangbei 4.1.6 on the same TV: their tile and the app's own banner differ
   by a mean 5.9/255 per channel, so this is exactly what they do.
2. **Adaptive icon layers** — the background layer becomes the card, the foreground layer (a transparent,
   line-art logo) is drawn on top. Read from the app's own resources, so it works on Android 8+, not just
   10+ where `AdaptiveIconDrawable` exposes its layers.
3. **Lifted ground** — a plain "coloured square with a logo" icon: the border colour is sampled, pixels of
   that colour made transparent, and the card painted in it.
4. **The icon itself** — anything else (a photo-like icon) fills the card instead of sitting in a box.

Everything is computed once per package on a 112 px bitmap and cached (64 entries), so scrolling and
reopening the drawer cost nothing.
