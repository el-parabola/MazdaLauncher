package it.gabriele.mazdalauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
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
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.location.Location;
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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.format.DateUtils;
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
import java.util.Calendar;
import java.util.Set;
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
    private static final int REQ_MICTEST = 4;
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
    private View routeBar;
    private TextView routeText;

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
            onTick();
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

    /** Quando lo schermo si spegne (quadro off) salva in automatico dove è parcheggiata l'auto. */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { saveParking(true); }
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
        setupPages();

        loadApps();
        buildDock();
        bindDock();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
        IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        off.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(screenReceiver, off);

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
        ensureObd();
        main.removeCallbacks(progressTicker);
        main.post(progressTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(progressTicker);
        if (micTest != null) micTest.cancel();
        IGeoPoint c = map.getMapCenter();
        prefs.edit()
                .putFloat("map_lat", (float) c.getLatitude())
                .putFloat("map_lon", (float) c.getLongitude())
                .putFloat("map_zoom", (float) map.getZoomLevelDouble())
                .apply();
        if (myLocation != null && navDest == null) myLocation.disableMyLocation(); // in navigazione il GPS resta attivo
        main.removeCallbacks(frame);
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

    /** Barra di sistema nascosta: si mostra scorrendo dal bordo. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
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
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        if (obd != null) obd.stop();
        if (micTest != null) micTest.cancel();
        if (sessionsListenerOn) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        setController(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        map.onDetach();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation();
        if (requestCode == REQ_AUDIO && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) startInAppRecognition();
        if (requestCode == REQ_MICTEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) startMicTest();
    }

    // ---------- Mappa ----------
    private void setupMap() {
        View panel = findViewById(R.id.map_panel);
        panel.setClipToOutline(true); // angoli arrotondati anche sulla mappa

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        // dopo un pizzico con due dita, assesta lo zoom sul livello intero: i tile restano nitidi
        map.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_POINTER_DOWN) pinched = true;
            else if (a == android.view.MotionEvent.ACTION_DOWN) main.removeCallbacks(snapZoom);
            else if ((a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) && pinched) {
                pinched = false;
                main.removeCallbacks(snapZoom);
                main.postDelayed(snapZoom, 250);
            }
            return false;
        });
        map.setTilesScaledToDpi(false); // tile 256px a dimensione nativa: più nitidi (ingrandirli alla densità dello schermo li sgranava)
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        // mappa scura, in tinta con il resto
        // mappa minimal: grigia, invertita, con poco contrasto (strade chiare su fondo scuro)
        ColorMatrix mono = new ColorMatrix();
        mono.setSaturation(0f);
        ColorMatrix invert = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        mono.postConcat(invert);
        map.getOverlayManager().getTilesOverlay().setColorFilter(new ColorMatrixColorFilter(mono));
        map.getController().setZoom((double) prefs.getFloat("map_zoom", 16f));
        map.getController().setCenter(new GeoPoint(
                prefs.getFloat("map_lat", 41.1171f), prefs.getFloat("map_lon", 16.8719f)));

        GpsMyLocationProvider provider = new GpsMyLocationProvider(this);
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER);
        // myLocation serve solo per i dati GPS (invisibile); freccia e inseguimento sono fluidi, ridisegnati a ogni frame
        myLocation = new MyLocationNewOverlay(provider, map) {
            @Override
            public void onLocationChanged(Location loc, org.osmdroid.views.overlay.mylocation.IMyLocationProvider src) {
                // il fix di rete è impreciso (decine di metri): lo scarto se il GPS è attivo
                boolean net = LocationManager.NETWORK_PROVIDER.equals(loc.getProvider());
                long now = SystemClock.elapsedRealtime();
                if (!net) lastGpsAt = now;
                else if (now - lastGpsAt < 20_000) return;
                super.onLocationChanged(loc, src);
                main.post(() -> feedFix(loc));
            }
        };
        Bitmap blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        myLocation.setPersonIcon(blank);
        myLocation.setDirectionArrow(blank, blank);
        map.getOverlays().add(myLocation);
        arrowBmp = makeArrow();
        map.getOverlays().add(new org.osmdroid.views.overlay.Overlay() {
            @Override
            public void draw(Canvas c, MapView mv, boolean shadow) {
                if (shadow || dispLat == null) return;
                android.graphics.Point pt = mv.getProjection().toPixels(
                        new GeoPoint(dispLat, dispLon), null);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postTranslate(-arrowBmp.getWidth() / 2f, -arrowBmp.getHeight() / 2f);
                m.postRotate(dispBearing + mv.getMapOrientation());
                m.postTranslate(pt.x, pt.y);
                c.drawBitmap(arrowBmp, m, null);
            }

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e, MapView mv) {
                lastTouchAt = SystemClock.uptimeMillis();
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) following = false;
                return false;
            }
        });
        // bandierina della destinazione
        map.getOverlays().add(new org.osmdroid.views.overlay.Overlay() {
            private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c, MapView mv, boolean shadow) {
                if (shadow || navDest == null) return;
                android.graphics.Point pt = mv.getProjection().toPixels(navDest, null);
                fillP.setColor(0xFFE0304C);
                ringP.setColor(Color.WHITE);
                c.drawCircle(pt.x, pt.y, dp(12), ringP);
                c.drawCircle(pt.x, pt.y, dp(9), fillP);
                ringP.setColor(Color.WHITE);
                c.drawCircle(pt.x, pt.y, dp(3), ringP);
            }
        });

        findViewById(R.id.btn_zoom_in).setOnClickListener(v -> map.getController().zoomIn());
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> map.getController().zoomOut());
        findViewById(R.id.btn_center).setOnClickListener(v -> {
            if (!hasLocationPermission()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
                return;
            }
            following = true;
            if (dispLat != null) map.getController().animateTo(new GeoPoint(dispLat, dispLon));
            else Toast.makeText(this, "Cerco il segnale GPS…", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.search).setOnClickListener(v -> askDestination());
        findViewById(R.id.btn_voice).setOnClickListener(v -> startVoice());
        routeBar = findViewById(R.id.route_bar);
        routeText = findViewById(R.id.route_text);
        findViewById(R.id.route_clear).setOnClickListener(v -> stopNavigation());
        setupNavUi();

    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        try {
            myLocation.enableMyLocation();
            following = true;
        } catch (SecurityException ignored) { }
        main.removeCallbacks(frame);
        main.post(frame);
    }

    // ---------- Movimento fluido della freccia ----------
    private boolean pinched;
    private final Runnable snapZoom = () -> {
        double z = map.getZoomLevelDouble(), r = Math.round(z);
        if (Math.abs(z - r) > 0.02) map.getController().zoomTo(r, 200L);
    };
    private Bitmap arrowBmp;
    private long lastGpsAt = -1_000_000;
    private boolean following = true;
    private Double dispLat, dispLon;      // posizione mostrata
    private float dispBearing;
    private double fromLat, fromLon, toLat, toLon;
    private float fromBrg, toBrg;
    private long animStart;
    private static final long ANIM_MS = 1000;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (dispLat != null) {
                float t = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) ANIM_MS);
                dispLat = fromLat + (toLat - fromLat) * t;
                dispLon = fromLon + (toLon - fromLon) * t;
                float d = ((toBrg - fromBrg + 540f) % 360f) - 180f; // via più breve
                dispBearing = (fromBrg + d * t + 360f) % 360f;
                if (navDest != null && route != null) {
                    // in navigazione: mappa con la direzione di marcia in alto; dopo 12 s dal tocco riprende a seguire
                    if (!following && SystemClock.uptimeMillis() - lastTouchAt > 12_000) following = true;
                    if (following) map.setMapOrientation(-dispBearing);
                }
                if (following && t < 1f) map.getController().setCenter(new GeoPoint(dispLat, dispLon));
                map.invalidate();
            }
            main.postDelayed(this, 33); // ~30 fps, leggero per la GPU
        }
    };

    /** Nuovo fix GPS: parte dalla posizione mostrata e scorre verso la posizione prevista fra 1 s. */
    private void feedFix(Location loc) {
        navUpdate(loc.getLatitude(), loc.getLongitude(), loc.hasSpeed() ? loc.getSpeed() : 0f);
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        float brg = loc.hasBearing() ? loc.getBearing() : (dispLat == null ? 0f : toBrg);
        if (loc.hasSpeed() && loc.hasBearing() && loc.getSpeed() > 1f) {
            double dist = loc.getSpeed() * 0.5; // anticipo di mezzo secondo: meno errore in curva
            double r = Math.toRadians(brg);
            lat += Math.toDegrees(dist * Math.cos(r) / 6371000.0);
            lon += Math.toDegrees(dist * Math.sin(r) / (6371000.0 * Math.cos(Math.toRadians(lat))));
        }
        if (dispLat == null) { dispLat = lat; dispLon = lon; dispBearing = brg; }
        fromLat = dispLat; fromLon = dispLon; fromBrg = dispBearing;
        toLat = lat; toLon = lon; toBrg = brg;
        animStart = SystemClock.uptimeMillis();
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

    private void goToPlace(String key, String label) {
        String addr = prefs.getString("place_" + key, "");
        if (addr.isEmpty()) editPlace(key, label); else navigateTo(addr, false);
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
                    if (!q.isEmpty()) navigateTo(q);
                })
                .setNegativeButton("Annulla", null)
                .create();
        input.setOnEditorActionListener((v, actionId, ev) -> {
            String q = input.getText().toString().trim();
            if (!q.isEmpty()) { dialog.dismiss(); navigateTo(q); }
            return true;
        });
        dialog.show();
        input.requestFocus();
    }

    private void sendNav(String pkg, Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        if (arg != null && !arg.isEmpty()) { navigateTo(arg); return; }

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
        if (t.contains("navigatore") || t.contains("mappa")) { showPage(PAGE_MAP); setExpanded(true); return; }
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

    // ---------- Navigazione integrata (percorso OSRM, istruzioni, voce) ----------
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search?format=json&limit=5&accept-language=it&q=";
    // (indirizzo, 1 = usa le radici ISRG aggiunte a mano). Si prova in ordine fino al primo che risponde.
    private static final String[][] ROUTERS = {
            {"https://router.project-osrm.org/route/v1/driving/", "0"},
            {"http://router.project-osrm.org/route/v1/driving/", "0"},
            {"https://routing.openstreetmap.de/routed-car/route/v1/driving/", "0"},
            {"https://routing.openstreetmap.de/routed-car/route/v1/driving/", "1"}};

    private static final class Place {
        final String name;
        final double lat, lon;
        Place(String name, double lat, double lon) { this.name = name; this.lat = lat; this.lon = lon; }
    }

    private Route route;
    private GeoPoint navDest;
    private String navName = "";
    private boolean routing, expanded, voiceOn = true;
    private int routeIdx, routeStep, offCount, announceStep = -1, announceStage, trimIdx;
    private long lastRerouteAt, lastTouchAt;
    private double lastLat, lastLon;
    private boolean haveFix;
    private String routeStatus = "";
    private Uri externalUri;
    private Polyline routeLine;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsPending;
    private final AudioManager.OnAudioFocusChangeListener focusListener = c -> { };
    private final java.util.Map<String, Bitmap> maneuverCache = new java.util.HashMap<>();
    private View navBanner, navBottom, searchBar;
    private ImageView navArrow;
    private TextView navDistText, navInstrText, navSummary;

    private void setupNavUi() {
        navBanner = findViewById(R.id.nav_banner);
        navBottom = findViewById(R.id.nav_bottom);
        searchBar = findViewById(R.id.search);
        navArrow = findViewById(R.id.nav_arrow);
        navDistText = findViewById(R.id.nav_dist);
        navInstrText = findViewById(R.id.nav_instr);
        navSummary = findViewById(R.id.nav_summary);
        voiceOn = prefs.getBoolean("nav_voice", true);
        ImageButton vb = findViewById(R.id.nav_voice);
        vb.setImageResource(voiceOn ? R.drawable.ic_volume : R.drawable.ic_volume_off);
        vb.setOnClickListener(v -> {
            voiceOn = !voiceOn;
            prefs.edit().putBoolean("nav_voice", voiceOn).apply();
            vb.setImageResource(voiceOn ? R.drawable.ic_volume : R.drawable.ic_volume_off);
            if (!voiceOn && tts != null) tts.stop();
        });
        findViewById(R.id.nav_stop).setOnClickListener(v -> stopNavigation());
        findViewById(R.id.btn_expand).setOnClickListener(v -> setExpanded(!expanded));
        // un tocco sulla barra di errore apre il percorso in un'altra app di navigazione
        routeText.setOnClickListener(v -> { if (externalUri != null) sendNav(null, externalUri); });

        // riprende un percorso interrotto da poco (es. l'app è stata chiusa dal sistema)
        double dl = Double.longBitsToDouble(prefs.getLong("nav_lat", 0));
        double dn = Double.longBitsToDouble(prefs.getLong("nav_lon", 0));
        long when = prefs.getLong("nav_t", 0);
        if (when > 0 && System.currentTimeMillis() - when < 2 * 3600_000L && (dl != 0 || dn != 0)) {
            navDest = new GeoPoint(dl, dn);
            navName = prefs.getString("nav_name", "");
            externalUri = Uri.parse("geo:" + dl + "," + dn + "?q=" + dl + "," + dn);
            routeStatus = "Cerco il segnale GPS…";
            setExpanded(true);
        }
        updateNavUi();
    }

    /** Mappa a tutto schermo: nasconde schede e pannello musica. */
    private void setExpanded(boolean on) {
        expanded = on;
        findViewById(R.id.media_panel).setVisibility(on ? View.GONE : View.VISIBLE);
        findViewById(R.id.tabs).setVisibility(on ? View.GONE : View.VISIBLE);
        ((ImageButton) findViewById(R.id.btn_expand)).setImageResource(
                on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        map.postDelayed(() -> map.invalidate(), 50);
    }

    private void updateNavUi() {
        boolean pending = navDest != null && route == null;
        boolean active = navDest != null && route != null;
        searchBar.setVisibility(navDest == null ? View.VISIBLE : View.GONE);
        routeBar.setVisibility(pending ? View.VISIBLE : View.GONE);
        navBanner.setVisibility(active ? View.VISIBLE : View.GONE);
        navBottom.setVisibility(navDest != null ? View.VISIBLE : View.GONE);
        if (navDest != null) findViewById(R.id.map_street_box).setVisibility(View.GONE);
        if (pending) {
            routeText.setText(routeStatus);
            navSummary.setText(navName.isEmpty() ? "Navigazione" : navName);
        }
    }

    // ----- Ricerca destinazione -----
    private void navigateTo(String destination) { navigateTo(destination, true); }

    /** Cerca l'indirizzo; con più risultati (e choose) fa scegliere, poi avvia la navigazione. */
    private void navigateTo(String destination, boolean choose) {
        addRecent(destination);
        showPage(PAGE_MAP);
        setExpanded(true);
        GeoPoint me = haveFix ? new GeoPoint(lastLat, lastLon) : null;
        Toast.makeText(this, "Cerco “" + destination + "”…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Place> hits = new ArrayList<>();
            boolean failed = false;
            try {
                String url = NOMINATIM + Uri.encode(destination);
                if (me != null) {
                    url += String.format(Locale.US, "&countrycodes=it&bounded=0&viewbox=%f,%f,%f,%f",
                            me.getLongitude() - 0.8, me.getLatitude() + 0.6,
                            me.getLongitude() + 0.8, me.getLatitude() - 0.6);
                }
                JSONArray arr = new JSONArray(httpRequest(url, null));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject h = arr.getJSONObject(i);
                    String[] parts = h.optString("display_name", destination).split(", ");
                    StringBuilder nm = new StringBuilder();
                    for (int k = 0; k < Math.min(4, parts.length); k++) {
                        if (k > 0) nm.append(", ");
                        nm.append(parts[k]);
                    }
                    hits.add(new Place(nm.toString(), Double.parseDouble(h.getString("lat")),
                            Double.parseDouble(h.getString("lon"))));
                }
            } catch (Exception e) { failed = true; }
            final boolean fail = failed;
            main.post(() -> {
                if (hits.isEmpty()) {
                    Toast.makeText(this, fail ? "Nessuna rete: controlla il Wi-Fi o l'hotspot"
                            : "Indirizzo non trovato", Toast.LENGTH_LONG).show();
                } else if (hits.size() == 1 || !choose) {
                    startNavigation(hits.get(0));
                } else {
                    String[] names = new String[hits.size()];
                    for (int i = 0; i < names.length; i++) names[i] = hits.get(i).name;
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Quale intendi?")
                            .setItems(names, (d, which) -> startNavigation(hits.get(which)))
                            .setNegativeButton("Annulla", null)
                            .show();
                }
            });
        }, "geocode").start();
    }

    private void startNavigation(Place p) {
        stopNavigation();
        navDest = new GeoPoint(p.lat, p.lon);
        navName = p.name;
        externalUri = Uri.parse("geo:" + p.lat + "," + p.lon + "?q=" + p.lat + "," + p.lon);
        prefs.edit().putLong("nav_lat", Double.doubleToLongBits(p.lat))
                .putLong("nav_lon", Double.doubleToLongBits(p.lon))
                .putString("nav_name", p.name)
                .putLong("nav_t", System.currentTimeMillis()).apply();
        showPage(PAGE_MAP);
        setExpanded(true);
        following = true;
        routeStatus = haveFix ? "Calcolo il percorso…" : "Cerco il segnale GPS…";
        updateNavUi();
        requestRoute(false);
    }

    private void stopNavigation() {
        navDest = null;
        route = null;
        routing = false;
        announceStep = -1;
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        prefs.edit().remove("nav_lat").remove("nav_lon").remove("nav_name").remove("nav_t").apply();
        if (tts != null) tts.stop();
        if (map != null) {
            map.setMapOrientation(0f);
            map.invalidate();
        }
        if (searchBar != null) updateNavUi();
    }

    // ----- Calcolo del percorso -----
    private void requestRoute(boolean reroute) {
        if (navDest == null || routing) return;
        if (!haveFix) { routeStatus = "Cerco il segnale GPS…"; updateNavUi(); return; }
        routing = true;
        lastRerouteAt = SystemClock.elapsedRealtime();
        if (!reroute) { routeStatus = "Calcolo il percorso…"; updateNavUi(); }
        final GeoPoint dest = navDest;
        final double fl = lastLat, fo = lastLon;
        new Thread(() -> {
            Route r = null;
            String err = "";
            String q = String.format(Locale.US, "%f,%f;%f,%f", fo, fl, dest.getLongitude(), dest.getLatitude())
                    + "?overview=full&geometries=polyline6&steps=true";
            for (String[] ep : ROUTERS) {
                try {
                    r = Route.parse(httpRequest(ep[0] + q, null, "1".equals(ep[1])));
                    break;
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if ("NoRoute".equals(err)) break; // il server risponde: inutile riprovare altrove
                }
            }
            final Route fr = r;
            final String ferr = err;
            main.post(() -> {
                routing = false;
                if (navDest != dest) return; // destinazione cambiata o navigazione chiusa
                if (fr != null) applyRoute(fr, reroute);
                else routeFailed(ferr, reroute);
            });
        }, "route").start();
    }

    private void routeFailed(String why, boolean reroute) {
        if (reroute && route != null) return; // si tiene il vecchio percorso e si riprova più tardi
        routeStatus = "NoRoute".equals(why) ? "Nessun percorso trovato per questa destinazione"
                : "Percorso non riuscito (" + why + "). Controlla Wi-Fi/hotspot. Tocca qui per aprire un altro navigatore";
        updateNavUi();
    }

    private void applyRoute(Route r, boolean reroute) {
        route = r;
        routeIdx = 0;
        routeStep = 0;
        offCount = 0;
        announceStep = -1;
        announceStage = 0;
        trimIdx = 0;
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline();
        routeLine.getOutlinePaint().setColor(0xFFE0304C);
        routeLine.getOutlinePaint().setStrokeWidth(dp(8));
        routeLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        routeLine.setPoints(routePoints(0));
        map.getOverlays().add(0, routeLine);
        if (!reroute) {
            following = true;
            map.getController().setZoom(17.0);
            speak("Percorso calcolato: " + Route.distSpoken(r.total) + ", circa "
                    + Math.max(1, Math.round(r.duration / 60.0)) + " minuti.");
        }
        updateNavUi();
        if (haveFix) navUpdate(lastLat, lastLon, 0f);
        map.invalidate();
    }

    private List<GeoPoint> routePoints(int from) {
        List<GeoPoint> pts = new ArrayList<>(Math.max(0, route.lat.length - from));
        for (int i = from; i < route.lat.length; i++) pts.add(new GeoPoint(route.lat[i], route.lon[i]));
        return pts;
    }

    /** Chiamato a ogni fix GPS (thread principale). */
    private void navUpdate(double lat, double lon, float speed) {
        lastLat = lat;
        lastLon = lon;
        haveFix = true;
        if (navDest == null) return;
        if (route == null) {
            if (!routing) requestRoute(false);
            return;
        }
        Route.Match m = route.match(lat, lon, routeIdx);
        routeIdx = m.idx;
        double remaining = route.total - m.s;
        if (remaining < 30) { arrived(); return; }

        long now = SystemClock.elapsedRealtime();
        if (m.off > 45) {
            offCount++;
            if (offCount >= 3 && !routing && now - lastRerouteAt > 8000) {
                speak("Ricalcolo del percorso.");
                requestRoute(true);
            }
        } else {
            offCount = 0;
        }

        routeStep = route.stepAt(m.s, routeStep);
        Route.Step cur = route.steps.get(routeStep);
        Route.Step next = route.steps.get(Math.min(routeStep + 1, route.steps.size() - 1));
        double dNext = Math.max(0, next.startS - m.s);
        boolean off = offCount >= 3;

        navDistText.setText(off ? "Ricalcolo…" : Route.distText(dNext));
        navInstrText.setText(off ? "Fuori percorso" : Route.text(next));
        navArrow.setImageBitmap(maneuverBitmap(next));

        long secs = Math.round(route.duration * remaining / Math.max(1, route.total));
        Calendar eta = Calendar.getInstance();
        eta.add(Calendar.SECOND, (int) secs);
        navSummary.setText(String.format(Locale.ITALY, "%s · %d min · arrivo %02d:%02d",
                Route.distText(remaining), Math.max(1, Math.round(secs / 60.0)),
                eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE)));

        // annunci vocali: uno da lontano e uno vicino, più larghi se si va veloci
        if (!off) {
            if (routeStep != announceStep) { announceStep = routeStep; announceStage = 0; }
            double far = Math.max(300, speed * 22), near = Math.max(110, speed * 8);
            double stepLen = next.startS - cur.startS;
            String say = Route.text(next);
            say = Character.toLowerCase(say.charAt(0)) + say.substring(1);
            if (announceStage < 2 && dNext <= near) {
                announceStage = 2;
                speak("arrive".equals(next.type) ? Route.text(next) : "Tra " + Route.distSpoken(dNext) + ", " + say);
            } else if (announceStage < 1 && dNext <= far) {
                announceStage = 1;
                if (stepLen >= far + 150 && dNext > near + 60)
                    speak("Tra " + Route.distSpoken(dNext) + ", " + say);
            }
        }

        // accorcia la linea rossa man mano che si avanza (meno punti da disegnare)
        if (routeLine != null && routeIdx - trimIdx > 30) {
            trimIdx = routeIdx;
            routeLine.setPoints(routePoints(trimIdx));
        }
    }

    private void arrived() {
        speak("Sei arrivato a destinazione.");
        Toast.makeText(this, "Sei arrivato a destinazione", Toast.LENGTH_LONG).show();
        stopNavigation();
    }

    // ----- Voce -----
    private void speak(String text) {
        if (!voiceOn) return;
        if (tts == null) {
            ttsPending = text;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS) return;
                tts.setLanguage(Locale.ITALY);
                tts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { abandonFocus(); }
                    @Override public void onError(String id) { abandonFocus(); }
                });
                ttsReady = true;
                if (ttsPending != null) { String t = ttsPending; ttsPending = null; speak(t); }
            });
            return;
        }
        if (!ttsReady) { ttsPending = text; return; }
        AudioManager am = getSystemService(AudioManager.class);
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav");
    }

    private void abandonFocus() {
        AudioManager am = getSystemService(AudioManager.class);
        am.abandonAudioFocus(focusListener);
    }

    // ----- Icona della manovra -----
    private Bitmap maneuverBitmap(Route.Step st) {
        String key = st.type + "|" + st.modifier;
        Bitmap b = maneuverCache.get(key);
        if (b != null) return b;
        int s = dp(64);
        b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(7));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        if ("arrive".equals(st.type)) {
            fill.setColor(0xFFE0304C);
            c.drawCircle(s / 2f, s / 2f, s * 0.26f, fill);
            line.setStrokeWidth(dp(4));
            c.drawCircle(s / 2f, s / 2f, s * 0.26f, line);
        } else if ("uturn".equals(st.modifier)) {
            Path p = new Path();
            p.moveTo(s * 0.68f, s * 0.92f);
            p.lineTo(s * 0.68f, s * 0.38f);
            p.arcTo(new android.graphics.RectF(s * 0.32f, s * 0.12f, s * 0.68f, s * 0.64f), 0, -180);
            p.lineTo(s * 0.32f, s * 0.5f);
            c.drawPath(p, line);
            drawHead(c, fill, s * 0.32f, s * 0.5f, 0, 1, dp(13));
        } else {
            double a;
            switch (st.modifier) {
                case "right": a = 90; break;
                case "left": a = -90; break;
                case "slight right": a = 45; break;
                case "slight left": a = -45; break;
                case "sharp right": a = 135; break;
                case "sharp left": a = -135; break;
                default: a = 0;
            }
            float ex, ey;
            Path p = new Path();
            p.moveTo(s * 0.5f, s * 0.92f);
            if (a == 0) {
                ex = s * 0.5f;
                ey = s * 0.2f;
                p.lineTo(ex, ey);
            } else {
                p.lineTo(s * 0.5f, s * 0.52f);
                ex = (float) (s * 0.5f + Math.sin(Math.toRadians(a)) * s * 0.34f);
                ey = (float) (s * 0.52f - Math.cos(Math.toRadians(a)) * s * 0.34f);
                p.lineTo(ex, ey);
            }
            c.drawPath(p, line);
            float dx = a == 0 ? 0 : (float) Math.sin(Math.toRadians(a));
            float dy = a == 0 ? -1 : (float) -Math.cos(Math.toRadians(a));
            drawHead(c, fill, ex, ey, dx, dy, dp(13));
        }
        maneuverCache.put(key, b);
        return b;
    }

    /** Punta di freccia nel punto (x,y), diretta come (dx,dy). */
    private static void drawHead(Canvas c, Paint fill, float x, float y, float dx, float dy, float h) {
        float px = -dy, py = dx;
        Path t = new Path();
        t.moveTo(x + dx * h, y + dy * h);
        t.lineTo(x + px * h * 0.85f, y + py * h * 0.85f);
        t.lineTo(x - px * h * 0.85f, y - py * h * 0.85f);
        t.close();
        c.drawPath(t, fill);
    }

    // ----- Rete -----
    private SSLSocketFactory extraRootsFactory;

    /** Radici ISRG X2 / Root YE assenti in Android 9: usate solo per i server che ne hanno bisogno. */
    private SSLSocketFactory extraRoots() throws Exception {
        if (extraRootsFactory == null) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("isrg_root_x2", loadCert(cf, R.raw.isrg_root_x2));
            ks.setCertificateEntry("root_ye", loadCert(cf, R.raw.root_ye));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            extraRootsFactory = ctx.getSocketFactory();
        }
        return extraRootsFactory;
    }

    private java.security.cert.Certificate loadCert(CertificateFactory cf, int resId) throws Exception {
        try (InputStream in = getResources().openRawResource(resId)) {
            return cf.generateCertificate(in);
        }
    }

    private String httpRequest(String url, String body) throws Exception {
        return httpRequest(url, body, false);
    }

    /** GET se body è null, altrimenti POST con corpo JSON. */
    private String httpRequest(String url, String body, boolean extra) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (extra && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(extraRoots());
        }
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "MazdaLauncher/2.16");
        c.setRequestProperty("Accept-Language", "it");
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        // i server rispondono con errori HTTP 400 che hanno comunque un JSON utile
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
            final int idx = i;
            if (i < SLOTS.length) {
                icon.setImageResource(SLOTS[i].icon);
                item.setContentDescription(SLOTS[i].label);
                item.setOnClickListener(v -> onDockClick(idx));
                item.setOnLongClickListener(v -> { pickApp(idx); return true; });
            } else {
                icon.setImageResource(R.drawable.ic_apps);
                item.setContentDescription("Tutte le app");
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
        if (idx == SLOT_NAV) { // il navigatore è la mappa del launcher
            showPage(PAGE_MAP);
            setExpanded(true);
            return;
        }
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
        // copertina quadrata, grande quanto lo spazio libero
        View artBox = findViewById(R.id.media_art_box);
        artBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int side = Math.min(r - l, b - t) - dp(8);
            ViewGroup.LayoutParams lp = mediaArt.getLayoutParams();
            if (side > 0 && lp.width != side) {
                lp.width = side;
                lp.height = side;
                mediaArt.setLayoutParams(lp);
            }
        });
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

    // ---------- Schede del pannello sinistro ----------
    private static final String[] PAGE_TITLES = {"Destinazioni", "Parcheggio", "Auto", "Scadenze", "Mappa"};
    private static final int PAGE_PARK = 1, PAGE_MAP = 4;
    private View[] pageViews;
    private TextView[] tabViews;
    private int currentPage = 0;

    private void setupPages() {
        pageViews = new View[]{findViewById(R.id.page_places), findViewById(R.id.page_park),
                findViewById(R.id.page_obd), findViewById(R.id.page_service), findViewById(R.id.page_map)};
        LinearLayout tabs = findViewById(R.id.tabs);
        tabViews = new TextView[PAGE_TITLES.length];
        for (int i = 0; i < PAGE_TITLES.length; i++) {
            TextView t = new TextView(this);
            t.setText(PAGE_TITLES[i]);
            t.setGravity(android.view.Gravity.CENTER);
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setClickable(true);
            final int idx = i;
            t.setOnClickListener(v -> showPage(idx));
            tabs.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            tabViews[i] = t;
        }
        setupPlacesPage();
        setupParkPage();
        setupObdPage();
        setupServicePage();
        showPage(Math.max(0, Math.min(prefs.getInt("page", 0), PAGE_TITLES.length - 1)));
    }

    private void showPage(int idx) {
        currentPage = idx;
        for (int i = 0; i < pageViews.length; i++) {
            pageViews[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabViews[i].setTextColor(i == idx ? 0xFFECEFF1 : 0xFF9AA0A6);
            tabViews[i].setBackgroundResource(i == idx ? R.drawable.tab_sel : 0);
        }
        prefs.edit().putInt("page", idx).apply();
        if (idx == PAGE_PARK) updateParkUi();
    }

    private void onTick() {
        if (currentPage == PAGE_PARK) updateParkUi();
        if (currentPage == PAGE_MAP) updateStreet();
    }

    private void setCard(View card, int icon, String title, String sub) {
        ((ImageView) card.findViewById(R.id.card_icon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.card_title)).setText(title);
        ((TextView) card.findViewById(R.id.card_sub)).setText(sub);
    }

    // ----- Destinazioni -----
    private View placeHome, placeWork;
    private final View[] destCards = new View[4];
    private View destEmpty;

    private void setupPlacesPage() {
        findViewById(R.id.search2).setOnClickListener(v -> askDestination());
        findViewById(R.id.btn_voice2).setOnClickListener(v -> startVoice());
        placeHome = findViewById(R.id.place_home);
        placeWork = findViewById(R.id.place_work);
        placeHome.setOnClickListener(v -> goToPlace("home", "Casa"));
        placeWork.setOnClickListener(v -> goToPlace("work", "Lavoro"));
        placeHome.setOnLongClickListener(v -> { editPlace("home", "Casa"); return true; });
        placeWork.setOnLongClickListener(v -> { editPlace("work", "Lavoro"); return true; });
        int[] ids = {R.id.dest_0, R.id.dest_1, R.id.dest_2, R.id.dest_3};
        for (int i = 0; i < ids.length; i++) destCards[i] = findViewById(ids[i]);
        destEmpty = findViewById(R.id.dest_empty);
        bindPlaces();
    }

    private void bindPlaces() {
        String h = prefs.getString("place_home", "");
        String w = prefs.getString("place_work", "");
        setCard(placeHome, R.drawable.ic_home, "Casa", h.isEmpty() ? "Tocca per impostare" : h);
        setCard(placeWork, R.drawable.ic_work, "Lavoro", w.isEmpty() ? "Tocca per impostare" : w);
        bindDest();
    }

    private List<String> loadList(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void saveList(String key, List<String> list) {
        prefs.edit().putString(key, new JSONArray(list).toString()).apply();
    }

    private void bindDest() {
        if (destCards[0] == null) return;
        List<String> favs = loadList("favs");
        List<String> shown = new ArrayList<>(favs);
        for (String r : loadList("recents")) if (!shown.contains(r)) shown.add(r);
        for (int i = 0; i < destCards.length; i++) {
            View card = destCards[i];
            if (i >= shown.size()) { card.setVisibility(View.GONE); continue; }
            final String d = shown.get(i);
            boolean fav = favs.contains(d);
            card.setVisibility(View.VISIBLE);
            setCard(card, fav ? R.drawable.ic_star : R.drawable.ic_clock, d, fav ? "Preferito" : "Recente");
            card.setOnClickListener(v -> navigateTo(d));
            card.setOnLongClickListener(v -> { toggleFav(d); return true; });
        }
        destEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toggleFav(String d) {
        List<String> favs = loadList("favs");
        if (favs.remove(d)) {
            Toast.makeText(this, "Tolto dai preferiti", Toast.LENGTH_SHORT).show();
        } else {
            favs.add(d);
            Toast.makeText(this, "Aggiunto ai preferiti", Toast.LENGTH_SHORT).show();
        }
        saveList("favs", favs);
        bindDest();
    }

    private void addRecent(String dest) {
        String d = dest == null ? "" : dest.trim();
        if (d.isEmpty()) return;
        if (d.equalsIgnoreCase(prefs.getString("place_home", "").trim())
                || d.equalsIgnoreCase(prefs.getString("place_work", "").trim())) return;
        List<String> list = loadList("recents");
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).equalsIgnoreCase(d)) list.remove(i);
        list.add(0, d);
        while (list.size() > 8) list.remove(list.size() - 1);
        saveList("recents", list);
        bindDest();
    }

    // ----- Geocodifica inversa (via e città) -----
    private static String firstOf(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** cb(via, città): uno o entrambi null se non trovato; chiamato sul thread principale. */
    private void reverseGeocode(double lat, double lon, java.util.function.BiConsumer<String, String> cb) {
        new Thread(() -> {
            String road = null, city = null;
            try {
                JSONObject j = new JSONObject(httpRequest(String.format(Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1&lat=%f&lon=%f",
                        lat, lon), null));
                JSONObject a = j.optJSONObject("address");
                if (a != null) {
                    road = firstOf(a, "road", "pedestrian", "footway", "path", "neighbourhood", "suburb");
                    String hn = a.optString("house_number", "");
                    if (road != null && !hn.isEmpty()) road += " " + hn;
                    city = firstOf(a, "city", "town", "village", "municipality", "county");
                }
            } catch (Exception ignored) { }
            final String r = road, c = city;
            main.post(() -> cb.accept(r, c));
        }, "reverse").start();
    }

    // ----- Parcheggio -----
    private TextView parkTitle, parkAddr, parkDist, parkWalk;
    private View parkGo;

    private void setupParkPage() {
        parkTitle = findViewById(R.id.park_title);
        parkAddr = findViewById(R.id.park_addr);
        parkDist = findViewById(R.id.park_dist);
        parkWalk = findViewById(R.id.park_walk);
        parkGo = findViewById(R.id.park_go);
        View save = findViewById(R.id.park_save);
        setCard(parkGo, R.drawable.ic_nav, "Portami all'auto", "Percorso nel launcher");
        setCard(save, R.drawable.ic_pin, "Salva qui", "Posizione attuale");
        View share = findViewById(R.id.park_share);
        setCard(share, R.drawable.ic_share, "QR telefono", "Posizione auto");
        parkGo.setOnClickListener(v -> goToCar());
        save.setOnClickListener(v -> saveParking(false));
        share.setOnClickListener(v -> shareParking());
        updateParkUi();
    }

    private boolean hasPark() { return prefs.getBoolean("park_has", false); }
    private double parkLat() { return Double.longBitsToDouble(prefs.getLong("park_lat", 0)); }
    private double parkLon() { return Double.longBitsToDouble(prefs.getLong("park_lon", 0)); }

    private void saveParking(boolean auto) {
        Location fix = myLocation == null ? null : myLocation.getLastFix();
        if (fix == null || (auto && System.currentTimeMillis() - fix.getTime() > 15 * 60_000L)) {
            if (!auto) Toast.makeText(this, "Aspetto il segnale GPS…", Toast.LENGTH_SHORT).show();
            return;
        }
        final double lat = fix.getLatitude(), lon = fix.getLongitude();
        if (auto && hasPark()) { // già salvato qui: non sovrascrivere
            float[] r = new float[1];
            Location.distanceBetween(parkLat(), parkLon(), lat, lon, r);
            if (r[0] < 30) return;
        }
        prefs.edit().putBoolean("park_has", true)
                .putLong("park_lat", Double.doubleToRawLongBits(lat))
                .putLong("park_lon", Double.doubleToRawLongBits(lon))
                .putLong("park_time", System.currentTimeMillis())
                .putString("park_addr", "").apply();
        updateParkUi();
        if (!auto) Toast.makeText(this, "Posizione dell'auto salvata", Toast.LENGTH_SHORT).show();
        reverseGeocode(lat, lon, (road, city) -> {
            if (road == null && city == null) return;
            if (parkLat() != lat || parkLon() != lon) return; // nel frattempo salvata un'altra posizione
            String addr = road == null ? city : (city == null ? road : road + ", " + city);
            prefs.edit().putString("park_addr", addr).apply();
            updateParkUi();
        });
    }

    /** Mostra un QR code col link di Google Maps: lo inquadri col telefono prima di scendere. */
    private void shareParking() {
        if (!hasPark()) {
            Toast.makeText(this, "Prima salva dove hai parcheggiato", Toast.LENGTH_SHORT).show();
            return;
        }
        String link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", parkLat(), parkLon());
        Bitmap qr;
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter().encode(
                    link, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                    java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.MARGIN, 2));
            int w = m.getWidth(), h = m.getHeight(), scale = Math.max(1, 440 / w);
            int[] px = new int[w * scale * h * scale];
            for (int y = 0; y < h * scale; y++)
                for (int x = 0; x < w * scale; x++)
                    px[y * w * scale + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            qr = Bitmap.createBitmap(px, w * scale, h * scale, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile creare il QR code", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView img = new ImageView(this);
        img.setImageBitmap(qr);
        img.setPadding(24, 24, 24, 24);
        new AlertDialog.Builder(this)
                .setTitle("Inquadra col telefono")
                .setMessage("Apre la posizione dell'auto in Google Maps")
                .setView(img)
                .setPositiveButton("Chiudi", null)
                .show();
    }

    private void goToCar() {
        if (!hasPark()) {
            Toast.makeText(this, "Prima salva dove hai parcheggiato", Toast.LENGTH_SHORT).show();
            return;
        }
        startNavigation(new Place("La tua auto", parkLat(), parkLon()));
    }

    private static String whenText(long t) {
        String hm = new java.text.SimpleDateFormat("HH:mm", Locale.ITALY).format(new java.util.Date(t));
        String day;
        if (DateUtils.isToday(t)) day = "oggi";
        else if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) day = "ieri";
        else day = new java.text.SimpleDateFormat("d MMM", Locale.ITALY).format(new java.util.Date(t));
        return day + " alle " + hm;
    }

    private void updateParkUi() {
        if (parkTitle == null) return;
        if (!hasPark()) {
            parkTitle.setText("Nessun parcheggio salvato");
            parkAddr.setText("Tocca “Salva qui” quando scendi dall'auto");
            parkDist.setText("—");
            parkWalk.setText("—");
            parkGo.setAlpha(0.4f);
            return;
        }
        parkGo.setAlpha(1f);
        parkTitle.setText("Auto parcheggiata");
        String addr = prefs.getString("park_addr", "");
        String when = whenText(prefs.getLong("park_time", 0));
        parkAddr.setText(addr.isEmpty() ? when : addr + " · " + when);
        Location fix = myLocation == null ? null : myLocation.getLastFix();
        if (fix == null) {
            parkDist.setText("—");
            parkWalk.setText("—");
            return;
        }
        float[] r = new float[1];
        Location.distanceBetween(fix.getLatitude(), fix.getLongitude(), parkLat(), parkLon(), r);
        float m = r[0];
        parkDist.setText(m < 1000 ? String.format(Locale.ITALY, "%d m", Math.round(m))
                : String.format(Locale.ITALY, "%.1f km", m / 1000f));
        long min = Math.max(1, Math.round(m / 80f)); // ~4,8 km/h
        parkWalk.setText(min < 90 ? min + " min" : "—");
    }

    // ----- Via corrente sulla mappa -----
    private long revAt;
    private double revLat, revLon;
    private boolean revBusy, revHas;

    private void updateStreet() {
        if (revBusy) return;
        Location f = myLocation == null ? null : myLocation.getLastFix();
        if (f == null) return;
        long now = SystemClock.elapsedRealtime();
        if (revAt != 0 && now - revAt < 15000) return; // massimo una richiesta ogni 15 s
        if (revHas) {
            float[] r = new float[1];
            Location.distanceBetween(revLat, revLon, f.getLatitude(), f.getLongitude(), r);
            if (r[0] < 150 && now - revAt < 120000) return;
        }
        revBusy = true;
        revAt = now;
        revLat = f.getLatitude();
        revLon = f.getLongitude();
        reverseGeocode(revLat, revLon, (road, city) -> {
            revBusy = false;
            if (road == null && city == null) return;
            revHas = true;
            ((TextView) findViewById(R.id.map_street)).setText(road != null ? road : city);
            ((TextView) findViewById(R.id.map_city)).setText(road != null && city != null ? city : "");
            findViewById(R.id.map_city).setVisibility(road != null && city != null ? View.VISIBLE : View.GONE);
            findViewById(R.id.map_street_box).setVisibility(navDest == null ? View.VISIBLE : View.GONE);
        });
    }

    // ----- Dati auto (OBD2 Bluetooth) -----
    private GaugeView gTemp, gBatt, gRpm, gCons;
    private TextView obdStatus, obdTripText, obdTripAvg;
    private ObdClient obd;
    private double tripKm, tripLiters;
    private long tripStart, lastObdAt;

    private final ObdClient.Listener obdListener = new ObdClient.Listener() {
        @Override public void onStatus(String s) {
            obdStatus.setText(s);
            if (!"Collegato".equals(s)) clearGauges();
        }
        @Override public void onData(double coolant, double volt, double rpm, double speed, double maf) {
            updateObd(coolant, volt, rpm, speed, maf);
        }
    };

    private void setupObdPage() {
        gTemp = findViewById(R.id.g_temp);
        gBatt = findViewById(R.id.g_batt);
        gRpm = findViewById(R.id.g_rpm);
        gCons = findViewById(R.id.g_cons);
        obdStatus = findViewById(R.id.obd_status);
        obdTripText = findViewById(R.id.obd_trip_text);
        obdTripAvg = findViewById(R.id.obd_trip_avg);
        View trip = findViewById(R.id.obd_trip);
        trip.setOnClickListener(v -> pickObdDevice());
        trip.setOnLongClickListener(v -> {
            tripKm = 0; tripLiters = 0; tripStart = 0; lastObdAt = 0;
            obdTripText.setText("Viaggio azzerato");
            obdTripAvg.setText("");
            return true;
        });
        setupMicTest();
        clearGauges();
        obdStatus.setText(prefs.getString("obd_mac", "").isEmpty() ? "Nessun dongle" : "Non collegato");
        obdTripText.setText(prefs.getString("obd_mac", "").isEmpty()
                ? "Tocca qui per scegliere il dongle OBD2" : "Tocca per cambiare dongle · pressione lunga = azzera viaggio");
    }

    // ---------- Test microfono ----------
    private MicTest micTest;
    private TextView micText;
    private ProgressBar micLevel;
    private boolean micCommSource; // false = MIC, true = VOICE_COMMUNICATION (con eliminazione eco/rumore)

    private void setupMicTest() {
        micText = findViewById(R.id.mic_text);
        micLevel = findViewById(R.id.mic_level);
        View box = findViewById(R.id.mic_test);
        showMicIdle();
        box.setOnClickListener(v -> {
            if (micTest != null && micTest.isRunning()) { micTest.cancel(); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MICTEST);
                return;
            }
            startMicTest();
        });
        box.setOnLongClickListener(v -> {
            if (micTest != null && micTest.isRunning()) return true;
            micCommSource = !micCommSource;
            showMicIdle();
            return true;
        });
    }

    private void showMicIdle() {
        micText.setText("Test microfono: tocca, parla 5 s e riascolta · sorgente "
                + (micCommSource ? "chiamata (filtrata)" : "normale")
                + " (pressione lunga = cambia)");
        micLevel.setProgress(0);
    }

    private void startMicTest() {
        if (micTest != null && micTest.isRunning()) return;
        micTest = new MicTest(main, new MicTest.Listener() {
            @Override public void onLevel(int percent) { micLevel.setProgress(percent); }
            @Override public void onStatus(String text) { micText.setText(text); }
            @Override public void onFinished() { micLevel.setProgress(0); }
        });
        micTest.start(micCommSource
                ? android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : android.media.MediaRecorder.AudioSource.MIC);
    }

    private void clearGauges() {
        if (gTemp == null) return;
        gTemp.set("—", "°C", 0);
        gBatt.set("—", "V", 0);
        gRpm.set("—", "giri/min", 0);
        gCons.set("—", "L/100", 0);
    }

    private void ensureObd() {
        String mac = prefs.getString("obd_mac", "");
        if (mac.isEmpty() || (obd != null && obd.isRunning())) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            obdStatus.setText("Bluetooth spento");
            return;
        }
        try {
            obd = new ObdClient(ad.getRemoteDevice(mac), obdListener, main);
            obd.start();
        } catch (Exception e) {
            obdStatus.setText("Dongle non valido");
        }
    }

    private void updateObd(double coolant, double volt, double rpm, double speed, double maf) {
        long now = SystemClock.elapsedRealtime();
        if (tripStart == 0) tripStart = now;
        double dt = lastObdAt == 0 ? 0 : Math.min(5.0, (now - lastObdAt) / 1000.0);
        lastObdAt = now;
        double lph = Double.isNaN(maf) ? Double.NaN : maf * 3600.0 / (14.7 * 740.0); // benzina
        if (!Double.isNaN(speed)) tripKm += speed * dt / 3600.0;
        if (!Double.isNaN(lph)) tripLiters += lph * dt / 3600.0;

        if (Double.isNaN(coolant)) gTemp.set("—", "°C", 0);
        else gTemp.set(String.valueOf(Math.round(coolant)), "°C", (float) (coolant / 120.0));
        if (Double.isNaN(volt)) gBatt.set("—", "V", 0);
        else gBatt.set(String.format(Locale.ITALY, "%.1f", volt), "V", (float) ((volt - 11.0) / 4.0));
        if (Double.isNaN(rpm)) gRpm.set("—", "giri/min", 0);
        else gRpm.set(String.valueOf(Math.round(rpm)), "giri/min", (float) (rpm / 6500.0));
        if (Double.isNaN(lph)) gCons.set("—", "L/100", 0);
        else if (!Double.isNaN(speed) && speed >= 5) {
            double l100 = lph / speed * 100.0;
            gCons.set(String.format(Locale.ITALY, "%.1f", l100), "L/100", (float) (l100 / 15.0));
        } else {
            gCons.set(String.format(Locale.ITALY, "%.1f", lph), "L/h", (float) (lph / 15.0));
        }

        long min = (now - tripStart) / 60000;
        obdTripText.setText(String.format(Locale.ITALY, "Viaggio: %.1f km · %d min", tripKm, min));
        obdTripAvg.setText(tripKm > 0.5 && tripLiters > 0
                ? String.format(Locale.ITALY, "media %.1f L/100", tripLiters / tripKm * 100.0) : "");
    }

    private void pickObdDevice() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            Toast.makeText(this, "Bluetooth non disponibile", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ad.isEnabled()) {
            Toast.makeText(this, "Accendi il Bluetooth e riprova", Toast.LENGTH_LONG).show();
            return;
        }
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        final List<BluetoothDevice> devs = new ArrayList<>(bonded);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setNeutralButton("Abbina nuovo…", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(this, "Apri le impostazioni Bluetooth dell'autoradio", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Scollega", (d, w) -> {
                    prefs.edit().remove("obd_mac").apply();
                    if (obd != null) obd.stop();
                    obd = null;
                    obdStatus.setText("Nessun dongle");
                    clearGauges();
                });
        if (devs.isEmpty()) {
            b.setTitle("Nessun dispositivo abbinato")
                    .setMessage("Abbina prima il dongle OBD2 dalle impostazioni Bluetooth "
                            + "(di solito PIN 1234 o 0000), poi torna qui.");
        } else {
            String[] names = new String[devs.size()];
            for (int i = 0; i < names.length; i++) {
                String n = devs.get(i).getName();
                names[i] = (n != null ? n : "Sconosciuto") + "  (" + devs.get(i).getAddress() + ")";
            }
            b.setTitle("Scegli il dongle OBD2").setItems(names, (d, which) -> {
                prefs.edit().putString("obd_mac", devs.get(which).getAddress()).apply();
                if (obd != null) obd.stop();
                obd = null;
                tripKm = 0; tripLiters = 0; tripStart = 0; lastObdAt = 0;
                obdTripText.setText("Viaggio: 0,0 km · 0 min");
                obdTripAvg.setText("");
                ensureObd();
            });
        }
        b.show();
    }

    // ----- Scadenze -----
    private static final String[][] SERVICES = {
        {"service", "Tagliando"}, {"inspection", "Revisione"}, {"tax", "Bollo"}, {"insurance", "Assicurazione"}};
    private final View[] svCards = new View[4];

    private void setupServicePage() {
        int[] ids = {R.id.sv_0, R.id.sv_1, R.id.sv_2, R.id.sv_3};
        for (int i = 0; i < ids.length; i++) {
            svCards[i] = findViewById(ids[i]);
            ((TextView) svCards[i].findViewById(R.id.sv_title)).setText(SERVICES[i][1]);
            final int idx = i;
            svCards[i].setOnClickListener(v -> pickServiceDate(idx));
        }
        bindService();
    }

    private static long midnight(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void bindService() {
        long today = midnight(System.currentTimeMillis());
        for (int i = 0; i < svCards.length; i++) {
            long due = prefs.getLong("svc_" + SERVICES[i][0], 0);
            TextView t = svCards[i].findViewById(R.id.sv_due);
            ProgressBar bar = svCards[i].findViewById(R.id.sv_bar);
            if (due == 0) {
                t.setText("Tocca per impostare");
                bar.setProgress(0);
                continue;
            }
            long days = Math.round((due - today) / 86400000.0);
            String text;
            if (days < 0) text = "scaduto da " + (-days) + (days == -1 ? " giorno" : " giorni");
            else if (days == 0) text = "scade oggi";
            else if (days <= 60) text = "tra " + days + (days == 1 ? " giorno" : " giorni");
            else {
                long mesi = Math.round(days / 30.4);
                text = "tra " + mesi + " mesi";
            }
            t.setText(text);
            int color = days <= 30 ? 0xFFB3112E : (days <= 90 ? 0xFFD9A441 : 0xFF6A9955);
            bar.setProgressTintList(ColorStateList.valueOf(color));
            float used = 1f - Math.max(0, Math.min(365, days)) / 365f;
            bar.setProgress(Math.round(used * 1000));
        }
    }

    private void pickServiceDate(int idx) {
        final String key = "svc_" + SERVICES[idx][0];
        long due = prefs.getLong(key, 0);
        Calendar c = Calendar.getInstance();
        if (due > 0) c.setTimeInMillis(due);
        DatePickerDialog dlg = new DatePickerDialog(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
                (v, y, m, d) -> {
                    Calendar n = Calendar.getInstance();
                    n.clear();
                    n.set(y, m, d, 0, 0, 0);
                    prefs.edit().putLong(key, n.getTimeInMillis()).apply();
                    bindService();
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.setTitle("Scadenza " + SERVICES[idx][1].toLowerCase(Locale.ITALY));
        dlg.setButton(DialogInterface.BUTTON_NEUTRAL, "Cancella", (d, w) -> {
            prefs.edit().remove(key).apply();
            bindService();
        });
        dlg.show();
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
