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
- **MQTT Remote Control:** Full remote control via MQTT (Start/Stop, Time Window toggle, Start/End time configuration, and Manual Upload trigger) with Home Assistant MQTT Discovery support. Features real-time UI synchronization and per-photo status updates.
- **Android 16 Optimization:** Integrated "Foreground Service Bridge" to maintain connectivity and prevent process freezing during background operation on modern Android versions.
- **Technical Robustness:** Shared, thread-safe MQTT client architecture with automatic reconnect and persistent sessions to survive brief network drops.
- **Time Window:** Restrict captures to specific hours (e.g., only during daylight), configurable both in-app and via MQTT.
- **Energy Efficiency:** Optimized for Wi-Fi environments with a 10-minute MQTT keep-alive to maximize battery life while maintaining instant responsiveness.
- **Security:** Android Keystore integration via `EncryptedSharedPreferences` for MQTT and SMB credentials.
- **Persistence:** AlarmManager with Exact-Alarm fallback and Boot/Package recovery.
- **Background Operations:** Dedicated Foreground Services for camera capture and data synchronization.

### Technical Notes & Platform Specifics

#### Android 14-16 (API 34-36)
Camera Foreground Service (FGS) permissions are restricted for background starts. To ensure reliability:
- **Service Bridge:** When MQTT is configured, the service remains active in the background as a "bridge" to maintain the connection.
- **Persistent Listener:** A dedicated MQTT listener job ensures that remote commands (like starting the timelapse) are received and executed immediately, even if the device is in a low-power state.
- **Manual Start:** After a reboot, it is still recommended to open the app once to guarantee the service lifecycle is correctly established by the OS.

#### Battery & Alarms
- **Battery Optimization:** For reliable long-term operation, it is highly recommended to disable battery optimizations for this app.
- **Exact Alarms:** For Android 12+, the `SCHEDULE_EXACT_ALARM` permission may be required. The app falls back to `setAndAllowWhileIdle` if not granted.

#### Build Requirements
The project is pinned to **JDK 17** for the toolchain. While modern Android Studio versions may run on newer JDKs, this project uses JVM target 17 for stability and compatibility.

### License
This project is licensed under the **GNU General Public License v3.0**. See the [LICENSE](file:///C:/Users/Jellyfin/StudioProjects/Timelapper/LICENSE) file for details.

---

<a name="deutsch"></a>
## Deutsch

Eine robuste Android-App für automatisierte Langzeit-Zeitrafferaufnahmen. Sie bietet hochwertige Aufnahmen, sichere Datensynchronisation über SMB und Fernüberwachung via MQTT mit Home Assistant-Integration.

### Funktionen
- **Camera2 API:** JPEG-Aufnahme mit frei wählbarer Kamera, Auflösung und JPEG-Qualität.
- **Sichere Warteschlange:** Room-Datenbank für Fotos und Uploadstatus.
- **SMB-Upload:** Sicherer Upload über SMB2/SMB3 (smbj 0.13.0).
- **MQTT Fernsteuerung:** Vollständige Steuerung über MQTT (Start/Stop, Zeitfenster-Modus, Start-/Endzeit und manueller Upload) inklusive Home Assistant MQTT Discovery. Bietet Echtzeit-Synchronisierung der App-Oberfläche und Status-Updates nach jedem Foto.
- **Android 16 Optimierung:** Integrierter "Foreground Service Bridge"-Mechanismus, um die Erreichbarkeit zu garantieren und das Einfrieren des Prozesses im Hintergrund zu verhindern.
- **Technische Stabilität:** Thread-sichere "Shared Client" MQTT-Architektur mit automatischem Wiederaufbau der Verbindung und persistenten Sitzungen.
- **Zeitfenster:** Begrenzung der Aufnahmen auf bestimmte Tageszeiten, sowohl in der App als auch über MQTT konfigurierbar.
- **Energieeffizienz:** Optimiert für WLAN-Umgebungen mit einem 10-minütigen MQTT-Keep-Alive, um die Akkulaufzeit bei maximaler Reaktionsschnelligkeit zu maximieren.
- **Sicherheit:** Android Keystore über `EncryptedSharedPreferences` für MQTT- und SMB-Passwörter.
- **Zuverlässigkeit:** AlarmManager mit Exact-Alarm-Fallback und Boot-/Package-Recovery.
- **Hintergrunddienste:** Getrennte Foreground Services für Kamera und Datensynchronisation.

### Wichtige Hinweise & Plattform-Besonderheiten

#### Android 14-16 (API 34-36)
Die Kamera-FGS-Berechtigung ist im Hintergrund eingeschränkt. Für maximale Zuverlässigkeit:
- **Service-Brücke:** Bei aktiver MQTT-Konfiguration bleibt der Dienst als "Brücke" im Hintergrund aktiv, um die Verbindung zu halten.
- **Permanenter Listener:** Ein dedizierter MQTT-Job garantiert, dass Fernsteuerungsbefehle (wie das Starten der Aufnahmen) sofort empfangen und ausgeführt werden, selbst wenn das Gerät im Energiesparmodus ist.
- **Manueller Start:** Nach einem Neustart wird empfohlen, die App einmal zu öffnen, um sicherzustellen, dass das System den Dienst-Lebenszyklus korrekt etabliert.

#### Akku & Alarme
- **Akku-Optimierung:** Für einen zuverlässigen Betrieb sollte die Akku-Optimierung für diese App in den Android-Einstellungen deaktiviert werden.
- **Exakte Alarme:** Ab Android 12 kann die Berechtigung `SCHEDULE_EXACT_ALARM` erforderlich sein. Die App nutzt andernfalls `setAndAllowWhileIdle`.

#### Build-Hinweise
Das Projekt ist auf **JDK 17** fixiert. Dies ist beabsichtigt, um eine stabile Kompilierung sicherzustellen, auch wenn das Build-System auf neueren JDK-Versionen läuft.

### Lizenz
Dieses Projekt steht unter der **GNU General Public License v3.0**. Weitere Details finden Sie in der [LICENSE](file:///C:/Users/Jellyfin/StudioProjects/Timelapper/LICENSE)-Datei.
