# Android Timelapse for Plants

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
- **Time Window & Light Buffer:** Restrict captures to specific hours (e.g., during daylight or artificial light cycles) with an automatic phase-closing capture at window end. Includes a configurable Light Buffer/Offset (default: 10s, 0–300s) to delay window-start captures (allowing ramp-up lights to reach 100% brightness) and advance window-end captures (taking the final photo before lights switch off). Configurable both in-app and via MQTT.
- **Energy Efficiency:** Optimized for Wi-Fi environments with a 10-minute MQTT keep-alive to maximize battery life while maintaining instant responsiveness.
- **Security:** Android Keystore integration via `EncryptedSharedPreferences` for MQTT and SMB credentials.
- **Persistence:** AlarmManager with Exact-Alarm fallback and Boot/Package recovery.
- **Background Operations:** Dedicated Foreground Services for camera capture and data synchronization.

### Technical Notes & Platform Specifics

#### Android 14-16 (API 34-36)
Camera Foreground Service (FGS) permissions are heavily restricted for background starts. To ensure maximum reliability:
- **Service Bridge & Auto-Wakeup:** The app utilizes an automated foreground activity bridge. When a capture alarm fires, the main activity is briefly launched (with the screen physically remaining dark) to gain "while-in-use" camera permissions. The app automatically returns to the background after the capture.
- **USE_EXACT_ALARM & Status Bar Icon:** On Android 14+, the app uses `AlarmManager.setAlarmClock()` (`USE_EXACT_ALARM`) to wake up from deep sleep (Doze). Standard `setExactAndAllowWhileIdle()` failed on Android 14-16, causing devices to sleep indefinitely due to Doze throttling and background activity launch restrictions. Using `setAlarmClock()` guarantees punctual wakeups, but causes Android to display an alarm clock icon in the status bar and list the next capture time as a system alarm on lock screens / widgets.
- **Time Window & Light Buffer Optimization:** The alarm logic calculates exact window boundaries and schedules a final phase-closing capture at window end before sleeping. To support artificial day/night cycles (e.g., grow lights that take time to ramp up or shut off abruptly), a configurable light buffer delays the start photo by N seconds (giving lights time to reach 100% output) and advances the end photo by N seconds (capturing while lights are still fully ON). Afterwards, the device sleeps continuously until the next window start, significantly saving battery.
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
- **Zeitfenster & Licht-Puffer:** Begrenzung der Aufnahmen auf bestimmte Tageszeiten mit automatischem Abschlussfoto zum Fensterende. Enthält einen konfigurierbaren Licht-Puffer (Standard: 10s, 0–300s), der die Startaufnahme verzögert (damit hochfahrende Lichter 100 % Helligkeit erreichen) und die Endaufnahme vorverlegt (damit das Foto aufgenommen wird, bevor das Licht ausgeht). In der App und über MQTT steuerbar.
- **Energieeffizienz:** Optimiert für WLAN-Umgebungen mit einem 10-minütigen MQTT-Keep-Alive, um die Akkulaufzeit bei maximaler Reaktionsschnelligkeit zu maximieren.
- **Sicherheit:** Android Keystore über `EncryptedSharedPreferences` für MQTT- und SMB-Passwörter.
- **Zuverlässigkeit:** AlarmManager mit Exact-Alarm-Fallback und Boot-/Package-Recovery.
- **Hintergrunddienste:** Getrennte Foreground Services für Kamera und Datensynchronisation.

### Wichtige Hinweise & Plattform-Besonderheiten

#### Android 14-16 (API 34-36)
Die Kamera-FGS-Berechtigung ist im Hintergrund stark eingeschränkt. Für maximale Zuverlässigkeit:
- **Service-Brücke & Auto-Wakeup:** Die App nutzt eine automatisierte Vordergrund-Aktivitätsbrücke. Wenn ein Aufnahmealarm auslöst, wird die Hauptaktivität kurzzeitig gestartet (wobei der Bildschirm physisch dunkel bleibt), um die "While-in-use"-Kameraberechtigungen zu erhalten. Die App kehrt nach der Aufnahme automatisch in den Hintergrund zurück.
- **USE_EXACT_ALARM & Wecker-Symbol:** Unter Android 14+ verwendet die App `AlarmManager.setAlarmClock()` (`USE_EXACT_ALARM`), um aus dem tiefen Doze-Modus aufzuwachen. Standard `setExactAndAllowWhileIdle()` führte auf Android 14-16 durch Doze-Drosselung und Hintergrund-Startverbote dazu, dass Geräte nach einigen Minuten einschlafen und nicht mehr aufwachen. Durch `setAlarmClock()` wacht das Gerät garantiert pünktlich auf, was jedoch dazu führt, dass Android ein Wecker-Symbol in der Statusleiste anzeigt und den nächsten Aufnahmezeitpunkt als aktiven System-Wecker auf dem Sperrbildschirm anzeigt.
- **Zeitfenster & Licht-Puffer-Optimierung:** Die Alarmlogik berechnet die genauen Fenstergrenzen und plant am Ende des Fensters ein finales Abschlussfoto ein, bevor das Gerät schlafen geht. Für künstliche Tag-/Nachtzyklen (z. B. Pflanzenlampen mit Sanftanlauf oder abruptem Ausschalten) sorgt ein konfigurierbarer Licht-Puffer dafür, dass die erste Aufnahme um N Sekunden verzögert wird (Licht fährt hoch) und die letzte Aufnahme um N Sekunden vorverlegt wird (Licht brennt noch). Anschließend schläft das Gerät bis zum nächsten Fensterstart durch, was massiv Akku spart.
- **Manueller Start:** Nach einer Erstinstallation oder tiefen Systemupdates wird empfohlen, die App einmal zu öffnen, um den Dienst-Lebenszyklus zu etablieren.

#### Akku, Alarme & Tarnung
- **Akku-Optimierung:** Für einen zuverlässigen Langzeitbetrieb ist es zwingend erforderlich, die Akku-Optimierung für diese App über den Setup-Tab zu deaktivieren.
- **Tarnmodus (API 26-28):** Auf älteren Android-Versionen (wie Android 9), die das Einschalten des Bildschirms während des Aktivitätsstarts erzwingen, dimmt die App ihre Fensterhelligkeit während des automatischen Aufnahmezyklus automatisch auf das Minimum und stellt sie danach wieder her.
- **Exakte Alarme:** Für Android 12-13 kann die Berechtigung `SCHEDULE_EXACT_ALARM` erforderlich sein. Auf Android 14+ greift der neue `USE_EXACT_ALARM`-Standard.

#### Build-Hinweise
Das Projekt ist auf **JDK 17** fixiert. Dies ist beabsichtigt, um eine stabile Kompilierung sicherzustellen, auch wenn das Build-System auf neueren JDK-Versionen läuft.

### Lizenz
Dieses Projekt steht unter der **GNU General Public License v3.0**. Weitere Details finden Sie in der [LICENSE](file:///C:/Users/Jellyfin/StudioProjects/Timelapper/LICENSE)-Datei.
