# FxxkMoondrop

> Author: [bqj6666](https://github.com/bqj6666) ｜ Version: **alpha2.41.5** (versionCode 279) ｜ License: **GPL-3.0** (see [LICENSE](LICENSE))

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
- **Popup mode**: Google Fast Pair half-sheet popup (injected into GMS's HalfSheetActivity).
- **Material 3 UI**: home page (hero card + status panel + ANC buttons), settings page (Appearance / General / Behavior, follows system dark/light + Material You dynamic color), about page; all using Material 3 components.
- **Permission check** (full secondary screen): Bluetooth / Notifications / Overlay / Battery whitelist / Root / FastPairHook / GAIA direct — 7 real-time checks, one-tap jump to fix when missing.
- **Log capture** (device adaptation): one-tap collect system info / app settings / Bluetooth / runtime environment / logcat — five categories packaged as a ZIP.
- **Root force keep-alive**, auto-start on boot, background hide (optional toggles).
- **Display-layer language switching (Chinese/English)**: language preference (Follow system / Chinese / English); main-screen tabs, settings items, ANC panel, log popup, and permission-check page text follow the language; exposed via an exported ContentProvider for the GMS popup to read cross-process.

## Screenshots

| Overview | Settings | About | Fast Pair Popup |
|---|---|---|---|
| ![Home](screenshots/home.png) | ![Settings](screenshots/settings.png) | ![About](screenshots/about.png) | ![Fast Pair](screenshots/fastpair.png) |

## Tech Stack

| Item | Description |
|---|---|
| Language | **Kotlin** |
| Build chain | Gradle 8.9 (fixed wrapper) + AGP 8.5.2 + Kotlin 1.9.22 |
| UI | Material 3, `Theme.Material3.DayNight.NoActionBar` + dynamic color, three-page Fragment architecture |
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
3. Grant Bluetooth / Notification / Overlay permissions (the Settings → "Check permissions" page can jump to fix them in one tap).
4. The popup defaults to the Google Fast Pair half-sheet; you can also switch to the app's built-in floating card in Settings.

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

---

## Directory Structure

```
FxxkMoondrop-repo/
├── app/                  # Gradle app module (sourceSets point to ../src)
│   └── src/main/         # res / AndroidManifest.xml / resources/META-INF/xposed
├── src/                  # All Kotlin source (com.fxxkmoondrop.secret)
├── screenshots/          # UI screenshots used in the README
├── gradle/               # Gradle wrapper (8.9)
├── build.gradle.kts      # AGP 8.5.2 + Kotlin 1.9.22 (apply false)
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

- [JingMatrix](https://github.com/JingMatrix) and the maintained [LSPosed](https://github.com/JingMatrix/LSPosed) / [Vector](https://github.com/JingMatrix/Vector) framework: the project's **UI and interaction style references LSPosed Manager's design**; thanks for that. The project also benefits from the LSPosed ecosystem toolchain.
- [LSPlant](https://github.com/JingMatrix/LSPlant) and the Xposed / LSPosed community.
- [lingbai-rong/PuddingPods](https://github.com/lingbai-rong/PuddingPods): via that project's protocol reverse-engineering docs, FxxkMoondrop completed adaptation for Moondrop PUDDING (MD-TWS-056) — including GAIA v4 over RFCOMM/SPP, 5-level ANC device-code mapping, triple-battery (incl. charging case) reading, and gain & indicator control protocols.
- Various AIs assisted in development.

## Version History

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

## Disclaimer

This project is for learning and research of Android reverse-engineering and Bluetooth protocols only. Do not use it for any commercial purpose or to infringe on others' rights. All consequences arising from the use of this project are borne by the user.
