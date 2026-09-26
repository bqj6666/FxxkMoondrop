# FxxkMoondrop

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&labelColor=555555)
![Xposed](https://img.shields.io/badge/Xposed-API_102-E64A19?style=flat-square&labelColor=555555)
![Target](https://img.shields.io/badge/Target-gms_%7C_settings-007EC6?style=flat-square&labelColor=555555)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square&labelColor=555555)

> Author: [bqj6666](https://github.com/bqj6666) | Version: **3.2.8** (versionCode 328) | License: **GPL-3.0** (see [LICENSE](LICENSE))

> [![Latest release](https://img.shields.io/badge/release-3.2.8-2ea44f?style=flat-square&labelColor=555555)](https://github.com/bqj6666/FxxkMoondrop/releases/latest) | [Changelog](CHANGELOG.md)

Moondrop Bluetooth earbud assistant: automatically shows a **Fast Pair card** when the earbuds connect, and talks to the earbuds directly over **GAIA BLE** to read status and control noise cancellation. The project itself is an **LSPosed / Xposed module**.

> **AI vibe-coding notice**: Part of the application code was **assisted by AI**. The code has been manually reviewed and verified on real devices, but may still contain logic errors, security flaws, or compatibility issues. **Please review the code carefully before use**; use at your own risk.
>
> **Reverse-engineering notice**: This project controls earbuds and reads status by leveraging the private interfaces of the Moondrop App (`com.moondroplab.moondrop.moondrop_app`), **for study and research purposes only**. The project does **not** contain any code, resources, or decompiled artifacts from the Moondrop App; all hook target class names are referenced only as strings. Do not use for commercial purposes; use at your own risk.

---

## Features

- **Bluetooth monitoring + GAIA direct connection**: BLE GATT direct connection to the earbuds, reads left/right battery levels, controls noise cancellation.
- **ANC model profile library**: `AncProfileLib` automatically applies tested device-code mappings by device name (e.g., GA2 tested 1=OFF / 2=ANC / 3=Wind / 4=Transparency); untested models fall back to the default mapping; custom mappings in Settings take precedence.
- **FastPairHook (LSPosed module, injected into Google Play services)**: leverages GMS's BLE scanning to dynamically discover the earbud's LE address and push it to the app.
- **Self-healing loop**: no cache → REQ scan → GMS push → connection success writes back to file / SP → instant reconnect next time; address changes trigger automatic rediscovery (**fully dynamic address discovery, zero hardcoding**).
- **Popup mode**: Google Fast Pair half-sheet popup (injected into GMS's HalfSheetActivity); the popup "Settings" button jumps to the system Bluetooth device detail page.
- **System Bluetooth device detail injection (LSPosed)**: hooks `com.android.settings`' Bluetooth device detail page (`BluetoothDeviceDetailsFragment`) to inject a noise-control and function-control panel (wind / gain / LED) that you can adjust directly on the settings screen; the whole block collapses while disconnected and holds its place with the official loading row until the earbuds are ready.
- **Google official Hearable Controls bridge (LSPosed, injected into Google Play services)**: hooks the GMS Hearable Controls path (GFPS message group `0x08`) - locates the official ANC submodule by DexKit signature, lifts the Fast Pair cache gate, and catches panel taps (ANC / Transparency / volume slider / prompt-and-vibration tone) at **both the intent entry point and the manager's send exit**, translating them into GAIA requests the app really sends to the earbuds; the app's real state is injected back into the official DataStore so the official UI highlight always matches reality. This injection is disabled in no-root mode.
- **Official spatial audio / head tracking rows (LSPosed)**: the details page uses the system's own "Spatial audio" and "Head tracking" switches instead of custom controls — this device reports the rows as unavailable and removes them, so the module lifts that check and lets the official component render them, with the checked state and taps wired to the earbuds (GAIA). Only earbuds this module supports are taken over; any other earbud (including ones the system natively supports) is handed straight back to the official code.
- **Extended device control (DC)**: spatial audio / head tracking / gain / LED; the three tracking levels are derived by `AncProfileLib` — with spatial audio on it never rests at "tracking off", a reported level 0 is auto-corrected to 30°, and an explicit manual off by the user is respected. Device-code mapping comes from `AncProfileLib.DcProfile` per model.
- **Three-protocol auto-detection**: GAIA V3 (BLE) / GAIA V4 (RFCOMM/SPP, PUDDING) / Moondrop private 9ECA0000 auto-routing; falls back to RFCOMM/SPP when BLE fails on dual-mode devices.
- **9ECA private protocol client**: source switching / EQ / MIC / SN (reuses the same GATT connection, coexists with GAIA).
- **Device notification**: battery and noise control are merged into **one** persistent notification (left/right earbuds only, no charging case); mode buttons carry large icons with the active mode highlighted, colored from the system dynamic palette, and are generated from the device's **actually supported** modes.
- **No-root mode**: when no root is detected, the app automatically falls back to controlling noise cancellation **from the notification and the main UI only** (GAIA BLE direct has never needed root); every root-dependent feature is silently disabled — no errors, no dialogs.
- **Material 3 UI**: home page (hero card + status panel + ANC buttons), settings page (Appearance / Features / Custom Mapping / Background / Diagnostics, follows system dark/light + Material You dynamic color), about page; all using Material 3 components.
- **Permission check** (full secondary screen): 6 real-time checks grouped into **required** (Bluetooth / notifications / GAIA direct link) and **optional** (battery whitelist / root / FastPairHook module), each with one-tap jump to fix; the verdict only counts required items, so a missing optional one is not treated as "permissions not set up".
- **Log capture** (device adaptation): one-tap collect system info / app settings / Bluetooth / runtime environment / logcat — six categories packaged as a ZIP.
- **Keep-alive is on by default and needs no root**: auto-start on boot + a 30-second watchdog + the system battery optimisation whitelist (asked only once); on a rooted device `deviceidle` / `appops` tweaks are applied silently and failures are non-fatal. A separate optional "hide in background" switch is also available.
- **Getting started tour**: shown automatically on first launch, a horizontally paged tour with one feature area per page — About / Permissions / Connection / System integration / Headset control / Adaptation & diagnostics / Welcome. Every switch inside is the same switch as in Settings and takes effect immediately. It never auto-opens again after you finish or skip it, and can be replayed from the bottom of Settings.
- **Display-layer language switching (Chinese/English)**: language preference (Follow system / Chinese / English); main-screen tabs, settings items, ANC panel, log popup, and permission-check page text follow the language; exposed via an exported ContentProvider for the GMS popup to read cross-process.

## Screenshots

| Overview | Settings | About | Fast Pair Popup | Device notification | Device details |
|---|---|---|---|---|---|
| ![Home](screenshots/home.png) | ![Settings](screenshots/settings.png) | ![About](screenshots/about.png) | ![Fast Pair](screenshots/fastpair.png) | ![Device notification](screenshots/notif.png) | ![Device details](screenshots/detail.png) |

## Tech Stack

| Item | Description |
|---|---|
| Language | **Kotlin** |
| Build chain | Gradle 8.9 (fixed wrapper) + AGP 8.6.1 + Kotlin 2.3.21 |
| UI | Material 3, `Theme.Material3Expressive.DayNight.NoActionBar` (M3 Expressive) + dynamic color, three-page Fragment architecture |
| Minimum system | **Android 8.0** (API 26); targetSdk 36 |
| Module | libxposed API 102 (LSPosed ≥ 2.1.1, scope `com.google.android.gms;com.android.settings`) |
| Package | `com.fxxkmoondrop.secret` |

## Build

```bash
./gradlew :app:assembleRelease -PfxxkKeypass=<signing password>
```

- Output: `app/build/outputs/apk/release/app-release.apk`
- Gradle `packaging.merges` automatically merges `META-INF/xposed/*` (`java_init.list` / `module.prop` / `scope.list`), and the APK is signed after building.
- Provide your own signing key; pass the password via `-PfxxkKeypass=` during build.

## Installation

1. Install the APK.
2. In **LSPosed**, enable it and check the scope `com.google.android.gms` (optionally `com.android.settings`).
3. On first launch a **getting-started tour** appears, where you can grant permissions directly (the Settings "Check permissions" page works too); if you skipped it, reopen it from the bottom of Settings.
4. **Root is optional**: it works without root — when no root is detected the app enters no-root mode and still reads battery and switches ANC over GAIA BLE direct; only the official panel injection, popup icon customization and root keep-alive are disabled.

> ## Need more real-device testing;
>
> The **theoretically supported** and **unknown** models in the table below are mostly chip-level inferences and have not all been verified on real devices. If you own the corresponding earbuds, you are welcome to **connect them once and report the result to [Issues](https://github.com/bqj6666/FxxkMoondrop/issues)**.

## Supported Devices

> Compatibility is determined based on the **Bluetooth transport layer and service fingerprint**, not the model name:
> - Earbud exposes Qualcomm **GAIA service** via BLE GATT → uses GAIA V3 protocol
> - Earbud exposes Qualcomm **GAIA service** via Classic BT RFCOMM/SPP → uses GAIA V4 protocol (e.g., PUDDING)
> - Earbud exposes Moondrop private **`9ECA0000` service** → uses the private protocol (audio source switch / EQ / MIC / SN)
>
> Therefore, as long as the main controller is **Qualcomm QCC** or **Bluetrum**, it should theoretically be connectable.

| Status | Earbud Model | SoC / Protocol | Evidence |
|---|---|---|---|
| Tested | 梦回2 / Golden Ages 2 (GA2) | TWS-01 custom SoC (GAIA) | Verified on real device (ANC device codes 1=OFF / 2=ANC / 3=Wind / 4=Transparency stored) |
| Theoretically supported | 爱丽丝 ALICE | QCC5151 (GAIA) | Chip-level support |
| Theoretically supported | 火花 SPARKS | QCC3040 (GAIA) | Chip-level support |
| Theoretically supported | 旅行者 VOYAGER (neckband) | QCC5144 (GAIA) | Chip-level support |
| Theoretically supported | 梦回1979 / Golden Ages | Same platform & SoC as GA2 (GAIA) | Chip-level support |
| Theoretically supported | 猫饼 NEKOCAKE | BT8922E (9ECA) | Chip-level support |
| Tested | 太空漫游2 / Space Travel 2 | BT8932F (9ECA) | Verified on real device (ANC device codes 1=OFF/2=ANC/3=Wind/4=Transparency; gain codes 0=High/1=Mid/2=Low stored) |
| Tested | U.C.T.S (MD-OWS-014, open-ear) | Qualcomm QCC (GAIA) | Verified on a real device (issue #9: recognized, controls work, L/R battery correct, popup works; **the hardware itself has no ANC**, so noise cancellation is unavailable by design) |
| Theoretically supported | 音乐胶囊 PILL | BT8932F (9ECA) | Chip-level support |
| Theoretically supported | 超声波 ULTRASONIC | BT8952F (9ECA) | Chip-level support |
| Theoretically supported | 知更鸟 Robin | BT8952F (9ECA) | Chip-level support |
| Unknown | 太空漫游 / Space Travel (gen 1) | Suspected Bluetrum (model unconfirmed) | Pending real-device test |
| Unknown | 猫咖 MOCA | Suspected Bluetrum (BT 5.4 / LC3 characteristics) | Pending real-device test |
| Unknown | 方糖 BLOCK | Suspected Bluetrum BT8922 family | Pending real-device test |
| Tested | 布丁 PUDDING (MD-TWS-056) | Domestic SoC (GAIA V4, RFCOMM/SPP) | Adapted via [PuddingPods](https://github.com/lingbai-rong/PuddingPods) protocol docs; 5-level ANC + triple-battery + gain + indicator |
| Unknown | 太空漫游2 ULTRA | Domestic SoC (model not public) | Pending real-device test |
| Unknown | 羽翼 EDGE / EDGE2 | Domestic SoC (model not public) | Pending real-device test |

- **Tested**: verified by the developer on a real device.
- **Theoretically supported**: the SoC is confirmed and the protocol side auto-detects, but not every one has been run through on a real device.
- **Unknown**: the SoC is not public or suspected to be Bluetrum family; connect the earbuds and check the log's GATT fingerprint (`GAIA` / `9ECA0000`) to confirm.

---

## Google Fast Pair Service Popup Adaptation

> **The Fast Pair popup depends on a complete Google Play Services (GMS) installation**; whether it appears depends on the **completeness of your system's GMS**, not the earbud model. The module itself does not need to install GMS components separately.

| System | Fast Pair Popup | Description |
|---|---|---|
| Tested | AOSP-like / stock (full GMS) | Fully functional |
| Requires extra module | ColorOS (OPPO / realme / OnePlus) | Needs [oplus-cn2global (Magisk module)](https://github.com/AndroPlus-org/magisk-module-oplus-cn2global) + [Luckytool (Xposed, unblock GMS restrictions)](https://github.com/Xposed-Modules-Repo/com.luckyzyx.luckytool) for the Fast Pair popup to work |
| Pending test | Other systems | Any system with full GMS should theoretically work (not yet verified one by one) |

### Enabled Scope & Hook Coverage

The module's LSPosed **scope only needs two entries**: `com.android.settings` and `com.google.android.gms`.

| Hook target | Scope | Status |
|---|---|---|
| Settings entry | `com.android.settings` | Enabled |
| Bluetooth device details panel | `com.android.settings` | Enabled |
| Fast Pair popup (card / connected state) | `com.google.android.gms` | Enabled |

Two further chains are kept in the source but are **outside the scope and therefore never injected or executed**:

| Dormant chain | Target package | Status |
|---|---|---|
| `hookMoondrop` | `com.moondroplab.moondrop.moondrop_app` | Disabled (**not** a scope) |
| `hookBluetooth` | `com.android.bluetooth` | Disabled (**not** a scope) |

- These two packages **are not** LSPosed scopes of this module; LSPosed never injects the module into those processes, so **that code never runs**;
- **The code is kept intact, not deleted**, ready to be enabled for future multi-device adaptation;
- The module **does not hook the official Moondrop app** (to avoid conflicting with its own logic).

> The Fast Pair obfuscated class names (`dtes` / `dthi` / `dtok`) may be renamed by any upstream rebuild; this is now covered by DexKit signature-based fallback (see version history 2.50). It only engages when the original names fail to load and still falls back to them, so **none of the behaviour above is affected**.

---

## Directory Structure

```
FxxkMoondrop-repo/
├── app/                  # Gradle app module (sourceSets point to ../src)
│   └── src/main/         # res / AndroidManifest.xml / resources/META-INF/xposed
├── src/                  # All Kotlin source (com.fxxkmoondrop.secret)
├── screenshots/          # UI screenshots used in the README
├── gradle/               # Gradle wrapper (8.9)
├── build.gradle.kts      # AGP 8.6.1 + Kotlin 2.3.21 (apply false)
├── settings.gradle.kts   # Module declarations and repositories
├── tools/                # Build helper scripts (post_edf.py: EDF injection + re-sign)
├── ADAPTATION.md         # Device adaptation notes (protocol knowledge / pitfalls / test data)
├── ARCHITECTURE.md       # System architecture doc (process model / data flow / popup layout / protocol)
├── DEVELOPMENT.md        # Development doc (build env / directories / versioning / debugging / release checklist)
├── CHANGELOG.md          # Changelog (recorded version by version)
└── (no xposed-api-stub.jar needed)  # Now uses Maven dependency io.github.libxposed:api:102.0.0
```

## Development Docs

The project maintains several development docs in the repo root; read as needed:

| Doc | Content | When to read |
|---|---|---|
| [ADAPTATION.md](ADAPTATION.md) | Device adaptation: protocol knowledge, pitfalls, test data, BLE/9ECA frame formats, ANC device-code mapping, connection strategy | When adding earbud support or troubleshooting connection/protocol issues |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture: dual-process model, cross-process communication, core modules, key data flows, popup layout, protocol architecture & design principles | To understand the overall design, before major changes |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Dev guide: build env & commands, signing & EDF scope injection, directory structure, version numbering, LSPosed metadata, dependency list, debugging tips, release checklist | Before local compilation, secondary development, or submitting a PR |
| [CHANGELOG.md](CHANGELOG.md) | Changelog: features, fixes, and reverse-engineering progress recorded per `alpha.x.y` | To review version history |

> Version format is `alpha.x.y`: `x` is the milestone, `y` is the iteration, `versionCode` increases monotonically. See [DEVELOPMENT.md versioning](DEVELOPMENT.md#versioning).

## Acknowledgments

- [JingMatrix](https://github.com/JingMatrix) and the maintained [LSPosed](https://github.com/JingMatrix/LSPosed) / [Vector](https://github.com/JingMatrix/Vector) framework: the module is built on this ecosystem's toolchain; thanks for that.
- [LSPlant](https://github.com/JingMatrix/LSPlant) and the Xposed / LSPosed community.
- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods): via that project's protocol reverse-engineering docs, FxxkMoondrop completed adaptation for Moondrop PUDDING (MD-TWS-056) — including GAIA v4 over RFCOMM/SPP, 5-level ANC device-code mapping, triple-battery (incl. charging case) reading, and gain & indicator control protocols.
- Various AIs assisted in development.

## Version History

- **3.2.8**: **Full Material 3 alignment** - switched to the M3 Expressive theme (button shape and press morph, dialogs, motion curves; bottom navigation 81 -> 65dp); all 32 icons migrated from Material Icons (24 grid) to Material Symbols (960 grid), ending the mix of two icon generations; self-drawn widgets replaced by official components (three dialogs -> MaterialAlertDialogBuilder, refresh bar and circular loader -> official indicators, in-progress checks -> official LoadingIndicator); touch targets 40 -> 48dp, corner radius 20 -> 24dp, 21 font sizes back onto the M3 typescale, 7 animations moved onto motion tokens. **Fixes three real bugs**: two dynamic-color lookups used tonal tones that do not exist in the Android palette (`system_neutral1_90` / `_80`), so `getIdentifier` returned 0 and the fallback was used forever - those surfaces **never followed the wallpaper** in light theme; the notification's `onContainer` fallback was mistakenly set to the container value, making button text the same color as its background in dark theme; selected-state foregrounds and outlines now use the `onPrimary` role (hardcoded white was badly under-contrasted on the light-tinted dark-theme primary). **Build chain**: AGP 8.6.1, compileSdk 35, material 1.14.0, Kotlin 2.3.21.
- **3.2.7**: The main screen's noise-control block is now **gated on device capability** - models whose hardware has no ANC at all (such as the U.C.T.S / MD-OWS-014) no longer show a row of buttons that do nothing when tapped. While disconnected or before capability is known it still shows greyed out, so it neither flickers nor misjudges. The popup and the notification already followed this rule; the main screen now matches them.
- **3.2.6**: Fixed getting stuck on a stale LE address - when the disconnect status is 0 (a normal disconnect) the candidate list was not advanced, so after the earbuds changed their LE address (Bluetooth restart / re-pairing) the app kept retrying the same dead address forever. Candidates are now advanced whenever this session has not connected yet, so it recovers on its own.
- **3.2.5**: **Faster connection** - the service now starts connecting to GAIA the moment the earbuds-connection broadcast arrives, instead of waiting out the 5-second poll (the system broadcast was already being delivered to the process; it was only used to show the popup, not to start the connection - and the GMS wake-up path needs root, so without it you simply waited). Measured: GAIA ready in about 2.3s, connection stable.
- **3.2.4**: The Hearable Controls path (including the volume panel) is now "no functional interference and no log output" for other earbuds - the audit confirmed every forward and inject path is gated on the target address, and the two unfiltered observation logs were narrowed to the target device, which also fixes other devices' MAC addresses leaking into logs.
- **3.2.3**: Documentation fix - clarified that the volume panel and the prompt-tone/vibration panel are enabled through GMS Hearable Controls by this module and have their taps taken over (same path as the official ANC panel, likewise scoped to the target device only).
- **3.2.2**: The popup "Settings" button now opens the details page of the earbuds the card actually belongs to (with multiple Moondrop sets paired it used to jump to the first one). Added a "zero interference with other devices" gate table to the docs listing the ownership criterion of every hook and injection point.
- **3.2.1**: **The popup no longer interferes with other Fast Pair earbuds.** Google's native card for genuinely Fast Pair earbuds (Pixel Buds / Sony / Nothing) uses the *same* `HalfSheetActivity` as this module's self-drawn popup, and the module previously acted on it by class name alone - overwriting the icon and battery text, injecting ANC buttons, and even **swallowing the native "Connect" button**. It now matches popup ownership first (the self-drawn popup registers its launch time and then claims the instance; cards that are not ours are left untouched) and re-checks the card device name. **Multi-device fixes**: when the GAIA link is idle but still holds a stale device address, connect the earbuds detected this round (it used to always reconnect the old address, so switching earbuds never worked); disconnecting one set now only tears down the link that belongs to it (it used to kill the other one too); and the processing order for multiple connected sets is now deterministic.
- **3.2.0**: **Spatial audio and head tracking now use the two official rows on the system Bluetooth details page** (the custom controls are gone) — this device reports them unavailable and removes them, so the module lifts that check, lets the official component render them, and wires the checked state and taps to the earbuds. Only earbuds this module supports are taken over; everything else (including earbuds the system natively supports for spatial audio) is handed back to the official code, matched by device address. Fixed **tracking staying at "off" after enabling spatial audio**: the invariant "spatial audio on never means tracking off" now auto-corrects a reported level 0 to 30°, while an explicit manual off by the user is respected. **ANC button order unified** across the main UI, the notification shade and the Google popup as ANC / Off / Transparency / Adaptive / Live / Wind. Added the **getting-started tour** (7 pages, auto-shown on first launch, replayable from the bottom of Settings). Permission checks are grouped into **required / optional**, and the verdict only counts required items.
- **3.1.0**: Major overhaul of the Bluetooth details page — the official rows are revealed again (HD audio / Calls / Media audio), our panel and the official ANC slice each take their proper place, and the panel follows connection state (fully collapsed while disconnected, holding its place with the official loading row until ready). Fixed a large blank area below "Related tools" and duplicated panel rendering (row views recycled by the adapter left residue; now the list simply re-lays out instead of `notifyDataSetChanged`). Our rows are lifted to the screen root and ordered by the official order value, so their position no longer drifts. Wind is now the same kind of switch as the official ones (shown only on ANC / Wind).
- **3.0.5**: **Keep-alive reworked to "no root needed, on by default"**, removing the "Root force keep-alive" switch (without root: auto-start + watchdog + battery whitelist; with root: silent extra tweaks). Fixed "everything disabled when unrooted" — features that depend on the LSPosed module no longer look at root. "Module active" and "root available" are no longer conflated. A failed root probe is no longer cached forever (root is hidden entirely when unauthorised, so it can only be retried, never concluded), and a "re-check root" entry was added. Fixed the custom popup icon "selection doing nothing".
- **3.0.4**: Root detection no longer accepts `su` only (kp / APatch / FolkPatch families); the hardcoded `su` in root command execution is gone.
- **3.0.3**: Capability responses are parsed as feature/type pairs so the ANC path is no longer misjudged as unknown; the PUDDING ANC_V2 level mapping was corrected; the Adaptive level is uniformly available; hook return values are type-safe (avoiding host-process crashes); the "Alpha" wording was dropped from the UI version.
- **3.0.2**: Fixed RFCOMM sends missing the GAIA transport framing.
- **3.0.1**: Devices are not blacklisted before a protocol fingerprint disproves them.
- **3.0**: Merged the battery and noise-control notifications into a single persistent notification (large-icon mode buttons, Material You dynamic color, generated from the device's supported modes); fixed the official noise-control panel not responding to taps; added Official-integration and Notification feature toggles in Settings and regrouped the page into Appearance / Features / Custom Mapping / Background / Diagnostics; added **no-root mode** (auto fallback to notification + main UI control, all root-dependent features silently disabled); **tightened permissions** by dropping the unused `SYSTEM_ALERT_WINDOW` (the app never creates an overlay) and `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` (the service never calls `startForeground()`).
- **alpha2.54**: Fixed a crash when toggling "Dynamic color / AMOLED pure black" repeatedly. The theme switch called `requireActivity().recreate()` from a delayed callback with no cancellation, so rapid toggling queued up multiple rebuilds; the callback also captured the Fragment instance that had since detached, making the next callback throw `Fragment not attached to an activity`. Rebuilds now go through a single entry point `scheduleRebuild()` that cancels pending work before posting, coalesces consecutive requests into one rebuild, and checks `isAdded / activity / isFinishing` before running; `onDestroyView` clears pending callbacks. Measured: 12 taps within 296 ms produce exactly one rebuild, no exceptions.
- **alpha2.53**: Fixed the hero-card badge keeping a stale codec (e.g. LDAC) after the earbuds disconnect — the codec cache was never cleared, and the badge was not refreshed on the connection-state broadcast. **alpha2.52 has been withdrawn because of this defect; please use this build.**
- **alpha2.52**: Full Material 3 UI overhaul — collapsing large title (M3 LargeTopAppBar), fadeIn + scaleIn page transition, accent-colored hero card, and theme/language switched to "row + current value + dropdown" (the menu opens at the touch point, selected item filled with the accent color + check mark). Fixed the injected Bluetooth device-details panel collapsing into a single native row (the hook process used the host Context to resolve module resources; both package IDs are `0x7f`, so the IDs hit host resources and threw a swallowed exception). The spatial-audio switch now reuses Settings' own widget (constructing `MaterialSwitch` directly always throws in the Settings process). ANC four-state and popup noise-control buttons moved to Material Symbols vectors; the app icon was rebuilt as an adaptive icon (background / foreground / monochrome). The hero badge now shows the active codec (LDAC / AAC / SBC, …) read over the existing root channel — no codec-name table is kept; it falls back to the link type. Custom mapping now uses dropdowns, and tracking labels save on focus loss. 26 unit tests green.
- **alpha2.51**: Fixed the swapped Transparency ↔ Wind-noise ANC buttons (issue #1). Root cause: editing any single level in Settings only persisted that level, so the remaining levels fell back to the **nominal default map** instead of the model **profile map**. Device codes 3/4 mean "wind noise" and "transparency" respectively on GA2 / Space Travel 2, and the nominal order happens to be the reverse of the profile order — so touching any single level silently swapped the two buttons. The fallback baseline is now the model profile, a one-shot `healStaleCustomAncMap()` self-heals existing dirty config (models whose profile equals the nominal order never match, so user settings are never wrongly cleared), and Settings now persists **all four levels at once** to prevent partial writes, with the active profile name shown in the hint. Added `AncProfileLibTest` regression cases; 26 unit tests green. No changes to the connection chain, protocol detection or GET/SET semantics.
- **2.50**: DexKit signature-based resolution introduced — the GMS Fast Pair obfuscated class names (`dtes`/`dthi`/`dtok`) can be renamed by any upstream rebuild, so hard-coding them is doomed to break; a new `DexKitLocator` resolves them from stable in-dex signature strings, and is only engaged when the original hard-coded name fails to load (the fast path is byte-for-byte the old behaviour, zero overhead), always falling back to the hard-coded name on failure — enhance-only, never replace; `dtok` has no usable signature string and is derived from the type of the `c` field of `dtes`. `abiFilters` also trims the ABI set, shrinking the APK by ~0.8MB. Verified on-device by forcing the fallback path with fake class names; no hook / protocol / device-DB behaviour changed.
- **alpha2.41.10**: Monitor switch consolidated - the Overview "Start/Stop background monitor" button was removed and unified into the Settings "Background monitor" master switch (on = start monitoring immediately and write both `enable`/`auto_service`, so launch auto-resume, boot auto-start and keep-alive follow; off = stop the service and cancel keep-alive); the Overview hero card still shows the live running state.
- **alpha2.41.9**: issue #3 triple fix — (1) "hide in background" no longer kills the app: `MainActivity.onStop` stopped calling `finishAndRemoveTask()` unconditionally and now hides only on `onUserLeaveHint` (user-initiated leave), guarded by a global visible-activity counter plus a 500ms re-check, so in-app navigation and permission flows are unaffected; (2) GAIA LE address cache poisoning fixed — FastPairHook now filters pushed addresses by real device name via `DeviceMatcher` (no hard-coded model/MAC), the three "persist before verification" paths only keep an in-memory candidate, persistence happens solely after GAIA/9ECA service confirmation, and a service-less address is invalidated plus its poisoned cache cleared for self-healing; (3) the background connect popup recovers accordingly, and the "Hide in background" description was clarified.
- **alpha2.41.8**: Icon offset around the battery subhead now waits for the subhead layout to complete (polling) instead of a one-shot measurement, so the offset can no longer be 0 and hide the battery percentage; added positioning logs.
- **alpha2.41.7**: Device card icon top is computed dynamically to avoid the battery subhead and no longer covers the battery percentage.
- **alpha2.41.6**: No second popup after first close once GAIA becomes ready — PopupGate.markUserClosed() registers the device on popup close (suppresses auto re-show until this connection disconnects); the half-sheet close broadcast carries the device name and refreshes the popup anti-repeat timestamp; the registration is cleared on disconnect, so the next connection and other models are unaffected.
- **alpha2.41.5**: Space Travel 2 mapping written to device DB + popup de-dup. Device DB: Space Travel 2 added to PROFILES (ANC mapping `[1,2,4,3]`, matching GOLDEN AGES 2); DcProfile gain `gainMap` fixed to `[2,1,0]` (device codes are reversed: 0x00=High/0x01=Mid/0x02=Low). Popup de-dup: postShow now suppresses a new sheet when one is already showing or the last show was under 12s ago, and cancels the queued popup on sheet close — it shows once, refreshes to controllable once GAIA is ready (or you close it and use the app).
- **alpha2.41.4**: RFCOMM frame-framing rework + response-driven capability fallback — new GaiaRfcommFramer streaming state machine cuts the SPP stream per the official TransportProtocol (FF frames by Len field, bare PDUs by frame boundary, partial frames retained across bursts), fixing mis-split frames and garbled device-info strings when devices double-send responses; CapabilityProbe gains onFeatureResponseSeen/onBasicAlive so devices that never return the capability bitmap get capability flags driven by real responses (BASIC cmd0 GET_GAIA_VERSION handled too); startProbes is throttled to one full probe per 8s to prevent probe loops stacking during RFCOMM reconnect storms. TX path unchanged (bare PDU), so verified devices like GA2/Pudding behave exactly the same.
- **alpha2.41.3**: Runtime permission request now includes BLUETOOTH_SCAN — the request array is expanded to CONNECT+SCAN (matching the official app) and PermissionChecker checks both; fixes 9ECA BLE-control devices like Space Travel 2 where a missing SCAN permission threw SecurityException, killed the BLE channel, forced RFCOMM fallback and left GAIA capabilities incomplete (ANC/gain could not be adjusted).

- **alpha2.41.2**: Connection-stability boost — RFCOMM/SPP fallback for dual-mode devices like MOCA. GaiaBleClient single-candidate branch now upgrades LE → TRANSPORT_AUTO → RFCOMM (default, all devices); when both LE and TRANSPORT_AUTO fail (status=147) it actively tries RFCOMM/SPP to fix devices like MOCA whose LEE GATT is dropped by BR/EDR and cannot establish the GAIA control channel; adds an rfcommFallbackTried flag so RFCOMM failure does not spam, and connect() reuses an established RFCOMM (useRfcomm && connected) to avoid detect polling disconnecting it.
- **alpha2.41.1**: Fix log export EACCES (Permission denied) — on some ColorOS builds getExternalFilesDir returns a /Android/data/.../files/Download/logs/ path blocked by storage policy when writing the ZIP, so log capture failed with "Save failed"; LogCollector now packages into app internal filesDir (always writable) and exports via Root → MediaStore public Downloads (Android 10+, no storage permission) → internal dir fallback, so the log ZIP saves on any ROM with or without Root
- **alpha2.41.0**: Bluetrum-side connection stability fixes + Space Travel 2 (BT8932F) adaptation — GaiaBleClient adds lastConnectedAddr + transportAutoTried, transportFor falls back to TRANSPORT_AUTO when dual-mode TWS is dropped by LE during service discovery (status=147), and single-candidate disconnect records the address for delayed reconnect; AncProfileLib adds a SPACE TRAVEL 2 DC profile (no spatial audio, 3-level gain, identity mapping).
- **alpha2.40.1**: The Fast Pair sheet's "Settings" button now opens the system Bluetooth device detail page (instead of the app's MainActivity); added resolveMoondropAddress() that dynamically matches the Moondrop headphone address from paired devices (no hardcoded MAC), opening Settings$BluetoothDeviceDetailActivity with :settings:show_fragment + device_address; falls back to the original MainActivity when no match.
- **alpha2.40.0**: Moved the control panel into the Bluetooth device detail page — injects the noise-control + feature panel into Settings device details; spatial-audio switch is triple-disabled (isEnabled+isClickable+isFocusable) while disconnected; noise-control title gets topMargin=dp(16) so it no longer touches the card top edge; pure injected UI (ControlPanel/DeviceDetailsPanel/CtrlBus) with no BLE/Gaia singleton and no main-screen changes.
- **alpha2.38.10**: Display-layer Chinese/English switching. Text in the ANC panel, log popup (privacy notice / progress / save path / ZIP inner filenames), and permission-check page now follows the language preference (Follow system / Chinese / English); preference is exposed via an exported ContentProvider for the GMS popup to read cross-process.
- **alpha2.38.9** (previous): Adapted PUDDING (MD-TWS-056) via [PuddingPods](https://github.com/lingbai-rong/PuddingPods) protocol docs — GAIA v4 over RFCOMM/SPP, 5-level ANC (Off / Adaptive / Transparency / Wind / Basic), triple-battery incl. charging case, gain & indicator control; also fixed a popup custom-icon crash caused by a duplicate `setContentView` in SettingsFragment.
- alpha2.38.7: Popup battery text written back into GMS native `subhead` (below earbud name, above icon); removed self-drawn overlay + hardcoded coordinates; self-drawn fallback only when `subhead` is missing, position read from `PopupProfile` screen-layout library.
- alpha2.38.5: Fixed popup battery display loss + ANC button unresponsive (mode bar dynamic-position tracking of `central_btn`).
- alpha2.38.4: Popup icon + mode panel raised 140px to make room for the settings button.
- alpha2.38.3: Settings button fully cloned the confirm button + top-aligned.
- alpha2.38.2: Added `PopupProfile` data class + `PROFILE_61` / `PROFILE_63` configs, auto-select by screen resolution.
- alpha2.38: Removed PopupOverlay + all hardcoded UI value fixes.
- alpha2.37: Popup settings button alignment + DC custom settings.
- **alpha2.31**: Xposed module migrated to **libxposed API 102** (for LSPosed ≥ 2.1.1) — `XposedEntry` extends `XposedModule`, all hooks use `module.hook().intercept{}`, `HookHelper` pure reflection replacing `XposedHelpers`, resource declarations moved to `META-INF/xposed/{java_init.list,module.prop,scope.list}`, Maven dependency replacing the local stub jar.
- alpha2.26.10: GET/SET bidirectional mapping split — GA2 firmware read-back uses 0-based direct (0=OFF / 1=ANC / 2=Transparency / 3=Wind), independent from SET's 1-based enum (1/2/4/3) profile mapping; fixed button state stuck when read-back is 0.
- **alpha2.26.9**: ANC model profile library `AncProfileLib` — GA2 tested 1=OFF / 2=ANC / 3=Wind / 4=Transparency, auto-applied by device name; untested models fall back to default; custom mapping takes precedence (GAIA path only, not mixed with 9ECA Bluetrum family).
- **alpha2.26.8**: Connection fix — persist only the confirmed LE address, refresh GATT cache after connection succeeds (aligns with official refreshDeviceCache).
- **alpha2.26.7**: Reverted UNKNOWN→AudioCuration violation chain — "Unknown / Not ready" no longer sends cross-path commands.
- **alpha2.26.2**: ANC button mapping made configurable — custom device codes (0-5) in Settings, default AC 1-based [1,2,3,4].
- **alpha2.26**: Fixed button confusion after ANC control refactor — `fetchAncMode` truly uses `cmd=3(GET_MODE)`, official panel / main UI added 4th mode "Wind".
- **alpha2.25**: Capability probe fallback cmd=41→3 — `fetchAncMode` AudioCuration path reads `cmd=3(GET_MODE)` (GA2 unstable response to cmd=41), verified on installed device.
- **alpha2.24**: Official App reverse-engineering evidence landed — GA2 uses ANC_V2(0x20), determines ANC path by response feature bit, no hardcoded model.
- **alpha2.23**: Fixed "ANC refresh jumps back to Off" — ANC path identity mapping + explicit `ancPath` + ANC read uses `cmd=41(GET_CURRENT_ANC_SWITCH_CONF)`.
- **alpha2.22**: Removed optimistic updates + official Qualcomm protocol (AudioCuration) landed — capability bitmask truncation detection, terminal capability state, active read-only probe, ANC three-state broadcast, `cachedLe` bound to device name to prevent cross-talk.
- **alpha2.21**: Connection locked to learned LE address; GA2 lid-open connects in 1.8s instead of 12s timeout rotation.
- **alpha2.20**: Learned LE address prioritized over bonded main address, avoiding taking PUBLIC address to LE background to wait for broadcast.
- **alpha2.19**: Fixed "ANC control sometimes works sometimes not" — capability probe flag never reset causing `ancPath` stuck at -1; now resets on disconnect and every new GATT session with timeout self-heal resend.
- **alpha2.18**: Fixed the false "still connected" issue when earbuds disconnected (stale cache invalidation / dual-address self-feedback protection).
- **alpha2.17**: Fixed GA2 (DUAL) connection failure — scan for the real LE address by name, implementing a self-healing loop.
- **alpha2.16**: Integrated full 9ECA0000 protocol client (audio source switch / EQ / MIC / SN), full-chain runtime logs.
- **alpha2.15**: Cross-model adaptation + official App reverse-engineering evidence additions.
- **alpha2.14**: Open-source release (GitHub) base version.
- **alpha2.13**: Kotlin migration 28/28 complete (pure Kotlin source); fixed Settings / About page title and status bar overlap; fixed page loss after AMOLED-triggered recreate; Gradle + AGP project done; clean full build verified.
- **alpha2.12**: M3 three-page Fragment architecture (Home / Settings / About).
- **alpha2.0 and earlier**: Single Activity + old build chain (historical versions not in this repo).

<a id="support"></a>

## Support

If this project helped you, feel free to scan the code below to buy me a coffee —
your support is the biggest motivation for updates.

<p align="center">
  <img src="screenshots/reward.png" alt="Tip QR code" width="280">
</p>

## Disclaimer

This project is for learning and research of Android reverse-engineering and Bluetooth protocols only. Do not use it for any commercial purpose or to infringe on others' rights. All consequences arising from the use of this project are borne by the user.
