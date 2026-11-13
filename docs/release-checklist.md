# FernReader release checklist (first public release)

Tick every box before publishing anywhere. Items marked **(user)** need a person: an account, a payment, a legal
judgement or a real phone. Spec references are clarification ids in the spec repo.

## 1. Code and benchmarks

- [ ] `master` is green: `./gradlew check` (unit tests, lint, `checkFdroidClean`) and the full instrumented suite
      (`docs/milestones/M9.md` → "Final release runs" has the commands).
- [ ] **Two consecutive green** `bench/run_all.sh all --release` runs on the release candidate commit, on a quiet host
      (C-015, C-060): Gradle stopped (`./gradlew --stop`), no device-stream process (`pgrep -a ffmpeg` empty, C-028),
      `/tmp/epub-reader-bench-running` set while running. Benchmark APK of that commit installed
      (`./gradlew :app:installPlayBenchmark`), corpus pushed (`bench/push_corpus.sh`), `AD` exported. A group that
      fails is re-run once before acting (bench/README.md); P-002 under host load follows C-039. Paste both gate
      outputs into the milestone note.
- [ ] Release-only checks green (M9 part B): P-014, P-023, P-044, P-061, P-062 in the release runs; P-063 scroll and
      the corpus-wide page-count check (`CorpusPageCountTest`) recorded.
- [ ] **Power check green** on the release candidate commit (C-114): `bash tools/power/check.sh` on the emulator, quiet
      host, device lock free (`docs/power-audit.md` → "Power check"). Exit 0 = pass; 1 = a number is over its limit
      (find the cause before release, or loosen that limit with the spec item that needs it); 2 = environment invalid
      (slow-WebView state, software GPU, busy host): fix it and run again. Paste the table into the milestone note.
      If a release lowered a number, tighten: `--runs 3 --write-limits` and commit `tools/power/limits.tsv`.
