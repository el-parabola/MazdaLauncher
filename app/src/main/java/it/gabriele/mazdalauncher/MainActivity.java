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

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
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
        map.onDetach();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation();
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
        if (addr.isEmpty()) editPlace(key, label); else navigateTo(addr);
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
