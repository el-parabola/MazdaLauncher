package it.gabriele.mazdalauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Dialogo minimo con un dongle ELM327 Bluetooth (profilo seriale SPP). Tutto in un thread. */
final class ObdClient {
    interface Listener {
        void onStatus(String status);
        /** I valori non disponibili sono NaN. */
        void onData(double coolant, double volt, double rpm, double speed, double maf);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] PIDS = {"0105", "0142", "010C", "010D", "0110"};

    private final BluetoothDevice device;
    private final Listener listener;
    private final Handler ui;
    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private Thread thread;

    ObdClient(BluetoothDevice device, Listener listener, Handler ui) {
        this.device = device;
        this.listener = listener;
        this.ui = ui;
    }

    boolean isRunning() { return running; }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "obd");
        thread.start();
    }

    void stop() {
        running = false;
        closeSocket();
        if (thread != null) thread.interrupt();
    }

    private void status(String s) { ui.post(() -> listener.onStatus(s)); }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    private void loop() {
        while (running) {
            try {
                status("Mi collego a " + safeName() + "…");
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP);
                socket = s;
                s.connect();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                for (String init : new String[]{"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"}) {
                    query(in, out, init, init.equals("ATZ") ? 4000 : 1500);
                }
                status("Collegato");
                poll(in, out);
            } catch (Exception e) {
                if (running) status("Dongle non raggiungibile (riprovo)");
            }
            closeSocket();
            for (int i = 0; i < 50 && running; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
        }
    }

    private String safeName() {
        try {
            String n = device.getName();
            return n != null ? n : device.getAddress();
        } catch (Exception e) {
            return "dongle";
        }
    }

    private void poll(InputStream in, OutputStream out) throws Exception {
        int[] fails = new int[PIDS.length];
        double[] v = new double[PIDS.length];
        int silent = 0;
        while (running) {
            int ok = 0;
            for (int i = 0; i < PIDS.length && running; i++) {
                v[i] = Double.NaN;
                if (fails[i] >= 3) continue; // PID non supportato dall'auto: lo salto
                int[] b = parse(query(in, out, PIDS[i], 2500), PIDS[i]);
                if (b == null) { fails[i]++; continue; }
                fails[i] = 0;
                ok++;
                switch (i) {
                    case 0: v[i] = b[0] - 40; break;
                    case 1: v[i] = b.length > 1 ? (b[0] * 256 + b[1]) / 1000.0 : Double.NaN; break;
                    case 2: v[i] = b.length > 1 ? (b[0] * 256 + b[1]) / 4.0 : Double.NaN; break;
                    case 3: v[i] = b[0]; break;
                    case 4: v[i] = b.length > 1 ? (b[0] * 256 + b[1]) / 100.0 : Double.NaN; break;
                }
            }
            // motore spento / quadro off: nessuna risposta; dopo qualche giro riconnetto
            silent = ok == 0 ? silent + 1 : 0;
            if (silent >= 6) throw new java.io.IOException("nessuna risposta");
            final double[] snap = v.clone();
            ui.post(() -> listener.onData(snap[0], snap[1], snap[2], snap[3], snap[4]));
            // se tutti i PID sono "non supportati", li riprovo ogni tanto
            boolean allFailed = true;
            for (int f : fails) if (f < 3) allFailed = false;
            if (allFailed) java.util.Arrays.fill(fails, 0);
        }
    }

    /** Invia un comando e legge fino al prompt '>'. */
    private String query(InputStream in, OutputStream out, String cmd, long timeoutMs) throws Exception {
        while (in.available() > 0) in.read(); // svuota
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && running) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == '>') break;
                sb.append((char) c);
            } else {
                Thread.sleep(15);
            }
        }
        return sb.toString().toUpperCase();
    }

    /** "41 05 7B" (con o senza spazi) → byte di dati dopo l'intestazione, oppure null. */
    static int[] parse(String resp, String pid) {
        String s = resp.replaceAll("[^0-9A-F]", "");
        // le parole come "SEARCHING" contengono lettere A-F: dopo il filtro cerchiamo comunque l'intestazione
        String head = "41" + pid.substring(2);
        int idx = s.indexOf(head);
        if (idx < 0) return null;
        String data = s.substring(idx + head.length());
        int n = data.length() / 2;
        if (n == 0) return null;
        n = Math.min(n, 4);
        int[] out = new int[n];
        try {
            for (int i = 0; i < n; i++) out[i] = Integer.parseInt(data.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
