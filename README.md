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
Camera Foreground Service (FGS) permissions are heavily restricted for background starts. To ensure maximum reliability:
- **Service Bridge & Auto-Wakeup:** The app utilizes an automated foreground activity bridge. When a capture alarm fires, the main activity is briefly launched (with the screen physically remaining dark) to gain "while-in-use" camera permissions. The app automatically returns to the background after the capture.
- **USE_EXACT_ALARM:** On Android 14+, the app requests the `USE_EXACT_ALARM` permission, which is automatically granted by the system for alarm-based apps, ensuring the device wakes up exactly on time even from deep sleep (Doze).
- **Time Window Optimization:** The alarm logic calculates the next window start, allowing the device to sleep continuously through the night without any intermediate wake-ups, significantly saving battery.
- **Manual Start:** After a first installation or deep system update, it is recommended to open the app once to establish the service lifecycle.

#### Battery, Alarms & Stealth
- **Battery Optimization:** For reliable long-term operation, it is mandatory to disable battery optimizations for this app via the Settings tab.
- **Stealth Mode (API 26-28):** On older Android versions (like Android 9) that force the screen to turn on during activity launches, the app automatically forces its window brightness to minimum during the background capture cycle and restores it afterwards, ensuring discrete operation.
- **Exact Alarms:** For Android 12-13, the `SCHEDULE_EXACT_ALARM` permission may be required. On Android 14+, the system uses the new `USE_EXACT_ALARM` standard.

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
Die Kamera-FGS-Berechtigung ist im Hintergrund stark eingeschränkt. Für maximale Zuverlässigkeit:
- **Service-Brücke & Auto-Wakeup:** Die App nutzt eine automatisierte Vordergrund-Aktivitätsbrücke. Wenn ein Aufnahmealarm auslöst, wird die Hauptaktivität kurzzeitig gestartet (wobei der Bildschirm physisch dunkel bleibt), um die "While-in-use"-Kameraberechtigungen zu erhalten. Die App kehrt nach der Aufnahme automatisch in den Hintergrund zurück.
- **USE_EXACT_ALARM:** Unter Android 14+ fordert die App die Berechtigung `USE_EXACT_ALARM` an, die vom System für alarmbasierte Apps automatisch erteilt wird. Dies stellt sicher, dass das Gerät auch aus dem Tiefschlaf (Doze) pünktlich aufwacht.
- **Zeitfenster-Optimierung:** Die Alarmlogik berechnet den nächsten Fensterstart, sodass das Gerät die Nacht ohne Zwischenaufwachen komplett durchschlafen kann, was massiv Akku spart.
- **Manueller Start:** Nach einer Erstinstallation oder tiefen Systemupdates wird empfohlen, die App einmal zu öffnen, um den Dienst-Lebenszyklus zu etablieren.

#### Akku, Alarme & Tarnung
- **Akku-Optimierung:** Für einen zuverlässigen Langzeitbetrieb ist es zwingend erforderlich, die Akku-Optimierung für diese App über den Setup-Tab zu deaktivieren.
- **Tarnmodus (API 26-28):** Auf älteren Android-Versionen (wie Android 9), die das Einschalten des Bildschirms während des Aktivitätsstarts erzwingen, dimmt die App ihre Fensterhelligkeit während des automatischen Aufnahmezyklus automatisch auf das Minimum und stellt sie danach wieder her.
- **Exakte Alarme:** Für Android 12-13 kann die Berechtigung `SCHEDULE_EXACT_ALARM` erforderlich sein. Auf Android 14+ greift der neue `USE_EXACT_ALARM`-Standard.

#### Build-Hinweise
Das Projekt ist auf **JDK 17** fixiert. Dies ist beabsichtigt, um eine stabile Kompilierung sicherzustellen, auch wenn das Build-System auf neueren JDK-Versionen läuft.

### Lizenz
Dieses Projekt steht unter der **GNU General Public License v3.0**. Weitere Details finden Sie in der [LICENSE](file:///C:/Users/Jellyfin/StudioProjects/Timelapper/LICENSE)-Datei.