- [ ] Optional (user): the energy run on a phone with power rails (`tools/power/energy.sh`, same doc → "Energy run on
      a phone"); it reports, it does not gate.
- [ ] The exact page total ships (C-022), not only "≈ N".
- [ ] Baseline Profile: only if P-001/P-002 fail on a quiet host after a same-session A/B (C-039). Record the decision.

## 2. Real-device pass (user)

- [ ] Follow `docs/real-device-pass.md` on the slowest available phone (C-060, C-021): C-060 frames, C-025 animation,
      C-031/C-066 P-020, C-024 P-002, C-036 keyboard (API 26–29 if available), C-046 vertical pages, C-070 TalkBack on
      the huge chapter, TOC and library scroll. Results in `docs/milestones/M9.md`.

## 3. Name, domain, trademark (user)

- [ ] **Trademark search for "FernReader"** (C-054), classes **9** (software) and **42** (software services), before
      any store listing goes live:
  - USPTO: <https://tmsearch.uspto.gov/> — search the word mark "FERNREADER" and "FERN READER"; also look at
    "FERN" marks in classes 9/42 that cover e-books or reading software.
  - EUIPO: <https://www.tmdn.org/tmview/> (TMview covers EUIPO and national offices) — same terms, Nice classes 9, 42.
  - Note any live mark that is identical or confusingly similar for reading software; if in doubt, ask a trademark
    lawyer before publishing. Known context: the "FERN" field is crowded; "Fern Page Publishing" runs fernpage.com;
    fernreader.com is registered by someone else (2026-10-20).
  - Record the date, the queries and the result in the milestone note.
- [ ] **Register `fernreader.app`** at a registrar of your choice (`.app` requires HTTPS for any site on it). Use it
      for the store listing's website and the privacy policy URL; the applicationId `app.fernreader` matches it.
- [ ] Optional: a project page (privacy policy, source link, licence) on fernreader.app.

## 4. Storage permission decision for Google Play (C-008)

Both flavors currently request **all-files access** (`MANAGE_EXTERNAL_STORAGE`) to find books anywhere on shared
storage. Google Play allows it only for apps whose core function needs broad file access and asks for a declaration
form at submission ("Permissions declaration → All files access"). Decide before the first Play submission:

- [ ] **Try first:** submit the `play` flavor with all-files access and the declaration: core purpose "an e-book
      reader that finds and opens the EPUB files the user keeps anywhere on the device; files are only read". Keep
      the in-app explanation screen (onboarding) and the "choose a folder" option visible in the listing screenshots.
- [ ] **If review rejects it — fallback:** the scanner sits behind an interface (C-008), so the `play` flavor switches
      to user-chosen folders through the system folder picker (Storage Access Framework, persisted tree URIs) plus the
      media index, and drops `MANAGE_EXTERNAL_STORAGE` from its manifest. That is a code change (a separate milestone):
      folder picker on first run, rescan of the persisted trees, "Open with" unchanged. The `fdroid` flavor keeps
      all-files access (F-Droid has no such policy; it shows the permission as an anti-feature-free normal permission).
- [ ] Record the decision and its date in the spec repo (gatekeeper) and in the milestone note.

## 5. Signing (user)

No signing key is in the repository, and none should ever be committed. Release builds are **unsigned** unless a
keystore is supplied; F-Droid signs its own builds from source, so the `fdroid` flavor needs no key from you.

- [ ] Create an upload/release key once, outside the repo, and back it up in two places (losing it means you can't
      update the app on Play without Google's key reset):
      ```fish
      keytool -genkeypair -v -keystore ~/keys/fernreader-release.jks -alias fernreader -keyalg RSA -keysize 4096 -validity 10000
      ```
- [ ] Give Gradle the key through `~/.gradle/gradle.properties` (not the repo's file):
      ```properties
      fernreader.signing.storeFile=/home/<you>/keys/fernreader-release.jks
      fernreader.signing.storePassword=…
      fernreader.signing.keyAlias=fernreader
      fernreader.signing.keyPassword=…
      ```
      or as environment variables `FERNREADER_SIGNING_STOREFILE`, `FERNREADER_SIGNING_STOREPASSWORD`,
      `FERNREADER_SIGNING_KEYALIAS`, `FERNREADER_SIGNING_KEYPASSWORD` (CI). Without them the release build is unsigned.
- [ ] Build: `./gradlew :app:bundlePlayRelease` (Play, `.aab`) and/or `:app:assembleFdroidRelease`; check the
      signature with `apksigner verify --print-certs <apk>`.
- [ ] Google Play: enrol in Play App Signing; the key above becomes the **upload key**.
- [ ] Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every published build.

## 6. F-Droid

- [ ] `./gradlew :app:checkFdroidClean` passes; the `fdroid` release builds from a clean checkout with only dependency
      downloads (`docs/milestones/M9.md` → "F-Droid build").
- [ ] The `fdroid` APK makes no network calls and has no `INTERNET` permission (checked in M9 part B).
- [ ] Tag the release commit (`v0.1.0`); F-Droid's metadata (fdroiddata merge request) points at the tag, the
      `fdroid` flavor and `app` module; note that the build runs `npm ci` for `reader-web/` (Node.js needed), so the
      recipe needs `sudo: apt-get install -y nodejs npm` or the srclib equivalent.
- [ ] Reproducible-build check (optional): build the tag twice in clean checkouts and compare with `diffoscope`.

## 7. Store listing assets (user)

- [ ] App name "FernReader"; short description (≤ 80 characters), e.g. "Fast, offline EPUB reader. No account, no
      tracking."; full description (features, privacy: no network, no analytics, MIT licence, source link).
- [ ] Icon 512 × 512 PNG: export from `tools/icon/fern-preview.svg` (`rsvg-convert -w 512 -h 512 …`; Play applies
      its own mask, so export the square without the circle clip).
- [ ] Feature graphic 1024 × 500 (Play).
- [ ] Phone screenshots (2–8, 16:9 or 9:16, 1080 px wide or more): library grid, reader page (light and dark),
      display settings panel, search results, contents panel. Use public-domain books from the corpus only.
- [ ] Privacy policy URL (Play requires one even with no data collection): "FernReader collects no data; it has no
      network permission." Host it on fernreader.app.
- [ ] Play Data safety form: no data collected or shared. Content rating questionnaire. Target audience.
- [ ] F-Droid: `fastlane/metadata/android/en-US/` (title, short and full description, icon, screenshots) in the repo.

## 8. Final

- [ ] `docs/licenses.md` up to date (every shipped dependency, font and asset) and shown in About → licences.
- [ ] Old development installs removed from your devices (`adb uninstall app.epubreader`, C-044).
- [ ] Publish; keep the release commit, the two benchmark gate outputs and the device-pass summary with the tag.
