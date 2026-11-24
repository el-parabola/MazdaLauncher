package it.gabriele.mazdalauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // ---------- Icone del dock (configurabili con pressione lunga) ----------
    static final class Slot {
        final String key, label;
        final int icon;
        final String[] defaults; // pacchetti provati in ordine se l'utente non ha scelto
        Slot(String key, String label, int icon, String... defaults) {
            this.key = key; this.label = label; this.icon = icon; this.defaults = defaults;
        }
    }

    private static final Slot[] SLOTS = {
        new Slot("nav", "Navigatore", R.drawable.ic_nav,
                "com.google.android.apps.maps", "com.waze"),
        new Slot("music", "Musica", R.drawable.ic_music,
                "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app"),
        new Slot("phone", "Telefono", R.drawable.ic_phone,
                "com.syu.bt", "com.microntek.bluetooth", "com.nwd.bt.music", "com.android.bluetooth"),
        new Slot("carplay", "CarPlay / Auto", R.drawable.ic_car,
                "com.zjinnova.zlink", "com.zjinnova.zlink.android"),
        new Slot("radio", "Radio", R.drawable.ic_radio,
                "com.syu.radio", "com.microntek.radio", "com.android.fmradio"),
        new Slot("settings", "Impostazioni", R.drawable.ic_settings,
                "com.android.settings"),
    };
    private static final int SLOT_NAV = 0, SLOT_MUSIC = 1;
    private static final String MAPS_PKG = "com.google.android.apps.maps";
    private static final int REQ_LOCATION = 1;
    private static final int REQ_VOICE = 2;
    private static final int REQ_AUDIO = 3;
    private SpeechRecognizer recognizer;

    static final class AppEntry {
        final String label;
        final ComponentName component;
        final Drawable icon;
        AppEntry(String label, ComponentName component, Drawable icon) {
            this.label = label; this.component = component; this.icon = icon;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private PackageManager pm;
    private View[] dockItems;

    private View drawer;
    private GridView grid;
    private AppAdapter gridAdapter;

    // mappa
    private MapView map;
    private MyLocationNewOverlay myLocation;
    private TextView homeText, workText;
    private View routeBar;
    private TextView routeText;
    private Polyline routeLine;

    // musica
    private ImageView mediaArt;
    private TextView mediaSource, mediaTitle, mediaArtist, mediaElapsed, mediaTotal;
    private ProgressBar mediaProgress;
    private ImageButton btnPlay;
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean sessionsListenerOn = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::onSessionsChanged;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateMediaUi(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMediaUi(); }
        @Override public void onSessionDestroyed() { setController(null); refreshSessions(); }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            updateProgress();
            main.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            bindDock();
            if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
        }
    };

    // ---------- Ciclo di vita ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        pm = getPackageManager();
        msm = getSystemService(MediaSessionManager.class);

        // osmdroid va configurato prima di creare la MapView
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        File osmBase = new File(getCacheDir(), "osmdroid");
        Configuration.getInstance().setOsmdroidBasePath(osmBase);
        Configuration.getInstance().setOsmdroidTileCache(new File(osmBase, "tiles"));

        setContentView(R.layout.activity_main);

        setupMap();
        setupMedia();
        setupDrawer();

        loadApps();
        buildDock();
        bindDock();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        if (hasLocationPermission()) {
            startLocation();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }

        if (!prefs.getBoolean("hint_shown", false)) {
            Toast.makeText(this, "Tieni premuta un'icona in basso per cambiare app",
                    Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean("hint_shown", true).apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (hasLocationPermission()) startLocation();
        refreshSessions(); // anche al ritorno dalle impostazioni dei permessi
        main.removeCallbacks(progressTicker);
        main.post(progressTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(progressTicker);
        IGeoPoint c = map.getMapCenter();
        prefs.edit()
                .putFloat("map_lat", (float) c.getLatitude())
                .putFloat("map_lon", (float) c.getLongitude())
                .putFloat("map_zoom", (float) map.getZoomLevelDouble())
                .apply();
        if (myLocation != null) myLocation.disableMyLocation();
        map.onPause();
    }

    /** Quando un'altra app (es. Maps) passa in primo piano, libera la RAM della mappa del launcher. */
    @Override
    protected void onStop() {
        super.onStop();
        map.getTileProvider().clearTileCache();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) map.getTileProvider().clearTileCache();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideDrawer(); // tasto Home: torna alla schermata principale
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer();
        // altrimenti niente: siamo il launcher
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        if (sessionsListenerOn) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        setController(null);
        if (recognizer != null) recognizer.destroy();
        map.onDetach();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation();
        if (requestCode == REQ_AUDIO && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) startInAppRecognition();
    }

    // ---------- Mappa ----------
    private void setupMap() {
        FrameLayout panel = findViewById(R.id.map_panel);
        panel.setClipToOutline(true); // angoli arrotondati anche sulla mappa

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        // mappa scura, in tinta con il resto
        map.getOverlayManager().getTilesOverlay().setColorFilter(TilesOverlay.INVERT_COLORS);
        map.getController().setZoom((double) prefs.getFloat("map_zoom", 16f));
        map.getController().setCenter(new GeoPoint(
                prefs.getFloat("map_lat", 41.1171f), prefs.getFloat("map_lon", 16.8719f)));

        GpsMyLocationProvider provider = new GpsMyLocationProvider(this);
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER);
        myLocation = new MyLocationNewOverlay(provider, map);
        Bitmap arrow = makeArrow();
        myLocation.setDirectionArrow(arrow, arrow);
        map.getOverlays().add(myLocation);

        findViewById(R.id.btn_zoom_in).setOnClickListener(v -> map.getController().zoomIn());
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> map.getController().zoomOut());
        findViewById(R.id.btn_center).setOnClickListener(v -> {
            if (!hasLocationPermission()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
                return;
            }
            myLocation.enableFollowLocation();
            GeoPoint me = myLocation.getMyLocation();
            if (me != null) map.getController().animateTo(me);
            else Toast.makeText(this, "Cerco il segnale GPS…", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.search).setOnClickListener(v -> askDestination());
        findViewById(R.id.btn_voice).setOnClickListener(v -> startVoice());
        routeBar = findViewById(R.id.route_bar);
        routeText = findViewById(R.id.route_text);
        findViewById(R.id.route_clear).setOnClickListener(v -> clearRoute());

        homeText = findViewById(R.id.place_home_text);
        workText = findViewById(R.id.place_work_text);
        View home = findViewById(R.id.place_home);
        View work = findViewById(R.id.place_work);
        home.setOnClickListener(v -> goToPlace("home", "Casa"));
        work.setOnClickListener(v -> goToPlace("work", "Lavoro"));
        home.setOnLongClickListener(v -> { editPlace("home", "Casa"); return true; });
        work.setOnLongClickListener(v -> { editPlace("work", "Lavoro"); return true; });
        bindPlaces();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        try {
            myLocation.enableMyLocation();
            myLocation.enableFollowLocation();
        } catch (SecurityException ignored) { }
    }

    /** Freccia rossa con bordo bianco per la posizione. */
    private Bitmap makeArrow() {
        int s = dp(40);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path p = new Path();
        p.moveTo(s * 0.5f, s * 0.08f);
        p.lineTo(s * 0.85f, s * 0.92f);
        p.lineTo(s * 0.5f, s * 0.74f);
        p.lineTo(s * 0.15f, s * 0.92f);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFF3B55);
        c.drawPath(p, fill);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(Color.WHITE);
        c.drawPath(p, stroke);
        return b;
    }

    private void bindPlaces() {
        String h = prefs.getString("place_home", "");
        String w = prefs.getString("place_work", "");
        homeText.setText(h.isEmpty() ? "Casa" : "Casa · " + h);
        workText.setText(w.isEmpty() ? "Lavoro" : "Lavoro · " + w);
    }

    private void goToPlace(String key, String label) {
        String addr = prefs.getString("place_" + key, "");
        if (addr.isEmpty()) editPlace(key, label); else routeTo(addr);
    }

    private void editPlace(String key, String label) {
        EditText input = makeInput("Indirizzo di " + label.toLowerCase(Locale.ITALY));
        input.setText(prefs.getString("place_" + key, ""));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setView(wrap(input))
                .setPositiveButton("Salva", (d, w) -> {
                    prefs.edit().putString("place_" + key, input.getText().toString().trim()).apply();
                    bindPlaces();
                })
                .setNeutralButton("Cancella", (d, w) -> {
                    prefs.edit().putString("place_" + key, "").apply();
                    bindPlaces();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void askDestination() {
        EditText input = makeInput("Indirizzo, luogo o attività");
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Dove vuoi andare?")
                .setView(wrap(input))
                .setPositiveButton("Naviga", (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (!q.isEmpty()) routeTo(q);
                })
                .setNegativeButton("Annulla", null)
                .create();
        input.setOnEditorActionListener((v, actionId, ev) -> {
            String q = input.getText().toString().trim();
            if (!q.isEmpty()) { dialog.dismiss(); routeTo(q); }
            return true;
        });
        dialog.show();
        input.requestFocus();
    }

    /** Apre il navigatore scelto nel dock con la destinazione già impostata. */
    private void navigateTo(String destination) {
        AppEntry nav = resolveSlot(SLOTS[SLOT_NAV]);
        String pkg = nav != null ? nav.component.getPackageName() : null;
        Uri uri = MAPS_PKG.equals(pkg)
                ? Uri.parse("google.navigation:q=" + Uri.encode(destination))
                : Uri.parse("geo:0,0?q=" + Uri.encode(destination));
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Nessun navigatore installato", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- Assistente vocale ----------
    /** Usa il riconoscimento vocale di Google (italiano); se manca, prova l'assistente di sistema. */
    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dimmi cosa vuoi fare");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            startInAppRecognition();
        }
    }

    /** Riconoscimento senza schermata di sistema: serve un servizio di riconoscimento (app Google). */
    private void startInAppRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Riconoscimento vocale mancante")
                    .setMessage("Su questa autoradio non c'\u00e8 nessun servizio di riconoscimento vocale. "
                            + "Installa l'app Google (com.google.android.googlequicksearchbox) "
                            + "e riprova.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                Toast.makeText(MainActivity.this, "Ti ascolto\u2026", Toast.LENGTH_SHORT).show();
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) handleVoice(r.get(0));
            }
            @Override public void onError(int code) {
                Toast.makeText(MainActivity.this, code == SpeechRecognizer.ERROR_NO_MATCH
                        || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Non ho sentito nulla" : "Errore riconoscimento vocale (" + code + ")",
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }
        });
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT"));
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) handleVoice(r.get(0));
    }

    private static String after(String text, String... prefixes) {
        for (String p : prefixes)
            if (text.startsWith(p + " ")) return text.substring(p.length() + 1).trim();
        return null;
    }

    private void handleVoice(String raw) {
        String t = raw.toLowerCase(Locale.ITALY).trim();
        String arg;

        if (t.matches(".*\\b(pausa|metti in pausa|stop|ferma)\\b.*")) { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return; }
        if (t.matches(".*\\b(prossim[ao]|successiv[ao]|avanti|salta)\\b.*")) { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return; }
        if (t.matches(".*\\b(precedente|indietro)\\b.*")) { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return; }
        if (t.matches("(play|riproduci|riprendi|metti la musica|musica|suona)")) { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return; }

        if (t.matches("(naviga |portami |andiamo |vai )?(a |in )?casa")) { goToPlace("home", "Casa"); return; }
        if (t.matches("(naviga |portami |andiamo |vai )?(a |in |al )?(lavoro|ufficio)")) { goToPlace("work", "Lavoro"); return; }

        arg = after(t, "naviga verso", "naviga a", "naviga in", "naviga", "portami a", "portami in",
                "portami", "andiamo a", "andiamo in", "vai a", "vai in", "indicazioni per");
        if (arg != null && !arg.isEmpty()) { routeTo(arg); return; }

        arg = after(t, "chiama", "telefona a", "telefona");
        if (arg != null) {
            String digits = arg.replaceAll("[^0-9+]", "");
            if (digits.length() >= 3) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                launchSlot(2); // Telefono
                Toast.makeText(this, "Per chiamare un contatto usa l'app Telefono", Toast.LENGTH_LONG).show();
            }
            return;
        }

        arg = after(t, "apri", "avvia", "lancia");
        if (arg != null) {
            AppEntry best = null;
            for (AppEntry e : apps) {
                String l = e.label.toLowerCase(Locale.ITALY);
                if (l.equals(arg)) { best = e; break; }
                if (best == null && (l.contains(arg) || arg.contains(l))) best = e;
            }
            if (best != null) { launch(best); return; }
        }
        if (t.contains("navigatore") || t.contains("mappa")) { launchSlot(SLOT_NAV); return; }
        if (t.contains("musica") || t.contains("spotify")) { launchSlot(SLOT_MUSIC); return; }
        if (t.contains("radio")) { launchSlot(4); return; }
        if (t.contains("telefono")) { launchSlot(2); return; }

        Toast.makeText(this, "Non ho capito: \u201c" + raw + "\u201d", Toast.LENGTH_LONG).show();
    }

    private void launchSlot(int idx) {
        AppEntry e = resolveSlot(SLOTS[idx]);
        if (e != null) launch(e);
        else Toast.makeText(this, "Nessuna app per " + SLOTS[idx].label, Toast.LENGTH_SHORT).show();
    }

    // ---------- Percorso nel launcher (prototipo online) ----------
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=";
    private static final String VALHALLA = "https://valhalla.openstreetmap.de/route";

    /** Calcola il percorso dalla posizione attuale alla destinazione e lo disegna sulla mappa. */
    private void routeTo(String destination) {
        GeoPoint me = myLocation == null ? null : myLocation.getMyLocation();
        if (me == null) {
            Toast.makeText(this, "Cerco il segnale GPS…", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Calcolo il percorso…", Toast.LENGTH_SHORT).show();
        final String[] stage = {"indirizzo (rete)"};
        new Thread(() -> {
            try {
                JSONArray hits = new JSONArray(httpRequest(NOMINATIM + Uri.encode(destination), null));
                if (hits.length() == 0) {
                    main.post(() -> showRouteError("indirizzo", "nessun risultato per \u201c" + destination + "\u201d"));
                    return;
                }
                JSONObject hit = hits.getJSONObject(0);
                double dLat = Double.parseDouble(hit.getString("lat"));
                double dLon = Double.parseDouble(hit.getString("lon"));

                stage[0] = "percorso (rete)";
                String body = String.format(Locale.US,
                        "{\"locations\":[{\"lat\":%f,\"lon\":%f},{\"lat\":%f,\"lon\":%f}],"
                        + "\"costing\":\"auto\",\"directions_options\":{\"units\":\"kilometers\"}}",
                        me.getLatitude(), me.getLongitude(), dLat, dLon);
                JSONObject r = new JSONObject(httpRequest(VALHALLA, body));
                if (r.has("error") || !r.has("trip")) {
                    main.post(() -> showRouteError("percorso", r.optString("error", "risposta senza percorso")));
                    return;
                }
                JSONObject trip = r.getJSONObject("trip");
                JSONObject summary = trip.getJSONObject("summary");
                double km = summary.getDouble("length");
                double secs = summary.getDouble("time");
                ArrayList<GeoPoint> pts = new ArrayList<>();
                JSONArray legs = trip.getJSONArray("legs");
                for (int i = 0; i < legs.length(); i++) {
                    pts.addAll(decodePolyline(legs.getJSONObject(i).getString("shape")));
                }
                main.post(() -> showRoute(pts, km * 1000.0, secs, destination));
            } catch (Exception e) {
                String why = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                main.post(() -> showRouteError(stage[0], why));
            }
        }, "route").start();
    }

    /** Mostra il motivo nella barra del percorso, senza aprire altre app. */
    private void showRouteError(String stage, String why) {
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        routeText.setText("Percorso non riuscito · " + stage + " · " + why);
        routeBar.setVisibility(View.VISIBLE);
        map.invalidate();
    }

    /** GET se body è null, altrimenti POST con corpo JSON. */
    private SSLSocketFactory valhallaSocketFactory;

    /**
     * Valhalla usa una catena con radici ISRG Root X2 / Root YE, assenti nell'elenco di Android 9.
     * Le aggiungiamo solo per questo server; la verifica del certificato resta attiva.
     */
    private SSLSocketFactory valhallaSockets() throws Exception {
        if (valhallaSocketFactory == null) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("isrg_root_x2", loadCert(cf, R.raw.isrg_root_x2));
            ks.setCertificateEntry("root_ye", loadCert(cf, R.raw.root_ye));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            valhallaSocketFactory = ctx.getSocketFactory();
        }
        return valhallaSocketFactory;
    }

    private java.security.cert.Certificate loadCert(CertificateFactory cf, int resId) throws Exception {
        try (InputStream in = getResources().openRawResource(resId)) {
            return cf.generateCertificate(in);
        }
    }

    private String httpRequest(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (url.startsWith(VALHALLA) && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(valhallaSockets());
        }
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "MazdaLauncher/2.5");
        c.setRequestProperty("Accept-Language", "it");
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        // Valhalla risponde con errori HTTP 400 che hanno comunque un JSON utile
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Decodifica una polilinea Google/Valhalla a precisione 6 (1e-6 gradi). */
    private static List<GeoPoint> decodePolyline(String enc) {
        List<GeoPoint> out = new ArrayList<>();
        int index = 0, lat = 0, lng = 0;
        while (index < enc.length()) {
            int b, shift = 0, result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            shift = 0;
            result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            out.add(new GeoPoint(lat / 1e6, lng / 1e6));
        }
        return out;
    }

    private void showRoute(List<GeoPoint> pts, double meters, double secs, String dest) {
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline();
        routeLine.setPoints(pts);
        routeLine.getOutlinePaint().setColor(0xFFB3112E);
        routeLine.getOutlinePaint().setStrokeWidth(dp(7));
        map.getOverlays().add(0, routeLine);

        String km = String.format(Locale.ITALY, "%.1f km", meters / 1000.0);
        long min = Math.max(1, Math.round(secs / 60.0));
        routeText.setText(dest + " · " + km + " · " + min + " min");
        routeBar.setVisibility(View.VISIBLE);
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, dp(60));
        map.invalidate();
    }

    private void clearRoute() {
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        routeBar.setVisibility(View.GONE);
        map.invalidate();
    }

    private EditText makeInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        input.setTextSize(20);
        return input;
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = dp(20);
        f.setPadding(p, dp(8), p, 0);
        f.addView(v);
        return f;
    }

    // ---------- App ----------
    private void loadApps() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.add(new AppEntry(ri.loadLabel(pm).toString(),
                    new ComponentName(pkg, ri.activityInfo.name), ri.loadIcon(pm)));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private AppEntry findByComponent(ComponentName cn) {
        if (cn == null) return null;
        for (AppEntry e : apps) if (e.component.equals(cn)) return e;
        // l'activity potrebbe essere cambiata dopo un aggiornamento: cerca per pacchetto
        return findByPackage(cn.getPackageName());
    }

    private AppEntry findByPackage(String pkg) {
        for (AppEntry e : apps) if (e.component.getPackageName().equals(pkg)) return e;
        return null;
    }

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Impossibile aprire " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchPackage(String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---------- Dock ----------
    private void buildDock() {
        LinearLayout dock = findViewById(R.id.dock);
        LayoutInflater inf = getLayoutInflater();
        int total = SLOTS.length + 1;
        dockItems = new View[total];
        for (int i = 0; i < total; i++) {
            View item = inf.inflate(R.layout.dock_item, dock, false);
            ImageView icon = item.findViewById(R.id.dock_icon);
            TextView label = item.findViewById(R.id.dock_label);
            final int idx = i;
            if (i < SLOTS.length) {
                icon.setImageResource(SLOTS[i].icon);
                label.setText(SLOTS[i].label);
                item.setOnClickListener(v -> onDockClick(idx));
                item.setOnLongClickListener(v -> { pickApp(idx); return true; });
            } else {
                icon.setImageResource(R.drawable.ic_apps);
                label.setText("Tutte le app");
                item.setOnClickListener(v -> showDrawer());
            }
            dock.addView(item);
            dockItems[i] = item;
        }
    }

    private AppEntry resolveSlot(Slot s) {
        String saved = prefs.getString("slot_" + s.key, null);
        if (saved == null) {
            for (String pkg : s.defaults) {
                AppEntry e = findByPackage(pkg);
                if (e != null) return e;
            }
            return null;
        }
        if (saved.isEmpty()) return null;
        return findByComponent(ComponentName.unflattenFromString(saved));
    }

    /** Le icone senza app assegnata appaiono attenuate. */
    private void bindDock() {
        for (int i = 0; i < SLOTS.length; i++) {
            dockItems[i].setAlpha(resolveSlot(SLOTS[i]) != null ? 1f : 0.4f);
        }
    }

    private void onDockClick(int idx) {
        AppEntry e = resolveSlot(SLOTS[idx]);
        if (e != null) launch(e); else pickApp(idx);
    }

    private void pickApp(int idx) {
        final Slot s = SLOTS[idx];
        final List<AppEntry> list = new ArrayList<>(apps);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("App per \"" + s.label + "\"")
                .setAdapter(new AppAdapter(list, R.layout.item_app_row), (d, which) -> {
                    prefs.edit().putString("slot_" + s.key,
                            list.get(which).component.flattenToString()).apply();
                    bindDock();
                })
                .setNeutralButton("Svuota", (d, w) -> {
                    prefs.edit().putString("slot_" + s.key, "").apply();
                    bindDock();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    // ---------- Cassetto app ----------
    private void setupDrawer() {
        drawer = findViewById(R.id.drawer);
        grid = findViewById(R.id.grid);
        findViewById(R.id.drawer_close).setOnClickListener(v -> hideDrawer());
        grid.setOnItemClickListener((p, v, pos, id) -> { launch(apps.get(pos)); hideDrawer(); });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            openAppInfo(apps.get(pos).component.getPackageName());
            return true;
        });
    }

    private void showDrawer() {
        if (gridAdapter == null) {
            gridAdapter = new AppAdapter(apps, R.layout.item_app);
            grid.setAdapter(gridAdapter);
        }
        grid.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
    }

    private void hideDrawer() {
        if (drawer != null) drawer.setVisibility(View.GONE);
    }

    // ---------- Musica ----------
    private void setupMedia() {
        findViewById(R.id.media_panel).setOnClickListener(v -> onMediaPanelClick());
        mediaArt = findViewById(R.id.media_art);
        mediaArt.setClipToOutline(true);
        mediaSource = findViewById(R.id.media_source);
        mediaTitle = findViewById(R.id.media_title);
        mediaArtist = findViewById(R.id.media_artist);
        mediaProgress = findViewById(R.id.media_progress);
        mediaElapsed = findViewById(R.id.media_elapsed);
        mediaTotal = findViewById(R.id.media_total);
        btnPlay = findViewById(R.id.btn_play);

        btnPlay.setOnClickListener(v -> playPause());
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
            else sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        });
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
            else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        });
    }

    private ComponentName listener() {
        return new ComponentName(this, MediaListenerService.class);
    }

    private boolean hasNotificationAccess() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(listener());
    }

    private void refreshSessions() {
        if (msm == null || !hasNotificationAccess()) {
            setController(null);
            return;
        }
        try {
            if (!sessionsListenerOn) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listener(), main);
                sessionsListenerOn = true;
            }
            onSessionsChanged(msm.getActiveSessions(listener()));
        } catch (SecurityException e) {
            setController(null);
        }
    }

    private void onSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        setController(pick);
    }

    private void setController(MediaController c) {
        if (controller != null && c != null
                && controller.getSessionToken().equals(c.getSessionToken())) {
            updateMediaUi();
            return;
        }
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = c;
        if (controller != null) controller.registerCallback(mediaCallback, main);
        updateMediaUi();
    }

    private boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private void updateMediaUi() {
        if (mediaTitle == null) return;
        if (controller == null) {
            boolean access = hasNotificationAccess();
            mediaSource.setText("Musica");
            mediaTitle.setText(access ? "Nessun brano in riproduzione" : "Tocca qui per vedere il brano");
            mediaArtist.setText(access ? "Avvia la musica da un'app" : "Serve l'accesso alle notifiche");
            showArtPlaceholder();
            btnPlay.setImageResource(R.drawable.ic_play);
            updateProgress();
            return;
        }
        MediaMetadata md = controller.getMetadata();
        String title = null, artist = null;
        Bitmap art = null;
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        String app = appLabel(controller.getPackageName());
        mediaSource.setText(app);
        mediaTitle.setText(title != null ? title : app);
        mediaArtist.setText(artist != null ? artist : "");
        if (art != null) {
            mediaArt.setImageTintList(null);
            mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mediaArt.setPadding(0, 0, 0, 0);
            mediaArt.setImageBitmap(art);
        } else {
            showArtPlaceholder();
        }
        btnPlay.setImageResource(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        updateProgress();
    }

    private void updateProgress() {
        if (mediaProgress == null) return;
        long dur = 0, pos = 0;
        if (controller != null) {
            MediaMetadata md = controller.getMetadata();
            if (md != null) dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            PlaybackState st = controller.getPlaybackState();
            if (st != null) {
                pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING) {
                    long dt = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
                    pos += (long) (dt * st.getPlaybackSpeed());
                }
            }
        }
        if (dur > 0) {
            pos = Math.max(0, Math.min(pos, dur));
            mediaProgress.setProgress((int) (pos * 1000 / dur));
            mediaElapsed.setText(formatTime(pos));
            mediaTotal.setText(formatTime(dur));
        } else {
            mediaProgress.setProgress(0);
            mediaElapsed.setText("");
            mediaTotal.setText("");
        }
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ITALY, "%d:%02d", s / 60, s % 60);
    }

    private void showArtPlaceholder() {
        int p = dp(36);
        mediaArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mediaArt.setPadding(p, p, p, p);
        mediaArt.setImageResource(R.drawable.ic_music);
        mediaArt.setImageTintList(ColorStateList.valueOf(0xFF5F6368));
    }

    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void playPause() {
        if (controller != null) {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    /** Funziona anche senza permesso: comanda l'ultimo lettore attivo. */
    private void sendMediaKey(int code) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void onMediaPanelClick() {
        if (controller != null) {
            launchPackage(controller.getPackageName());
        } else if (!hasNotificationAccess()) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(this, "Attiva \"Mazda Launcher\"", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Impostazioni → App → Accesso speciale → Accesso notifiche",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            AppEntry music = resolveSlot(SLOTS[SLOT_MUSIC]);
            if (music != null) launch(music);
        }
    }

    // ---------- Utilità ----------
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> items;
        private final int layout;

        AppAdapter(List<AppEntry> items, int layout) { this.items = items; this.layout = layout; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            AppEntry e = items.get(position);
            ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.app_label)).setText(e.label);
            return v;
        }
    }
}
