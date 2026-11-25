package it.gabriele.mazdalauncher;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;

import java.util.Arrays;
import java.util.Locale;

/**
 * Test del microfono: registra 5 secondi, mostra il livello in tempo reale,
 * poi riascolta la registrazione e dà un giudizio (livello voce, rumore di fondo, saturazione).
 * Misura solo il microfono visibile ad Android: le chiamate Bluetooth possono usare un altro percorso.
 */
public class MicTest {
    public interface Listener {
        /** livello 0..100 durante la registrazione */
        void onLevel(int percent);
        /** testo di stato (fase, risultato) */
        void onStatus(String text);
        void onFinished();
    }

    private static final int RATE = 16000;
    private static final int SECONDS = 5;

    private final Handler main;
    private final Listener listener;
    private volatile boolean cancel;
    private Thread thread;

    public MicTest(Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
    }

    public boolean isRunning() { return thread != null && thread.isAlive(); }

    public void cancel() { cancel = true; }

    /** @param source MediaRecorder.AudioSource.MIC oppure VOICE_COMMUNICATION */
    public void start(final int source) {
        if (isRunning()) return;
        cancel = false;
        thread = new Thread(() -> run(source), "mic-test");
        thread.start();
    }

    private void status(final String s) { main.post(() -> listener.onStatus(s)); }

    private void run(int source) {
        AudioRecord rec = null;
        AudioTrack track = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) { status("Microfono non disponibile"); return; }
            rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                status("Microfono occupato o non disponibile");
                return;
            }
            short[] all = new short[RATE * SECONDS];
            int chunk = RATE / 10; // 100 ms
            int total = 0;
            double[] rmsDb = new double[SECONDS * 10];
            int nChunks = 0;
            short peak = 0;
            status("Parla normalmente… (5 s)");
            rec.startRecording();
            while (total + chunk <= all.length && !cancel) {
                int n = rec.read(all, total, chunk);
                if (n <= 0) break;
                double sum = 0;
                for (int i = total; i < total + n; i++) {
                    int v = all[i];
                    sum += (double) v * v;
                    int a = Math.abs(v);
                    if (a > peak) peak = (short) Math.min(32767, a);
                }
                total += n;
                double rms = Math.sqrt(sum / n);
                double db = toDb(rms);
                if (nChunks < rmsDb.length) rmsDb[nChunks++] = db;
                final int pct = (int) Math.max(0, Math.min(100, (db + 60) / 60 * 100));
                main.post(() -> listener.onLevel(pct));
            }
            rec.stop();
            if (cancel || total == 0 || nChunks == 0) { status("Test annullato"); return; }

            double[] sorted = Arrays.copyOf(rmsDb, nChunks);
            Arrays.sort(sorted);
            double noise = sorted[Math.max(0, nChunks / 10)];      // ~10° percentile = fondo
            double voice = sorted[Math.min(nChunks - 1, nChunks * 9 / 10)]; // ~90° = voce
            double peakDb = toDb(peak);
            String verdict = verdict(peakDb, voice, noise);
            final String summary = String.format(Locale.ITALY,
                    "Voce %.0f dB · fondo %.0f dB · picco %.0f dB\n%s", voice, noise, peakDb, verdict);

            main.post(() -> listener.onLevel(0));
            status("Riascolto…");
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, total * 2, AudioTrack.MODE_STATIC);
            track.write(all, 0, total);
            track.play();
            long end = System.currentTimeMillis() + total * 1000L / RATE + 300;
            while (System.currentTimeMillis() < end && !cancel) Thread.sleep(50);
            track.stop();
            status(summary);
        } catch (Exception e) {
            status("Errore microfono: " + e.getMessage());
        } finally {
            if (rec != null) { try { rec.release(); } catch (Exception ignored) { } }
            if (track != null) { try { track.release(); } catch (Exception ignored) { } }
            main.post(listener::onFinished);
        }
    }

    private static double toDb(double amplitude) {
        return 20 * Math.log10(Math.max(amplitude, 1) / 32768.0);
    }

    static String verdict(double peakDb, double voiceDb, double noiseDb) {
        if (peakDb < -50) return "Quasi silenzio: microfono scollegato, muto o non usato da Android.";
        if (peakDb > -1.5) return "Segnale saturo: guadagno troppo alto o microfono troppo vicino.";
        StringBuilder sb = new StringBuilder();
        if (voiceDb < -38) sb.append("Voce molto bassa: avvicina o orienta il microfono, o alza il guadagno. ");
        else if (voiceDb < -30) sb.append("Voce un po' bassa. ");
        else sb.append("Livello voce buono. ");
        double snr = voiceDb - noiseDb;
        if (snr < 15) sb.append("Rumore di fondo alto rispetto alla voce (controlla cavo e posizione).");
        else if (snr < 25) sb.append("Rumore di fondo medio.");
        else sb.append("Rumore di fondo basso.");
        return sb.toString();
    }
}
