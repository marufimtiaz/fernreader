# FernReader 0.1.0 — release notes

First public release. An offline EPUB reader for Android: no account, no network permission, no tracking.
MIT licence (`LICENSE`, third-party licences in `docs/licenses.md` and in the app under About).

## Known issues

- **"≈" page estimate in the screen-page modes (C-016).** Right after opening a book, or after a layout change, the
  screen-page indicator shows an estimate "≈ N" until the exact total is counted. For most books it is within the
  ±5 % the spec asks for; two corpus books are accepted exceptions for this release: *JLREQ (Requirements for
  Japanese Text Layout)*, a large Japanese document, is off by about 33 % (exact 792–805 pages, estimates measured
  31.6–33.8 %), and *Linear Algebra* (MathML-heavy) by about 6–9 % (exact 1,051 pages, 5.99–9.04 %). The exact total
  replaces the estimate once counting finishes. Book pages, the default numbering (C-083), do not use the estimate.
- **Text-selection menu without icons (C-086).** Android draws the app's selection menu (Highlight, Note, Copy, …)
  as text only; icons there would need our own selection toolbar.
- **Real-device pass.** Smoothness and some checks are confirmed on a real phone before the store release
  (`docs/real-device-pass.md`).
