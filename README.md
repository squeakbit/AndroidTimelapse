# Android Timelapse

[English](#english) | [Deutsch](#deutsch)

---

<a name="english"></a>
## English

A robust Android application for automated long-term timelapse photography. It features high-quality capture, secure data synchronization via SMB, and remote monitoring via MQTT with Home Assistant integration.

### Key Features
- **Camera2 API:** High-resolution JPEG capture with selectable cameras, resolutions, and quality settings.
- **Reliable Storage:** Room-based queue for managing photos and upload status.
- **SMB Synchronization:** Secure upload using SMB2/SMB3 (smbj 0.13.0).
- **MQTT Monitoring:** Real-time status updates using MQTT v5 (Eclipse Paho 1.2.5) with Home Assistant MQTT Discovery support.
- **Device Stats:** Reporting of battery level, charging status, and capture/upload statistics.
- **Automated Scheduling:** Daily SMB uploads and status updates with every capture.
- **Security:** Android Keystore integration via `EncryptedSharedPreferences` for MQTT and SMB credentials.
- **Persistence:** AlarmManager with Exact-Alarm fallback and Boot/Package recovery.
- **Background Operations:** Dedicated Foreground Services for camera capture and data synchronization.

### Technical Notes & Platform Specifics

#### Android 14+ (API 34-36)
Camera Foreground Service (FGS) permissions are restricted for background starts. The `BootReceiver` restores scheduled alarms, but after a reboot, the user may need to open the app once to ensure the camera service can start reliably in the background. This is a platform-level security measure.

#### Battery & Alarms
- **Battery Optimization:** For reliable long-term operation, it is highly recommended to disable battery optimizations for this app.
- **Exact Alarms:** For Android 12+, the `SCHEDULE_EXACT_ALARM` permission may be required. The app falls back to `setAndAllowWhileIdle` if not granted.

#### Build Requirements
The project is pinned to **JDK 17** for the toolchain. While modern Android Studio versions may run on newer JDKs, this project uses JVM target 17 for stability and compatibility.

---

<a name="deutsch"></a>
## Deutsch

Eine robuste Android-App für automatisierte Langzeit-Zeitrafferaufnahmen. Sie bietet hochwertige Aufnahmen, sichere Datensynchronisation über SMB und Fernüberwachung via MQTT mit Home Assistant-Integration.

### Funktionen
- **Camera2 API:** JPEG-Aufnahme mit frei wählbarer Kamera, Auflösung und JPEG-Qualität.
- **Sichere Warteschlange:** Room-Datenbank für Fotos und Uploadstatus.
- **SMB-Upload:** Sicherer Upload über SMB2/SMB3 (smbj 0.13.0).
- **MQTT-Monitoring:** Status-Updates über MQTT v5 (Eclipse Paho 1.2.5) inklusive Home Assistant MQTT Discovery.
- **Gerätestatistiken:** Übertragung von Akkustand, Ladestatus und Foto-/Uploadstatistiken.
- **Automatisierung:** Täglicher SMB-Upload und Status-Updates bei jeder Aufnahme.
- **Sicherheit:** Android Keystore über `EncryptedSharedPreferences` für MQTT- und SMB-Passwörter.
- **Zuverlässigkeit:** AlarmManager mit Exact-Alarm-Fallback und Boot-/Package-Recovery.
- **Hintergrunddienste:** Getrennte Foreground Services für Kamera und Datensynchronisation.

### Wichtige Hinweise & Plattform-Besonderheiten

#### Android 14+ (API 34-36)
Die Kamera-FGS-Berechtigung ist im Hintergrund eingeschränkt. Nach einem Reboot stellt der `BootReceiver` zwar die Alarme wieder her, aber der Nutzer muss die App ggf. einmal öffnen, damit der Kameradienst zuverlässig starten kann. Dies ist eine Einschränkung des Android-Systems.

#### Akku & Alarme
- **Akku-Optimierung:** Für einen zuverlässigen Betrieb sollte die Akku-Optimierung für diese App in den Android-Einstellungen deaktiviert werden.
- **Exakte Alarme:** Ab Android 12 kann die Berechtigung `SCHEDULE_EXACT_ALARM` erforderlich sein. Die App nutzt andernfalls `setAndAllowWhileIdle`.

#### Build-Hinweise
Das Projekt ist auf **JDK 17** fixiert. Dies ist beabsichtigt, um eine stabile Kompilierung sicherzustellen, auch wenn das Build-System auf neueren JDK-Versionen läuft.
