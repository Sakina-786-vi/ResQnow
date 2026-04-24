<div align="center">

# 🚨 ResQnow

**Fast, reliable emergency assistance for highway incidents — right from your Android device.**

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-blue)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-brightgreen)](https://developer.android.com)
[![Build](https://img.shields.io/badge/Build-Gradle%20KTS-02303A?logo=gradle)](https://gradle.org)
[![Firebase](https://img.shields.io/badge/Firebase-Optional-FFCA28?logo=firebase&logoColor=black)](https://firebase.google.com)

[Features](#-core-features) · [App Flow](#-app-flow) · [Architecture](#-architecture) · [Data Layer](#-data-layer) · [Setup](#-setup--installation) · [Troubleshooting](#-troubleshooting)

</div>

---

## Overview

ResQnow is an Android emergency-response app built for highway incidents. It combines **one-tap SOS actions**, **background GPS tracking**, **Quick Settings tile controls**, and **local persistence** to help users share their location and summon help in seconds — even with limited connectivity.

> ⚠️ **Safety Notice:** This app involves real SMS sending, outgoing calls, and background location tracking. Always test on a real device with an active SIM. Emulators cannot replicate SMS, call, or GPS behavior accurately.

---

## ✨ Core Features

### 🆘 Emergency Screen
- Full-screen emergency UI with a **5-second auto-countdown**
- Auto-dials **112** unless the user cancels
- Sends an SOS SMS containing the last known GPS coordinates and a live maps link to all configured emergency contacts

### 📍 Background GPS Tracking
- Persistent foreground service using `FusedLocationProviderClient`
- **~30s interval**, balanced power mode, 100m displacement threshold
- Sticky notification with actionable buttons (Send SOS, Stop Tracking)
- Auto-restarts after device reboot if tracking was previously active

### ⚡ Quick Settings Tile
- One-tap tile to toggle tracking on/off from the notification shade
- Live status subtitle reflects active/inactive state without opening the app

### 🗺️ Highway Grid Signal
- GPS positions are mapped to **grid cells**
- Local + server-synced vehicle counts determine monitoring or clear status
- Supports a rumor-debunk threshold model to filter false signals

### 👤 Onboarding & Registration
- Multi-step setup: registration → onboarding → emergency contacts
- Smart splash routing — skips steps already completed
- Profile stored in Room; contacts persisted in both Room and SharedPreferences

### 📲 SOS Communication
- Automatic SMS dispatch to configured contacts with a default helpline fallback
- Quick-action buttons for direct call and map open
- Graceful fallback to `ACTION_DIAL` if direct call permission is denied

### 🔔 Optional FCM Alerts
- Firebase Cloud Messaging token capture on launch
- Push notification display when Firebase is configured
- Build works without Firebase — `google-services.json` is optional

---

## 🔄 App Flow

```
App Launch (splash_screen)
        │
        ├─ User not registered? ──────────────────► RegistrationActivity
        │
        ├─ Onboarding incomplete? ────────────────► OnboardingActivity
        │
        ├─ No emergency contacts? ────────────────► EmergencyContactsActivity
        │
        └─ All set ───────────────────────────────► MainActivity (Dashboard)
                          │
                          ├── Tap Emergency Card ──────────► EmergencyActivity
                          │        └── 5s countdown → auto-dial 112
                          │            └── SOS SMS dispatched
                          │
                          ├── Long-Press Emergency Card ───► Immediate call to 112
                          │
                          ├── Notification Action ─────────► SendSosSmsActivity
                          │
                          └── Quick Settings Tile ─────────► Toggle GPSTrackingService
```

---

## 🏗️ Architecture

ResQnow uses a pragmatic **Activity + ViewModel + Service + Repository** split across a hybrid XML/ViewBinding UI.

```
┌────────────────────────────────────────────┐
│               UI Layer                     │
│  Activities · ViewBinding · Material UI    │
└─────────────────┬──────────────────────────┘
                  │ observes
┌─────────────────▼──────────────────────────┐
│            ViewModel Layer                 │
│  MainViewModel · coroutines + LiveData     │
└─────────────────┬──────────────────────────┘
                  │ reads/writes
┌─────────────────▼──────────────────────────┐
│             Data Layer                     │
│  Room (AppDatabase) · SharedPreferences    │
│  (PreferencesManager) · HighwayDatabase    │
└────────────────────────────────────────────┘
          ▲                   ▲
          │                   │
┌─────────┴──────┐   ┌────────┴────────────┐
│ GPSTracking    │   │ BootReceiver        │
│ Service        │   │ SOSTileService      │
│ (Foreground)   │   │ FCM Service         │
└────────────────┘   └─────────────────────┘
```

### Project Structure

```
ResQnow/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/example/resqnow/
│           ├── splash_screen.kt              # Entry point + routing logic
│           ├── MainActivity2.kt             # Legacy (inactive)
│           ├── data/
│           │   ├── model/
│           │   │   ├── Models.kt            # Domain models
│           │   │   └── UserModels.kt        # User/profile models
│           │   └── repository/
│           │       ├── AppDatabase.kt       # Main Room DB
│           │       └── HighwayDatabase.kt   # Highway grid + thresholds
│           ├── service/
│           │   ├── GPSTrackingService.kt    # Foreground location service
│           │   ├── BootReceiver.kt          # Restart service on reboot
│           │   └── ResqFirebaseMessagingService.kt
│           ├── tile/
│           │   └── SOSTileService.kt        # Quick Settings tile
│           ├── ui/
│           │   ├── auth/RegistrationActivity.kt
│           │   ├── contacts/EmergencyContactsActivity.kt
│           │   ├── emergency/EmergencyActivity.kt
│           │   ├── home/MainActivity.kt     # Active dashboard
│           │   ├── home/MainViewModel.kt
│           │   ├── onboarding/OnboardingActivity.kt
│           │   └── sos/SendSosSmsActivity.kt
│           └── util/
│               ├── EmergencySmsHelper.kt
│               └── PreferencesManager.kt   # SharedPreferences wrapper
└── res/
    ├── layout/
    ├── drawable/
    ├── values/
    └── values-night/                        # Dark mode support
```

---

## 🗄️ Data Layer

ResQnow uses two persistence mechanisms that serve different purposes.

### Room Database (`AppDatabase` — `highway_sos_db`)

| DAO | Entity / Table | Purpose |
|---|---|---|
| `GridCellDao` | `grid_cells` | Per-cell traffic counter storage and queries |
| `TrackingSessionDao` | `tracking_sessions` | Tracking session logs |
| `UserProfileDao` | `user_profiles` | User registration profile |
| `EmergencyContactDao` | `emergency_contacts` | Contacts linked to user profile |

### SharedPreferences (`PreferencesManager`)

Used for **synchronous, cross-component state** that needs to be immediately accessible without coroutines — especially from services and tiles:

| Key | Purpose |
|---|---|
| Tracking active flag | Shared between service, tile, and UI |
| Latest GPS state | Last known coordinates cache |
| Server count cache | Per-grid-cell vehicle count from API |
| Onboarding completion | Routing gate in splash |
| Registration user ID | Cross-component user reference |
| FCM / device token | Push notification token |
| Emergency contact slots | Fallback + quick-access contacts |

> **Note:** Some contact/location state is currently duplicated between Room and SharedPreferences. Consolidating to a single source of truth is a tracked roadmap item.

---

## 🔐 Permissions

| Permission | Required For |
|---|---|
| `ACCESS_FINE_LOCATION` | Precise GPS tracking |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `ACCESS_BACKGROUND_LOCATION` | Foreground service location (API 29+) |
| `FOREGROUND_SERVICE` | GPS tracking service |
| `FOREGROUND_SERVICE_LOCATION` | Location-type foreground service (API 34+) |
| `SEND_SMS` | SOS SMS dispatch |
| `CALL_PHONE` | Direct emergency call |
| `POST_NOTIFICATIONS` | Service notification (API 33+) |
| `VIBRATE` | Alert feedback |
| `INTERNET` | Grid report API + FCM |
| `ACCESS_NETWORK_STATE` | Connectivity checks |
| `RECEIVE_BOOT_COMPLETED` | Service restart after reboot |

All emergency features **degrade gracefully** when optional permissions are denied. For example, if `CALL_PHONE` is unavailable, the app falls back to `ACTION_DIAL` (opens the dialer).

---

## ⚙️ Configuration Reference

### Key Constants (`GPSTrackingService`)

| Constant | Value | Description |
|---|---|---|
| GPS interval | `30,000 ms` | Standard location update interval |
| Fastest interval | `15,000 ms` | Minimum interval between updates |
| Min displacement | `100 m` | Minimum movement to trigger update |
| Grid expiry window | `5 min` | Age limit for grid cell data |

### Highway Grid (`HighwayDatabase`)

| Constant | Value |
|---|---|
| `RUMOR_DEBUNK_THRESHOLD` | `3` |

### App Identifiers

| Property | Value |
|---|---|
| Namespace / Application ID | `com.example.resqnow` |
| Theme | `Theme.HighwaySOS` |
| Launcher Activity | `.splash_screen` |
| Service notification channel | `sos_tracking` |

---

## 🌐 API / Network Behavior

The tracking service reports grid data to:

```
POST https://api.highwaysos.in/v1/grid/report
```

**Payload fields:**

| Field | Description |
|---|---|
| `grid_cell` | Current mapped grid cell ID |
| `highway` | Inferred highway identifier |
| `km_marker` | Approximate kilometer marker |
| `lat` / `lon` | Raw GPS coordinates |
| `device_token` | FCM token for push targeting |
| `timestamp` | ISO 8601 report time |

The server response can update the local vehicle count for that grid cell. All network failures are caught and logged — the app never crashes on connectivity loss.

---

## 🛠️ Setup & Installation

### Prerequisites

| Requirement | Version |
|---|---|
| Android Studio | Latest stable |
| JDK | 17 |
| Android SDK | API 35 |
| Gradle | Included via wrapper |

### Steps

```bash
# 1. Clone the repository
git clone <your-repo-url>
cd ResQnow

# 2. Open in Android Studio
#    File → Open → select the ResQnow root folder
#    Let Gradle sync complete

# 3. (Optional) Add Firebase
#    Place your google-services.json in app/
#    Without it, the build succeeds and FCM is silently skipped

# 4. Select a real device (recommended for SMS/call/GPS testing)
#    Then run:
./gradlew installDebug
```

---

## 🚀 Build & Test

```bash
# Debug build
./gradlew assembleDebug

# Install debug APK on connected device
./gradlew installDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedDebugAndroidTest
```

---

## 🧩 Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| **Location not updating** | Battery optimization or permission denied | Grant precise location; disable battery optimization for the app |
| **SMS not sending** | Permission denied or emulator used | Grant `SEND_SMS`; use a real SIM-capable device |
| **Call goes to dialer instead of direct-calling** | `CALL_PHONE` not granted | This is expected fallback behavior — grant the permission for direct calls |
| **No FCM notifications** | Missing or mismatched `google-services.json` | Ensure the file exists in `app/` and the package name matches your Firebase project |
| **Quick Settings tile not visible** | Tile not added by user | Open the Quick Settings edit panel and manually drag the ResQnow tile into view |
| **Service not restarting after reboot** | `RECEIVE_BOOT_COMPLETED` not granted | Check manifest permission and ensure the `BootReceiver` is registered |

---

## ⚠️ Known Limitations

- Emergency hospital/police numbers inside SMS templates are **placeholders** — replace with real local directory data before production use.
- `MainActivity2.kt` is a legacy file — the active dashboard is `ui/home/MainActivity.kt`.
- Contact and location state is **partially duplicated** between Room and SharedPreferences.
- Highway roadmap data in `HighwayDatabase` is **static and region-specific** — not dynamically updated.

---

## 🗺️ Roadmap

- [ ] Replace placeholder emergency directory data with live, region-aware sources
- [ ] Add dependency injection with Hilt or Koin
- [ ] Introduce proper repository abstraction across all data access
- [ ] Migrate contacts and profile to a single source of truth (Room only)
- [ ] Expand unit and integration test coverage for SMS dispatch, service lifecycle, and onboarding
- [ ] Set up CI pipeline for lint, test, and release build checks
- [ ] Offline-first queue for grid reports that failed during connectivity loss
- [ ] Multilingual SOS message templates

---

## 📄 Additional Documentation

The following companion documents can be generated on request:

| Document | Contents |
|---|---|
| `README.dev.md` | Condensed developer onboarding: setup, architecture, and debug commands |
| `CONTRIBUTING.md` | Branching strategy, commit conventions, and review standards |
| `SECURITY.md` | Permission rationale, key handling, and safety testing guidelines |

---

<div align="center">

Built to keep people safer on highways. Every second counts.

</div>
