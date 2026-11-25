package it.gabriele.mazdalauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Riconnessione rapida al Wi-Fi (hotspot del telefono).
 * Finché il launcher è aperto e il Wi-Fi non è connesso: accende il Wi-Fi se spento,
 * avvia una scansione e forza la connessione alla rete già salvata
 * (prima l'ultima usata, poi la più forte tra quelle visibili).
 * Su Android 9 (targetSdk 28) queste API sono ancora consentite.
 */
class WifiHelper {
    private static final long RETRY_MS = 6000;

    private final Context ctx;
    private final WifiManager wm;
    private final ConnectivityManager cm;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running;
    private int attempt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { step(); } catch (Exception ignored) { }
            h.postDelayed(this, RETRY_MS);
        }
    };

    WifiHelper(Context c, SharedPreferences prefs) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
        wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void start() {
        if (running || wm == null) return;
        running = true;
        attempt = 0;
        h.post(tick);
    }

    void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    /** Da chiamare anche a schermo acceso / ritorno in primo piano per scattare subito. */
    void kick() {
        if (!running) { start(); return; }
        attempt = 0;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private boolean wifiBusyOrUp() {
        NetworkInfo ni = cm == null ? null : cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return ni != null && ni.isConnectedOrConnecting();
    }

    private void step() {
        if (!wm.isWifiEnabled()) {
            wm.setWifiEnabled(true);
            return;
        }
        if (wifiBusyOrUp()) {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getSSID() != null && !"<unknown ssid>".equals(wi.getSSID())
                    && wi.getNetworkId() != -1) {
                prefs.edit().putString("wifi_last", wi.getSSID()).apply();
            }
            attempt = 0;
            return;
        }
        List<WifiConfiguration> conf = wm.getConfiguredNetworks();
        if (conf == null || conf.isEmpty()) return;
        List<ScanResult> scan = wm.getScanResults();
        String last = prefs.getString("wifi_last", "");

        // ordine: ultima rete usata, poi la più forte tra le visibili; ruota se non riesce
        WifiConfiguration best = null;
        int bestLevel = Integer.MIN_VALUE;
        for (WifiConfiguration c : conf) {
            if (c.SSID == null) continue;
            int level = Integer.MIN_VALUE;
            if (scan != null) {
                for (ScanResult r : scan) {
                    if (("\"" + r.SSID + "\"").equals(c.SSID) && r.level > level) level = r.level;
                }
            }
            if (c.SSID.equals(last)) level = Math.max(level, 0) + 1000; // preferita
            if (level == Integer.MIN_VALUE) continue; // non visibile
            if (attempt % 4 == 3 && c.SSID.equals(last)) continue; // ogni tanto prova le altre
            if (level > bestLevel) { bestLevel = level; best = c; }
        }
        if (best == null) {
            // nessuna rete nota visibile: nuova scansione e riprova
            wm.startScan();
            attempt++;
            return;
        }
        wm.enableNetwork(best.networkId, true);
        wm.reconnect();
        attempt++;
        if (attempt % 3 == 0) wm.startScan();
    }
}
