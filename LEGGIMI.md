# Mazda Launcher – per autoradio TIEYEZ 12,3" (1920x720, Android 9)

Launcher in stile Mazda Connect (scuro con accenti rossi), vedi `anteprima.png`:
- **in alto a sinistra**: mappa con la tua posizione, ricerca della destinazione, Casa e Lavoro
- **in alto a destra**: brano in riproduzione (copertina, titolo, avanzamento, ⏮ ⏯ ⏭)
- **in basso**: dock con Navigatore, Musica, Telefono, CarPlay/Auto, Radio, Impostazioni, Tutte le app, e un orologio piccolo

## 1. Compilare l'APK con GitHub (gratis, dal browser)
1. Crea un account su github.com, se non ce l'hai.
2. **New repository** → nome `MazdaLauncher` → **Public** (così l'autoradio può scaricare l'APK senza login) → Create.
3. Nel repository vuoto: **uploading an existing file** → trascina **tutto il contenuto** della cartella estratta dallo zip (non la cartella stessa) → *Commit changes*.
   - Se la cartella `.github` non viene caricata (su Mac e Windows è nascosta), crea il file a mano: **Add file → Create new file**, nome `.github/workflows/build.yml`, e incolla il contenuto di quel file.
4. Scheda **Actions**: parte "Compila APK" (3-5 minuti). Aspetta la spunta verde.

## 2. Installare sull'autoradio (senza USB, via Wi-Fi)
1. Collega l'autoradio al Wi-Fi o all'hotspot del telefono.
2. Nel browser dell'autoradio apri (al posto di TUONOME metti il tuo nome utente GitHub):
   `https://github.com/TUONOME/MazdaLauncher/releases/latest/download/MazdaLauncher.apk`
   Consiglio: salva il link nei preferiti, ti servirà per gli aggiornamenti.
3. Apri il file scaricato e installalo (consenti le "origini sconosciute" al browser se te lo chiede).
4. Premi **Home**: scegli **Mazda Launcher** → **Sempre**.
   Se non ti chiede niente: Impostazioni → App → App predefinite → App Home.
   Per tornare al launcher originale basta rifare questa scelta.

Senza browser sull'autoradio: scarica l'APK sul telefono e passalo all'autoradio con un'app tipo LocalSend (dal Play Store, va installata su entrambi), oppure su una microSD se l'autoradio ha lo slot.

## 3. Prime impostazioni
- Al primo avvio **consenti la posizione**, così la mappa ti segue.
- **Tieni premuta un'icona del dock** per scegliere quale app apre (per esempio l'app Bluetooth dell'autoradio per "Telefono"). "Svuota" la libera. Le icone senza app sono attenuate.
- **Casa / Lavoro**: al primo tocco ti chiede l'indirizzo. Dopo, il tocco avvia la navigazione e la pressione lunga lo modifica.
- **"Dove vuoi andare?"**: scrivi la destinazione e si apre il navigatore del dock (Maps o Waze) con la rotta già impostata.
- **Titolo e copertina del brano**: tocca il riquadro musica e attiva "Mazda Launcher" nell'accesso alle notifiche. Anche senza permesso i tasti ⏮ ⏯ ⏭ funzionano.
- In "Tutte le app": tocco = apre, pressione lunga = info app.

## Aggiornamenti
Ogni modifica caricata su GitHub ricompila l'APK e aggiorna lo stesso link. La chiave di firma è sempre la stessa, quindi la nuova versione si installa sopra la vecchia senza perdere le impostazioni.
