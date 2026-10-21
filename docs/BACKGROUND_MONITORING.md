# Background monitoring and battery use

The background-monitor switch requires both existing `enable` and `auto_service` preferences.
Material settings, Miuix settings and onboarding use `KeepAlive.setEnabled` to update both keys
and immediately start or stop monitoring. Explicit ANC notification actions still work independently
of automatic monitoring; when monitoring is off, they get a bounded 30-second service session
without a watchdog, even if the headset stays connected. Another explicit action renews that session.
The existing notification behavior is unchanged: it attempts the command immediately while requesting
reconnection. If the transport/capabilities are not ready, retry once connected; this is not a queued
command implementation.

## Lifecycle

- Boot and app replacement can start the detector. ACL connections must match a known/learned
  headset name; A2DP/HEADSET connections also allow unrejected audio models for protocol probing.
  Unrelated BLE links do not restart it. These native Bluetooth actions are manifest-broadcast
  exceptions and do not depend on LSPosed's Bluetooth-process hook (outside the default scope).
- A new service gets a 30-second inactivity deadline even if no headset has ever been seen.
  Connected observations refresh it. Missing Bluetooth permission and Bluetooth-off also take the
  idle path. The deadline uses elapsed realtime, not wall-clock time. Handler delivery itself
  does not wake a sleeping device, so shutdown can be deferred until the CPU next runs.
- Intentional idle shutdown records a persisted idle marker and cancels the fallback alarm.
  A queued alarm cannot undo that stop. `stopSelfResult(startId)` protects a newer pending start;
  a stopped instance cannot re-arm the alarm after its last poll. A qualifying connection, opening
  the app or explicitly enabling monitoring clears idle and allows detection again. Automatic idle
  also removes stale headset notifications (including Bluetooth-off/permission-loss paths); a manual
  lease expiring with monitoring off does not withdraw the control buttons.
- Service creation no longer starts a blind LE scan or hook probe. Connection attempts still use
  the existing targeted discovery and protocol fallback. Shutdown releases detector-owned GAIA
  work: scans, queued retries, the LE-address receiver and the control connection. Stale GATT/scan
  callbacks are ignored; an in-flight RFCOMM result is closed rather than adopted after shutdown.
  The overview hands its callback back to an active detector in `onStop`; if neither owner remains,
  the connection is released. A delayed reconnect from a departed UI is ignored. Explicitly turning
  monitoring off releases both owners, including an in-progress UI connection.
- While monitoring is active, the existing one-shot fallback uses **ELAPSED_REALTIME**, not
  ELAPSED_REALTIME_WAKEUP. It does not restart an already-running service. It attempts process-death
  recovery when Android next delivers it, subject to background-start restrictions; it cannot wake
  the phone just for recovery.
- Disabling either preference rejects background receiver/sticky starts. An app update cancels
  an old-version alarm before starting the new detector. Root no longer silently re-adds battery
  exemptions or allows background appops.

Android's background-service rules still apply. The app remains an ordinary service, not a new
foreground service or companion-device integration. Native broadcast delivery is not an unlimited
background-execution entitlement. Recovery can be delayed or denied by platform/OEM policy;
opening the app remains the fallback. The existing battery-exemption consent flow remains available.
Previously granted exemptions/appops are **not revoked automatically**: this change stops overriding
future user choices rather than silently undoing existing ones. On upgrade, a Miuix switch previously
set to off is now really off, even if the old UI left `enable=true`.

## Automated checks

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`HeadsetIdleTimeoutTest` covers cold disconnected startup, a fixed deadline across repeated polls,
connection/disconnection, reconnection, a long elapsed-time gap, bounded manual control and a fresh
service instance. `BackgroundMonitoringTest` covers both flags, shared start/stop, filtered native
and legacy routing, ignored events, recovery, persisted idle and background-start failures.
`GaiaShutdownTest` covers both owner-departure orders, callback handoff, explicit shutdown, rejected
late UI reconnects, LE receiver release/re-registration and rejection of retired GATT callbacks.
These use JVM Android stubs, **not** an emulated AlarmManager, real Bluetooth transport or Android
service lifecycle. No UI tests are added.

The repository's `android.aapt2FromMavenOverride` is an upstream ARM64-host setting. On other hosts,
override it locally with an AGP-matched native aapt2; do not commit a machine-specific replacement.

## Device validation checklist (requires a device, not covered by JVM tests)

1. Start with no headset; after the grace period verify the service stops and its KEEPALIVE alarm
   is absent. Repeat with Bluetooth off and with Bluetooth permission denied.
2. Connect a headset while the service/process is absent and the app is backgrounded. Verify native
   ACL/profile broadcasts restore detection, GAIA battery updates, notifications and ANC controls.
   Repeat without any Bluetooth-process hook, without battery exemption, and on API 26–30 as well
   as newer Android. Where the OS rejects background starts, verify opening the app recovers it.
   Unrelated watches/keyboards must not start the detector via ACL events.
3. Disconnect, let the service stop, then reconnect. Confirm the old watchdog does not revive an
   idle service, including an alarm already queued at shutdown. Reconnect at the idle deadline to
   check start-ID ordering. While connected, check recovery on the next normal device wake.
4. Disable monitoring in each settings style and onboarding. Boot/connection/old watchdog delivery
   must not restart it. Confirm GAIA scans/retries stop, including disable during connection setup.
   Explicit ANC actions must remain usable without automatic monitoring and must stop detector
   polling after their bounded lease, even with a connected headset. Test UI takeover, leaving the
   overview, then disabling monitoring. Repeat with a UI reconnect thread still pending.
5. Upgrade from the old app while a wakeup alarm is armed; verify any remaining watchdog is
   non-waking. With root available, manually restrict battery use and verify a new process does
   not silently restore the exemption.
6. Compare matched unplugged, screen-off intervals with wireless debugging disconnected, hotspot
   off and the same accessory state. Measure charge use as well as alarm/awake counters. No
   numerical battery saving is claimed from source changes or JVM tests alone.
