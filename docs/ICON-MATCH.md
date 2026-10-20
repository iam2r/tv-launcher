# Matching the reference launcher's cards

The reference is 当贝桌面 4.1.6 on this TV (1080p, Android 9). Its cards were measured off screenshots,
pixel by pixel; no file or asset of it is used here. Card geometry there is 300x168 px with 15 dp corners,
the same as our `150x84dp` card.

## How the reference fills a card

Measured on its "all apps" grid and on its system-apps grid:

| icon the app ships | what the reference paints |
| --- | --- |
| a TV banner | the banner, full bleed |
| an adaptive icon | the background layer as the card, the foreground drawn as a logo on top |
| a plain icon | the whole icon, small, centred on a card (white for TvProxySet, whose icon is dark) |
| a glyph on transparency (most system apps) | a card in the icon's own average colour, the glyph on top |

## Colours, measured against ours

Card corner colour read from the reference and from this launcher, same app, same screen:

| app | reference | before | after |
| --- | --- | --- | --- |
| 相机 | `(79,142,245)` | `(74,115,181)` | `(88,147,244)` |
| 媒体中心 | `(183,77,251)` | `(124,68,181)` | `(160,78,244)` |
| 屏保画册 | `(217,168,198)` | `(142,120,181)` | `(177,146,229)` |
| 系统设置 | `(53,78,90)` | `(144,157,172)` | `(139,154,170)` |
| SystemInfo | `(255,255,255)` | `(150,180,67)` | `(154,188,61)` |

The colour is the icon's own alpha-weighted average, with saturation pushed and the value held inside a
readable band (`SlotCard.tint`). Two apps still differ, and both are the reference's own doing rather than a
missing rule here: it draws SystemInfo on white although that icon is olive, and it darkens the grey
Settings icon to slate, so its per-app colours are not a pure function of the installed icon.

## What is deliberately not copied

Nothing is decompiled into this repository: the reference is hardened with 360 jiagu and its drawables are
its own. The pipeline above rebuilds the *result* from each app's own resources, so every card here is
generated from the app it belongs to.
