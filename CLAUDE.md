# Contesto del progetto – Mazda Launcher

Launcher Android personalizzato per l'autoradio Android della Mazda 2 (2019, 1.5 benzina) di Gabriele. Rispondi in italiano.

## Hardware di destinazione
- Autoradio TIEYEZ 12,3", modello AJ002-OB, piattaforma 8581, versione MCU ...WJ_25.03.05_399
- SoC MediaTek MT8163 (4x Cortex-A53 1,3 GHz), GPU Mali-T720: poco potente, quindi tenere l'app leggera
- Schermo **1920x720** orizzontale, **Android 9 (API 28)** anche se dichiara "Android 16", **senza root**
- **Nessuna porta USB** e niente ADB: le Opzioni sviluppatore sono bloccate da una password del produttore
- Ha il Wi-Fi e i Google Play Services

## Come si compila e si installa (non cambiare senza motivo)
- Qui non c'è l'SDK Android: la compilazione la fa **GitHub Actions** (`.github/workflows/build.yml`, Gradle 8.9, AGP 8.5.2, JDK 17, `gradle assembleDebug`)
- Ogni push crea una GitHub Release `v<run_number>` con l'asset `MazdaLauncher.apk`
- Sull'autoradio l'APK si installa dal browser via Wi-Fi, dal link fisso:
  `https://github.com/<utente>/MazdaLauncher/releases/latest/download/MazdaLauncher.apk`
  Per questo il repository deve restare **pubblico** e il nome dell'asset non deve cambiare
- La firma usa la chiave fissa `app/launcher.keystore` (password `mazda2019`), così ogni nuova versione si installa sopra la precedente. **Non rigenerarla.**
- Dopo ogni modifica: commit e push, poi controlla che il workflow Actions passi. Se fallisce, leggi il log e correggi.
- Alza `versionCode`/`versionName` in `app/build.gradle` quando rilasci modifiche visibili.

## Scelte già fatte con l'utente (versione 2, design attuale)
- La v1 a schede colorate è stata **scartata**: all'utente non piaceva.
- Stile **ispirato a Mazda Connect**: fondo grafite scuro con gradiente radiale, pannelli con bordo sottile (#1FFFFFFF) e angoli da 14dp, accento **rosso Mazda #B3112E**, icone monocromatiche (#D7DBDF), testi chiari o grigi (#9AA0A6). Riferimento visivo: `anteprima.png`.
- Solo Java e framework Android. Unica dipendenza: **osmdroid 6.1.20** per la mappa. minSdk e targetSdk 28.
- Disposizione:
  - **In alto a sinistra** (peso 1.65): **mappa OpenStreetMap** scura (colori invertiti), freccia rossa della posizione GPS che segue l'auto, barra "Dove vuoi andare?", scorciatoie **Casa** e **Lavoro**, pulsanti centra/zoom
  - **In alto a destra** (peso 1): **brano in riproduzione**: nome dell'app in rosso, copertina, titolo, artista, barra di avanzamento con i tempi, ⏮ ⏯ ⏭ (play dentro un anello rosso)
  - **Dock in basso** (92dp): Navigatore, Musica, Telefono, CarPlay/Auto, Radio, Impostazioni, Tutte le app; a destra un orologio piccolo con la data
- Percorso nel launcher (prototipo, v2.4): geocodifica con Nominatim e percorso con Valhalla (FOSSGIS, valhalla1.openstreetmap.de; OSRM demo non è accettato dal certificato dell'unità), solo online (Wi-Fi). Niente voce né svolte: si vede la linea e distanza/tempo. Se la rete manca o fallisce, ricade sul Navigatore del dock (`google.navigation:q=` per Google Maps, `geo:0,0?q=` per le altre). Il dock Navigatore resta esterno.
- Casa/Lavoro: tocco = naviga (se vuoto chiede l'indirizzo), pressione lunga = modifica. Salvati in prefs `place_home` / `place_work`
- Dock configurabile: **pressione lunga = scegli l'app** (prefs `launcher`, chiave `slot_<key>`; stringa vuota = svuotata, chiave assente = pacchetti predefiniti). Le icone senza app sono attenuate.
- Musica: MediaSessionManager tramite `MediaListenerService` (serve l'accesso alle notifiche). Senza permesso i tasti usano `AudioManager.dispatchMediaKeyEvent`
- Cassetto "Tutte le app": griglia; tocco = apre l'app, pressione lunga = info app
- L'orologio grande non c'è più: l'utente ha scelto solo musica e mappa. Quello piccolo nel dock si può togliere se non gli piace.
- Funzioni scartate: velocità GPS, meteo

## File principali
- `app/src/main/java/it/gabriele/mazdalauncher/MainActivity.java`: tutta la logica
- `app/src/main/res/layout/activity_main.xml`, `dock_item.xml`, `item_app.xml`, `item_app_row.xml`; stili in `values/styles.xml`
- `LEGGIMI.md`: istruzioni per l'utente

## Stato
- Versione 2 (design Mazda Connect) scritta, **mai compilata né provata sull'autoradio**. Il primo passo è verificare che la build di GitHub Actions passi.
- Pacchetti predefiniti delle schede Telefono e Radio (com.syu.*, com.microntek.*) **ipotizzati**: vanno verificati sull'unità reale.
- Da verificare sull'unità: la mappa scura con colori invertiti è leggibile? Il GPS dà la posizione dentro osmdroid? Il permesso di posizione viene chiesto al primo avvio.
- Densità dello schermo sconosciuta (probabilmente 160 o 240 dpi): controllare che gli elementi entrino senza essere tagliati.
- L'utente prova l'autoradio con il quadro in ACC, a motore spento, per sessioni brevi.
