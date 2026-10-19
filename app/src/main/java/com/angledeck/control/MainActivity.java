package com.angledeck.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** ANGLE Deck 0.8: per-app recovery, safe quiet sessions, read-only graphics checks. */
public class MainActivity extends Activity {
    private static final int BG = 0xff0b121d, SURFACE = 0xff151f2d, SECONDARY = 0xff1b293b;
    private static final int ACTIVE = 0xff22354b, BORDER = 0xff2b3c50, INK = 0xfff1f6ff;
    private static final int MUTED = 0xff9eafc3, ACCENT = 0xffa9d7ff, GREEN = 0xff89deb4, WARNING = 0xfff5c17c;
    private static final String PKGS = "angle_gl_driver_selection_pkgs";
    private static final String VALUES = "angle_gl_driver_selection_values";
    private static final String PREFS = "angle_deck_v02";
    private static final int EXPORT_LOG_REQUEST = 8505;
    // Android 12-16 updatable driver settings. These differ from ANGLE/GLES routing.
    private static final String GPU_PROD_IN = "updatable_driver_production_opt_in_apps";
    private static final String GPU_PROD_OUT = "updatable_driver_production_opt_out_apps";
    private static final String GPU_PRE_IN = "updatable_driver_prerelease_opt_in_apps";
    private static final String GPU_ALL = "updatable_driver_all_apps";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout root, content;
    private LinearLayout homePage, driversPage, logsPage, toolsPage, rendererPage;
    private ScrollView pagesScroll;
    private final LinkedHashMap<String, TextView> navButtons = new LinkedHashMap<>();
    private String activePage = "home";
    private TextView homeMode, homeGpu, homeWarning, toolsTestLabel, crashInsight;
    private TextView hwuiStatus, hwuiWarning;
    private String hwuiSelected = "none", hwuiApplied = "";
    private boolean hwuiEdited = false;
    private final LinkedHashMap<String, LinearLayout> hwuiCards = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> hwuiMarks = new LinkedHashMap<>();
    private ScrollView liveScroll;
    private TextView status, selected, driverLabel, testLabel, output;
    private TextView apiValue, translationValue, hardwareValue, gpuSupport, routeHint, angleWarning, applyButton;
    private TextView gpuDriverLabel, gpuDriverPackages, gpuDriverWarning, gpuApplyButton;
    private final LinkedHashMap<String, LinearLayout> gpuCards = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> gpuMarks = new LinkedHashMap<>();
    private String gpuApplied = "default", gpuDraft = "default";
    private boolean gpuDirty = false, productionAvailable = false, prereleaseAvailable = false;
    private String gpuProductionPkg = "", gpuPrereleasePkg = "";
    private ImageView appIcon;
    // Live per-app Logcat is read by the Shizuku UserService as shell UID.
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder liveHistory = new StringBuilder();
    private TextView liveStatus, liveContent, liveFilterAction;
    private EditText liveSearch;
    private boolean liveCapturing = false, liveBusy = false;
    private String livePackage = "", liveFilter = "all";
    private long liveLines = 0;
    private final Runnable liveTicker = new Runnable() {
        @Override public void run() {
            if (liveCapturing && !liveBusy && remote != null) {
                liveBusy = true;
                worker.execute(() -> {
                    String fragment;
                    try { fragment = remote != null ? remote.pollLog(11000) : ""; }
                    catch (Exception e) { fragment = "[Logcat disconnected: " + e.getMessage() + "]\n"; }
                    final String chunk = fragment;
                    runOnUiThread(() -> {
                        liveBusy = false;
                        if (liveCapturing && !chunk.isEmpty()) {
                            appendLive(chunk); renderLive();
                            if (chunk.contains("[Logcat stream ended]") || chunk.contains("[Logcat error:")) {
                                liveCapturing = false;
                                liveHandler.removeCallbacks(liveTicker);
                                if (liveStatus != null) liveStatus.setText("Logcat stream ended · check messages below");
                            }
                        }
                    });
                });
            }
            if (liveCapturing) liveHandler.postDelayed(this, 1100);
        }
    };
    private final LinkedHashMap<String, LinearLayout> routeCards = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> routeMarks = new LinkedHashMap<>();
    private String appliedMode = "default", draftMode = "default";
    private boolean draftDirty = false;
    private String chosen = "", friendly = "";
    private ICommandService remote;
    private boolean didBind;

    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::connect);
    private final Shizuku.OnBinderDeadListener dead = () -> runOnUiThread(() -> {
        remote = null; didBind = false; liveCapturing = false;
        liveHandler.removeCallbacks(liveTicker);
        if (liveStatus != null) liveStatus.setText("Shizuku disconnected · capture stopped");
        refreshStatus(); showDriver("Unavailable while Shizuku is offline");
    });
    private final Shizuku.OnRequestPermissionResultListener permission = (requestCode, result) ->
            runOnUiThread(this::connect);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder binder) {
            remote = ICommandService.Stub.asInterface(binder);
            runOnUiThread(() -> { refreshStatus(); inspect(); refreshDriver(); refreshGpuDriver(); refreshHwui(); });
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            remote = null; didBind = false; liveCapturing = false;
            liveHandler.removeCallbacks(liveTicker);
            runOnUiThread(() -> {
                if (liveStatus != null) liveStatus.setText("Shizuku service disconnected · capture stopped");
                refreshStatus(); showDriver("Shizuku service disconnected");
            });
        }
    };
    private Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(new ComponentName(getPackageName(), ShellUserService.class.getName()))
                .daemon(false).processNameSuffix("angledeck").debuggable(BuildConfig.DEBUG).version(1);
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        chosen = prefs.getString("selected_package", "");
        friendly = prefs.getString("selected_name", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    @Override protected void onResume() {
        super.onResume();
        if (liveCapturing) {
            liveHandler.removeCallbacks(liveTicker);
            liveHandler.post(liveTicker);
        }
        // Binder reconnection should not require reopening the app or re-requesting permission.
        if (authorized() && remote == null && !didBind) connect();
        refreshStatus();
        if (testLabel != null) refreshTestLabel();
        if (remote != null) { refreshDriver(); refreshGpuDriver(); refreshHwui(); }
    }
    @Override public void onBackPressed() {
        if ("renderer".equals(activePage)) page("tools");
        else if (!"home".equals(activePage)) page("home");
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        liveHandler.removeCallbacks(liveTicker);
        super.onPause();
    }
    @Override public void onDestroy() {
        liveHandler.removeCallbacks(liveTicker);
        if (remote != null) try { remote.stopLog(); } catch (Exception ignored) {}
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        worker.shutdownNow();
        super.onDestroy();
    }
    private boolean alive() { try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; } }
    private boolean authorized() {
        try { return alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }
    private void connect() {
        if (!alive()) { refreshStatus(); return; }
        if (!authorized()) {
            try { Shizuku.requestPermission(421); } catch (Throwable t) { log("Shizuku permission error: " + t); }
            refreshStatus(); return;
        }
        if (remote == null && !didBind) {
            try { didBind = true; Shizuku.bindUserService(args(), connection); }
            catch (Throwable t) { didBind = false; log("Shizuku service error: " + t); }
        }
        refreshStatus();
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private GradientDrawable outlined(int color, int stroke, int radius) {
        GradientDrawable d = shape(color, radius); d.setStroke(dp(1), stroke); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(color);
        t.setTextSize(size); t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(1); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(0); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void pad(View view, int amount) { view.setPadding(dp(amount), dp(amount), dp(amount), dp(amount)); }
    private void gap(LinearLayout l, int amount) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(amount))); }
    private void line(LinearLayout l) {
        View v = new View(this); v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.topMargin = dp(13); p.bottomMargin = dp(13); l.addView(v, p);
    }
    private LinearLayout card(String title, String detail) {
        LinearLayout l = column(); l.setBackground(outlined(SURFACE, BORDER, 22)); pad(l, 18);
        l.addView(text(title, 18, INK, true));
        if (detail != null) { gap(l, 4); l.addView(text(detail, 12, MUTED, false)); }
        gap(l, 14); content.addView(l, new LinearLayout.LayoutParams(-1, -2)); gap(content, 14);
        return l;
    }
    private TextView action(LinearLayout holder, String label, boolean primary, Runnable click) {
        TextView b = text(label, 14, primary ? BG : INK, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setBackground(outlined(primary ? ACCENT : SECONDARY, primary ? ACCENT : BORDER, 14));
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8); holder.addView(b, p); b.setOnClickListener(v -> click.run());
        return b;
    }
    private void button(LinearLayout holder, String label, boolean primary, Runnable click) {
        action(holder, label, primary, click);
    }
    private void step(LinearLayout holder, String number, String title, TextView value, String explanation) {
        LinearLayout r = row();
        TextView marker = text(number, 13, ACCENT, true);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(shape(SECONDARY, 12));
        r.addView(marker, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout labels = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(12); r.addView(labels, lp);
        labels.addView(text(title, 12, MUTED, true));
        labels.addView(value);
        holder.addView(r);
        if (explanation != null) {
            gap(holder, 6); holder.addView(text(explanation, 12, MUTED, false));
        }
    }
    private void addRoute(LinearLayout holder, String mode, String name, String subtitle) {
        LinearLayout container = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(9); holder.addView(container, lp);
        container.setBackground(outlined(SECONDARY, BORDER, 15)); pad(container, 13);
        LinearLayout header = row();
        TextView indicator = text("○", 22, MUTED, false);
        header.addView(indicator, new LinearLayout.LayoutParams(dp(32), -2));
        LinearLayout labels = column(); header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView title = text(name, 15, INK, true); labels.addView(title);
        labels.addView(text(subtitle, 12, MUTED, false));
        container.addView(header);
        routeCards.put(mode, container); routeMarks.put(mode, indicator);
        container.setOnClickListener(v -> {
            draftMode = mode; draftDirty = !mode.equals(appliedMode);
            updateRouteUi();
        });
    }
    private void updateRouteUi() {
        for (String mode : routeCards.keySet()) {
            boolean active = mode.equals(draftMode);
            routeCards.get(mode).setBackground(outlined(active ? ACTIVE : SECONDARY,
                    active ? ACCENT : BORDER, 15));
            routeMarks.get(mode).setText(active ? "●" : "○");
            routeMarks.get(mode).setTextColor(active ? ACCENT : MUTED);
        }
        if (apiValue != null) apiValue.setText("Chosen by the game");
        if (translationValue != null) translationValue.setText(draftMode.equals("angle") ?
                "ANGLE selected · backend unverified" : draftMode.equals("native") ? "No ANGLE · Native OpenGL ES" :
                "Android chooses the GLES driver");
        if (hardwareValue != null) hardwareValue.setText(
                draftMode.equals("angle") ? "ANGLE often uses Vulkan · check logs" :
                "GPU system-managed");
        if (routeHint != null) routeHint.setText(chosen.isEmpty() ?
                "Choose a game to view its driver route" : remote == null ?
                "Connect Shizuku to check the applied setting" : draftDirty ?
                "PREVIEW — tap Apply to change the game" :
                "Driver selection shown · runtime not verified");
        if (applyButton != null) {
            applyButton.setText(draftDirty ? "Apply selected route" : "Reapply / confirm this route");
        }
        if (angleWarning != null) {
            boolean warning = !chosen.isEmpty() && draftMode.equals("angle") &&
                    prefs.getBoolean("incompatible_angle_" + chosen, false);
            angleWarning.setVisibility(warning ? View.VISIBLE : View.GONE);
        }
    }
    /** One activity, four stable page views. Switching tabs never destroys the live Logcat session. */
    private void page(String name) {
        activePage = name;
        if (homePage != null) homePage.setVisibility(name.equals("home") ? View.VISIBLE : View.GONE);
        if (driversPage != null) driversPage.setVisibility(name.equals("drivers") ? View.VISIBLE : View.GONE);
        if (logsPage != null) logsPage.setVisibility(name.equals("logs") ? View.VISIBLE : View.GONE);
        if (toolsPage != null) toolsPage.setVisibility(name.equals("tools") ? View.VISIBLE : View.GONE);
        if (rendererPage != null) rendererPage.setVisibility(name.equals("renderer") ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, TextView> e : navButtons.entrySet()) {
            boolean on = e.getKey().equals(name) || ("renderer".equals(name) && "tools".equals(e.getKey()));
            e.getValue().setSelected(on);
            e.getValue().setTextColor(on ? ACCENT : MUTED);
            e.getValue().setBackground(shape(on ? ACTIVE : SURFACE, 12));
            e.getValue().setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (pagesScroll != null) pagesScroll.post(() -> pagesScroll.scrollTo(0, 0));
    }
    private void addTab(LinearLayout bar, String name, String label) {
        TextView item = text(label, 13, MUTED, false);
        item.setGravity(Gravity.CENTER);
        item.setMinHeight(dp(51));
        item.setBackground(shape(SURFACE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(51), 1f);
        lp.leftMargin = dp(2); lp.rightMargin = dp(2);
        bar.addView(item, lp);
        item.setOnClickListener(v -> page(name));
        navButtons.put(name, item);
    }
    private void refreshHomeSummary() {
        if (homeMode != null) homeMode.setText(chosen.isEmpty() ? "Choose an app first" :
                remote == null ? "Connect Shizuku to verify" : labelFor(appliedMode));
        if (homeGpu != null) homeGpu.setText(chosen.isEmpty() ? "Choose an app first" :
                remote == null ? "Connect Shizuku to verify" : gpuLabel(gpuApplied));
        if (homeWarning != null) {
            boolean bad = !chosen.isEmpty() && prefs.getBoolean("incompatible_angle_" + chosen, false);
            homeWarning.setVisibility(bad ? View.VISIBLE : View.GONE);
            if (bad) homeWarning.setText("ANGLE previously crashed for this app. Try System Default or Native OpenGL ES.");
        }
    }
    private TextView miniAction(LinearLayout holder, String label, boolean primary, Runnable click) {
        TextView b = text(label, 13, primary ? BG : INK, true);
        b.setGravity(Gravity.CENTER); b.setMinHeight(dp(45));
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        b.setBackground(outlined(primary ? ACCENT : SECONDARY, primary ? ACCENT : BORDER, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.leftMargin = dp(3); lp.rightMargin = dp(3); holder.addView(b, lp);
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private void build() {
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        LinearLayout header = column(); header.setPadding(dp(18), dp(21), dp(18), dp(13));
        root.addView(header);
        LinearLayout heading = row();
        LinearLayout headingText = column();
        headingText.addView(text("ANGLE Deck", 25, INK, true));
        headingText.addView(text("GRAPHICS MANAGER  ·  0.8", 10, MUTED, true));
        heading.addView(headingText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView icon = text("◇", 25, ACCENT, true);
        icon.setGravity(Gravity.CENTER); icon.setBackground(shape(SECONDARY, 15));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        header.addView(heading); gap(header, 12);

        LinearLayout connectionCard = row();
        connectionCard.setBackground(outlined(SURFACE, BORDER, 14)); pad(connectionCard, 11);
        status = text("Checking Shizuku…", 12, GREEN, true);
        connectionCard.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView connectAction = text("CONNECT  ↗", 11, ACCENT, true);
        connectAction.setPadding(dp(6), dp(8), dp(4), dp(8));
        connectAction.setOnClickListener(v -> connect()); connectionCard.addView(connectAction);
        header.addView(connectionCard); gap(header, 9);

        LinearLayout appSelector = row();
        appSelector.setBackground(outlined(SECONDARY, BORDER, 14)); pad(appSelector, 12);
        appIcon = new ImageView(this); appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        appSelector.addView(appIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        selected = text("Select an app to manage", 13, INK, true);
        selected.setMaxLines(2);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.leftMargin = dp(12); appSelector.addView(selected, sp);
        appSelector.addView(text("CHANGE  ›", 11, ACCENT, true));
        appSelector.setOnClickListener(v -> pickApp()); header.addView(appSelector);

        pagesScroll = new ScrollView(this); pagesScroll.setFillViewport(true);
        pagesScroll.setVerticalScrollBarEnabled(false);
        root.addView(pagesScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout pages = column(); pages.setPadding(dp(18), dp(8), dp(18), dp(18));
        pagesScroll.addView(pages);
        homePage = column(); driversPage = column(); logsPage = column(); toolsPage = column(); rendererPage = column();
        pages.addView(homePage); pages.addView(driversPage);
        pages.addView(logsPage); pages.addView(toolsPage); pages.addView(rendererPage);

        content = homePage;
        LinearLayout summary = card("At a glance", "Actual GPU usage may differ from an Android setting.");
        summary.addView(text("OPENGL ES ROUTE", 11, MUTED, true));
        homeMode = text("Reading…", 15, INK, true); summary.addView(homeMode);
        gap(summary, 13);
        summary.addView(text("GPU DRIVER", 11, MUTED, true));
        homeGpu = text("Reading…", 15, INK, true); summary.addView(homeGpu);
        homeWarning = text("", 12, WARNING, true);
        homeWarning.setVisibility(View.GONE); gap(summary, 9); summary.addView(homeWarning);
        action(summary, "Adjust graphics settings  →", true, () -> page("drivers"));

        LinearLayout overview = card("Game check", "Launch your selected app and record whether it works.");
        testLabel = text("No test recorded", 13, GREEN, true); overview.addView(testLabel);
        action(overview, "Launch selected app", true, this::launchGame);
        action(overview, "Force restart selected app", false, this::confirmRestartSelected);
        action(overview, "Quiet game launch (stop app log capture)", false, this::launchQuietGame);
        action(overview, "Test results and safe rollback  →", false, () -> page("tools"));
        LinearLayout tips = card("What ANGLE Deck can do", null);
        tips.addView(text("Select the OpenGL ES driver route, choose a supported GPU driver, and capture per-app logs. " +
                "A game must enable native Vulkan itself; this app cannot force it.", 13, MUTED, false));

        content = driversPage;
        LinearLayout choices = card("OpenGL ES route", "Changes apply only to the selected app. ANGLE usually translates GLES to Vulkan.");
        driverLabel = text("Choose an app to inspect its driver", 12, ACCENT, true);
        choices.addView(driverLabel); gap(choices, 11);
        addRoute(choices, "default", "System default", "Android decides · safest baseline");
        addRoute(choices, "native", "Native OpenGL ES", "Use the device's GLES driver directly");
        addRoute(choices, "angle", "ANGLE → Vulkan", "Experimental for each game · may crash");
        angleWarning = text("⚠  ANGLE previously failed for this app. Restore default if it crashes.",
                12, WARNING, true); choices.addView(angleWarning); gap(choices, 5);
        applyButton = action(choices, "Apply OpenGL ES route", true, () -> setDriver(draftMode));
        action(choices, "Apply route & restart selected app", false, () -> setDriver(draftMode, true));
        action(choices, "Check captured ANGLE / Vulkan evidence", false, this::checkAngleEvidence);
        LinearLayout restoreRow = row(); choices.addView(restoreRow);
        miniAction(restoreRow, "Restore default", false, () -> setDriver("default"));
        miniAction(restoreRow, "Refresh", false, this::refreshDriver);

        LinearLayout gpu = card("GPU driver", "A separate Android setting. Uninstalled or unsupported drivers stay unavailable.");
        gpuDriverLabel = text("Driver status not checked", 12, ACCENT, true); gpu.addView(gpuDriverLabel);
        gap(gpu, 8); gpuDriverPackages = text("Detecting updatable driver packages…", 12, MUTED, false);
        gpu.addView(gpuDriverPackages); gap(gpu, 13);
        addGpuRoute(gpu, "default", "Device default", "Android selects the configured driver");
        addGpuRoute(gpu, "system", "System graphics driver", "Use the GPU driver shipped with the device");
        addGpuRoute(gpu, "production", "Game / production driver", "Updated driver only when supported by this device");
        addGpuRoute(gpu, "prerelease", "Developer driver", "Prerelease packages may be restricted to test apps");
        gpuDriverWarning = text("", 12, WARNING, true); gpu.addView(gpuDriverWarning);
        gpuApplyButton = action(gpu, "Apply GPU driver", true, this::setGpuDriver);
        action(gpu, "Refresh available drivers", false, this::refreshGpuDriver);

        LinearLayout pipeline = card("How the pipeline works", "These stages are not three independent switches.");
        routeHint = text("Configuration shown; runtime not yet verified", 11, ACCENT, true);
        pipeline.addView(routeHint); gap(pipeline, 12);
        apiValue = text("Chosen by the game", 14, INK, true);
        translationValue = text("Android chooses GLES driver", 14, INK, true);
        hardwareValue = text("GPU system-managed", 14, INK, true);
        step(pipeline, "1", "GAME GRAPHICS API", apiValue,
                "Only the game can switch its own native API."); line(pipeline);
        step(pipeline, "2", "GLES / ANGLE TRANSLATION", translationValue, null); line(pipeline);
        step(pipeline, "3", "GPU HARDWARE", hardwareValue, null);
        gap(pipeline, 12);
        boolean vk = getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
        gpuSupport = text(vk ? "● Vulkan hardware feature detected" :
                "● Vulkan hardware feature not reported", 12, vk ? GREEN : MUTED, true);
        pipeline.addView(gpuSupport);
        action(pipeline, "Why ANGLE + GLES is unavailable", false, this::explainAngleGles);
        action(pipeline, "Learn about Turnip, Zink and SwiftShader", false, this::explainOtherDrivers);

        content = logsPage;
        buildLiveLogCard();

        content = toolsPage;
        LinearLayout tester = card("Game compatibility", "Record real launch results. No automated test can guarantee stability.");
        toolsTestLabel = text("No result yet", 13, GREEN, true); tester.addView(toolsTestLabel);
        action(tester, "Launch selected app", true, this::launchGame);
        LinearLayout verdict = row(); tester.addView(verdict);
        miniAction(verdict, "✓ Works", false, this::markWorked);
        miniAction(verdict, "! Problem", false, this::reportProblem);
        action(tester, "Undo last graphics-route change", false, this::undoChange);
        action(tester, "Reset selected game's GLES + GPU choices", false, this::resetSelectedGame);

        LinearLayout diagnostics = card("System diagnostics", "Raw outputs can include other apps. Do not assume they all belong to your selected game.");
        action(diagnostics, "Check device and settings", true, this::inspect);
        action(diagnostics, "Discover available GPU drivers", false, this::discoverDrivers);
        action(diagnostics, "Read recent system graphics/crash logs", false, this::inspectLogs);
        action(diagnostics, "ANGLE readiness + SurfaceFlinger (read-only)", false, this::inspectGraphicsStack);
        action(diagnostics, "System UI renderer (Skia)  →", false, () -> { page("renderer"); refreshHwui(); });
        LinearLayout results = card("Diagnostic output", "System command results; local to this screen.");
        output = text("No diagnostics yet.", 12, MUTED, false);
        output.setTextIsSelectable(true); results.addView(output);
        LinearLayout note = card("Safety notes", null);
        note.addView(text("No root, GPU overclocking, global force-stop, or driver injection. The quiet launch stops only ANGLE Deck's " +
                "Logcat reader; Android's system logs and crash reporting stay enabled. SurfaceFlinger diagnostics are read-only. " +
                "ANGLE selection does not prove Vulkan was used.", 12, MUTED, false));

        // Separate advanced page keeps the four-tab navigation compact.
        content = rendererPage;
        LinearLayout intro = card("System UI renderer", "Global Android HWUI setting, not a game-native graphics API selector.");
        intro.addView(text("Affects Android View / Skia rendering in newly started UI processes. " +
                "Does not force Unity, Unreal, or other game engines to use Vulkan.", 13, MUTED, false));
        hwuiStatus = text("Checking current system property…", 13, ACCENT, true);
        gap(intro, 12); intro.addView(hwuiStatus);
        action(intro, "Read current renderer", false, this::refreshHwui);

        LinearLayout rendererOptions = card("Choose UI renderer", "The property is global; only the chosen app will be restarted if requested.");
        addHwuiRoute(rendererOptions, "none", "System default", "Clear the property value (not literal 'none')");
        addHwuiRoute(rendererOptions, "skiagl", "Skia OpenGL ES", "Uses the HWUI OpenGL backend for Android Views");
        addHwuiRoute(rendererOptions, "skiavk", "Skia Vulkan", "Uses the HWUI Vulkan backend when supported");
        hwuiWarning = text("", 12, WARNING, true); rendererOptions.addView(hwuiWarning);
        action(rendererOptions, "Apply UI renderer", true, () -> setHwui(false));
        action(rendererOptions, "Apply UI renderer & restart app", false, () -> setHwui(true));
        action(rendererOptions, "Restore previous UI renderer", false, this::undoHwui);

        LinearLayout restartCard = card("Selected app restart", "Force-stop only the selected app, then launch its main activity.");
        action(restartCard, "Restart selected app now", true, this::confirmRestartSelected);
        restartCard.addView(text("For ANGLE → Vulkan, choose ANGLE on the Drivers page first. " +
                "Restarting reloads the chosen driver settings; it cannot turn a native GLES game into native Vulkan. " +
                "Do not restart a game during an active match.", 12, MUTED, false));
        action(restartCard, "← Back to tools", false, () -> page("tools"));

        LinearLayout navBar = row();
        navBar.setPadding(dp(11), dp(8), dp(11), dp(12));
        navBar.setBackgroundColor(SURFACE); root.addView(navBar);
        addTab(navBar, "home", "Home");
        addTab(navBar, "drivers", "Drivers");
        addTab(navBar, "logs", "Live logs");
        addTab(navBar, "tools", "Tools");

        if (!chosen.isEmpty()) updateSelectedApp();
        refreshTestLabel(); refreshStatus(); updateRouteUi(); updateGpuRouteUi();
        refreshHomeSummary(); updateHwuiUi(); page(activePage);
    }
    private void updateSelectedApp() {
        if (selected == null) return;
        selected.setText(friendly.isEmpty() ? chosen : friendly + "\n" + chosen);
        try { appIcon.setImageDrawable(getPackageManager().getApplicationIcon(chosen)); }
        catch (PackageManager.NameNotFoundException ignored) { appIcon.setImageResource(android.R.drawable.sym_def_app_icon); }
        updateRouteUi();
        refreshHomeSummary();
    }
    private void refreshStatus() {
        if (status == null) return;
        String message = !alive() ? "● Shizuku offline" : !authorized() ? "● Permission required" :
                remote == null ? "● Connecting to Shizuku" : "● Shizuku connected";
        status.setText(message); status.setTextColor(remote == null ? WARNING : GREEN);
        refreshHomeSummary();
    }
    private void log(String message) { runOnUiThread(() -> {
        if (output != null) output.setText(message);
        if (!"tools".equals(activePage)) {
            String brief = message.replace('\n', ' ').trim();
            Toast.makeText(this, brief.length() > 110 ? brief.substring(0, 107) + "…" : brief,
                    Toast.LENGTH_LONG).show();
        }
    }); }
    private void showDriver(String value) { runOnUiThread(() -> { if (driverLabel != null) driverLabel.setText(value); }); }
    private void execute(String cmd, boolean show) {
        if (remote == null) { log("Connect Shizuku before using diagnostics."); connect(); return; }
        worker.execute(() -> {
            try { String result = command(cmd); if (show) log(result.isEmpty() ? "Command succeeded (no output)." : result); }
            catch (Exception e) { log("Diagnostic failed: " + e.getMessage()); }
        });
    }
    private String command(String cmd) throws Exception {
        ICommandService service = remote;
        if (service == null) throw new Exception("Shizuku not connected");
        String result = service.run(cmd);
        if (result == null) throw new Exception("Empty service response");
        if (result.startsWith("ERROR:")) throw new Exception(result);
        return result.trim();
    }
    /** Read-only capability audit: no SurfaceFlinger debug flags are modified. */
    private void inspectGraphicsStack() {
        if (remote == null) { log("Connect Shizuku for graphics diagnostics."); connect(); return; }
        execute("echo '=== ANGLE ==='; getprop ro.angle.supported; " +
                "echo 'Built-in ANGLE app (may not be a separate APK):'; pm path com.android.angle 2>/dev/null || true; " +
                "echo '=== Vulkan ==='; pm list features | grep -i vulkan || true; " +
                "echo 'Hardware Vulkan implementation:'; getprop ro.hardware.vulkan; " +
                "echo '=== Skia HWUI ==='; getprop debug.hwui.renderer; " +
                "echo '=== SurfaceFlinger composition ==='; dumpsys SurfaceFlinger --list 2>&1 | head -35; " +
                "echo '=== Thermal status ==='; dumpsys thermalservice 2>&1 | grep -i 'Thermal Status' | head -3 || true", true);
    }

    /** Keeps device Logcat/crash reporting intact; stops only our own optional reader. */
    private void launchQuietGame() {
        final String pkg = chosen;
        if (pkg.isEmpty()) { log("Choose an app first."); return; }
        if (launcherComponent(pkg) == null) { log("Selected app is not a safe, launchable game."); return; }
        new AlertDialog.Builder(this).setTitle("Launch a quiet gaming session?")
                .setMessage("Stop ANGLE Deck's live Logcat collection (if active), then open " + pkg +
                        ". Existing captured lines remain in memory.\n\n" +
                        "This does NOT disable Android system logging, crash reports, or thermal safety.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch", (d, w) -> {
                    liveCapturing = false;
                    liveHandler.removeCallbacks(liveTicker);
                    if (liveStatus != null) liveStatus.setText("Capture paused for quiet game session");
                    worker.execute(() -> {
                        try {
                            ICommandService service = remote;
                            if (service != null) service.stopLog();
                        } catch (Exception e) { log("Log capture stop warning: " + e.getMessage()); }
                        runOnUiThread(() -> {
                            if (pkg.equals(chosen)) launchGame();
                        });
                    });
                }).show();
    }

    /** Local UID-capture evidence is advisory, not proof of the final GPU backend. */
    private void checkAngleEvidence() {
        if (chosen.isEmpty()) { log("Choose an app first."); return; }
        if (!chosen.equals(livePackage) || liveHistory.length() == 0) {
            log("No captured Logcat for this app. Open Live logs, capture a fresh launch, then check again.");
            return;
        }
        String[] lines = liveHistory.toString().split("\n");
        int mentions = 0;
        StringBuilder excerpt = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("angle") && (lower.contains("vulkan") ||
                    lower.contains("graphicsenvironment") || lower.contains("renderer"))) {
                mentions++;
                if (excerpt.length() < 2200) excerpt.append(line).append('\n');
            }
        }
        log("ANGLE runtime clues for " + chosen + ": " + mentions + " matching captured lines.\n" +
                (mentions == 0 ? "No ANGLE/Vulkan clues in the saved UID capture; this is NOT proof ANGLE failed. " +
                        "Some loader messages may be logged under another UID. " :
                        "Matching strings are clues, not definitive proof of which renderer the game used.\n" + excerpt) +
                "\nDriver choice is a request. Verify actual driver use with complete launch logs when available.");
    }

    /** Intentionally resets *only* the chosen app. Two independent Android settings need separate verification. */
    private void resetSelectedGame() {
        final String pkg = chosen;
        if (pkg.isEmpty() || launcherComponent(pkg) == null) {
            log("Choose a launchable non-protected app first."); return;
        }
        if (remote == null) { log("Connect Shizuku first."); return; }
        new AlertDialog.Builder(this).setTitle("Reset graphics choices for this app?")
                .setMessage("Return " + pkg + " to default GLES routing and default updatable GPU driver choice. " +
                        "Other apps and global HWUI settings remain unchanged.\n\n" +
                        "The game will be stopped, so save your progress. These are two separate Android updates; " +
                        "check both driver labels afterward in case either update fails.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset both", (d,w) -> worker.execute(() -> {
                    apply("default", pkg, false);
                    applyGpuDriver(pkg, "default");
                    refreshDriver();
                    refreshGpuDriver();
                })).show();
    }

    private void inspect() {
        execute("echo '--- Android version ---'; getprop ro.build.version.release; " +
                "echo '--- Vulkan features ---'; pm list features | grep -i vulkan || true; " +
                "echo '--- HWUI renderer ---'; getprop debug.hwui.renderer; " +
                "echo '--- System-wide ANGLE override ---'; settings get global angle_gl_driver_all_angle; " +
                "echo '--- Per-app packages ---'; settings get global angle_gl_driver_selection_pkgs; " +
                "echo '--- Per-app drivers (matching order) ---'; " +
                "settings get global angle_gl_driver_selection_values; " +
                "echo '--- Updatable driver global policy ---'; settings get global " + GPU_ALL + "; " +
                "echo '--- GPU driver packages ---'; getprop ro.gfx.driver.0; getprop ro.gfx.driver.1; " +
                "echo '--- Per-app GPU driver lists ---'; settings get global " + GPU_PROD_IN + "; " +
                "settings get global " + GPU_PROD_OUT + "; settings get global " + GPU_PRE_IN, true);
    }
    private void inspectLogs() {
        log("Reading recent system graphics logs. A log line may belong to another app...");
        execute("logcat -d -t 1400 -v brief 2>&1 | " +
                "grep -iE 'ANGLE|Vulkan|GraphicsEnvironment|AndroidRuntime|FATAL EXCEPTION|am_crash' | tail -65", true);
        if (!chosen.isEmpty()) { refreshDriver(); refreshGpuDriver(); }
    }

    /** Live stream: read a single app UID, not all other apps' private logs. */
    private void buildLiveLogCard() {
        LinearLayout panel = card("Live app Logcat", "Start capture, switch to the game, then return to review. Collection stays active via Shizuku.");
        liveStatus = text("Stopped · choose an app and press Start", 12, ACCENT, true);
        panel.addView(liveStatus);
        gap(panel, 8);
        panel.addView(text("UID filtering follows app process restarts. System crash reporters may use a different UID.",
                12, MUTED, false));
        LinearLayout liveControls = row(); panel.addView(liveControls);
        miniAction(liveControls, "▶ Start", true, this::startLiveLog);
        miniAction(liveControls, "■ Stop", false, this::stopLiveLog);
        action(panel, "Launch selected app", false, this::launchGame);
        liveFilterAction = action(panel, "Filter: All logs  ·  tap to change", false, () -> {
            liveFilter = liveFilter.equals("all") ? "graphics" : liveFilter.equals("graphics") ? "errors" : "all";
            liveFilterAction.setText("Filter: " + (liveFilter.equals("graphics") ? "Graphics" :
                    liveFilter.equals("errors") ? "Errors / crashes" : "All logs") + "  ·  tap to change");
            renderLive();
        });
        liveSearch = new EditText(this);
        liveSearch.setSingleLine(true);
        liveSearch.setTextColor(INK);
        liveSearch.setHintTextColor(MUTED);
        liveSearch.setHint("Search log messages...");
        liveSearch.setTextSize(13);
        liveSearch.setBackground(outlined(SECONDARY, BORDER, 12));
        liveSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(-1, dp(48));
        searchP.topMargin = dp(12);
        panel.addView(liveSearch, searchP);
        liveSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderLive(); }
            public void afterTextChanged(Editable e) {}
        });
        liveContent = text("No captured logs yet. Start capture, then launch the selected game.", 11, INK, false);
        liveContent.setTypeface(Typeface.MONOSPACE);
        liveContent.setTextIsSelectable(true);
        liveContent.setPadding(dp(12), dp(12), dp(12), dp(12));
        liveScroll = new ScrollView(this);
        liveScroll.setFillViewport(true);
        liveScroll.setBackground(outlined(0xff0d1723, BORDER, 14));
        liveScroll.addView(liveContent);
        LinearLayout.LayoutParams logP = new LinearLayout.LayoutParams(-1, dp(330));
        logP.topMargin = dp(11);
        panel.addView(liveScroll, logP);
        crashInsight = text("No crash analysis yet · capture a session first.", 12, MUTED, false);
        panel.addView(crashInsight);
        action(panel, "Analyze captured crash", false, this::analyzeLiveCrash);
        LinearLayout secondaryActions = row(); panel.addView(secondaryActions);
        miniAction(secondaryActions, "Clear viewer", false, () -> {
            liveHistory.setLength(0); liveLines = 0;
            if (crashInsight != null) crashInsight.setText("No crash analysis yet · capture a session first.");
            renderLive();
        });
        miniAction(secondaryActions, "Export TXT", false, this::saveLiveLog);
        action(panel, "Open system crash tools  →", false, () -> page("tools"));
        panel.addView(text("Private: logs stay in memory until you export them. Exported logs may contain sensitive data. " +
                "No system log buffers are cleared.", 11, MUTED, false));
    }

    private void startLiveLog() {
        final String pkg = chosen;
        if (pkg.isEmpty()) { log("Choose an app first."); return; }
        if (remote == null) { log("Connect Shizuku before starting Logcat."); connect(); return; }
        if (liveCapturing) stopLiveLog();
        final int uid;
        try { uid = getPackageManager().getApplicationInfo(pkg, 0).uid; }
        catch (Exception e) { log("Could not look up app UID: " + e.getMessage()); return; }
        liveHistory.setLength(0);
        liveLines = 0;
        livePackage = pkg;
        liveCapturing = true;
        liveStatus.setText("Starting capture for " + pkg + "...");
        worker.execute(() -> {
            String result;
            try { result = remote.startLog(uid); }
            catch (Exception e) { result = "ERROR: " + e.getMessage(); }
            final String message = result;
            runOnUiThread(() -> {
                if (!liveCapturing || !pkg.equals(livePackage)) return;
                if (message.startsWith("ERROR:")) {
                    liveCapturing = false;
                    liveHandler.removeCallbacks(liveTicker);
                    liveStatus.setText("Unable to start: " + message);
                    return;
                }
                liveStatus.setText("● LIVE · " + pkg + " · UID " + uid);
                appendLive("[Capture started: " + message + "]\n");
                renderLive();
                liveHandler.removeCallbacks(liveTicker);
                liveHandler.post(liveTicker);
            });
        });
    }

    private void stopLiveLog() {
        liveCapturing = false;
        liveHandler.removeCallbacks(liveTicker);
        if (liveStatus != null) liveStatus.setText("Stopped · " + livePackage + " · " + liveLines + " lines captured");
        worker.execute(() -> {
            try {
                ICommandService service = remote;
                if (service != null) {
                    String rest = service.pollLog(12000);
                    runOnUiThread(() -> { if (!rest.isEmpty()) { appendLive(rest); renderLive(); } });
                    service.stopLog();
                }
            } catch (Exception ignored) {}
        });
    }

    private void appendLive(String chunk) {
        liveHistory.append(chunk);
        for (int i = 0; i < chunk.length(); i++) if (chunk.charAt(i) == '\n') liveLines++;
        // Keep at most ~90 KB on the Activity side; Shizuku's buffer is also bounded.
        if (liveHistory.length() > 90000) {
            int cut = liveHistory.length() - 90000;
            int nl = liveHistory.indexOf("\n", cut);
            liveHistory.delete(0, nl < 0 ? cut : nl + 1);
        }
    }

    private void renderLive() {
        if (liveContent == null) return;
        String needle = liveSearch == null ? "" : liveSearch.getText().toString().toLowerCase(Locale.ROOT);
        String all = liveHistory.toString();
        String[] lines = all.split("\n");
        StringBuilder matched = new StringBuilder();
        int shown = 0;
        for (int i = lines.length - 1; i >= 0 && shown < 100; i--) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !lower.contains(needle)) continue;
            if (liveFilter.equals("graphics") &&
                    !(lower.contains("angle") || lower.contains("vulkan") || lower.contains("egl") ||
                      lower.contains("gles") || lower.contains("opengl") || lower.contains("gpu") ||
                      lower.contains("render") || lower.contains("gralloc"))) continue;
            if (liveFilter.equals("errors") &&
                    !(line.matches(".*\\s[EF]\\s.*") || lower.contains("fatal") ||
                      lower.contains("crash") || lower.contains("sigsegv") || lower.contains("sigabrt") ||
                      lower.contains("vk_error"))) continue;
            matched.insert(0, line + "\n");
            shown++;
        }
        liveContent.setText(shown == 0 ? "No matching logs yet. The app may not have written any logs." : matched.toString());
        if (liveScroll != null && needle.isEmpty()) liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
        if (liveCapturing && liveStatus != null) liveStatus.setText(
                "● LIVE · " + livePackage + " · " + liveLines + " lines captured");
    }

    /** Conservative, local rule-based crash summary; never asserts a root cause. */
    private void analyzeLiveCrash() {
        if (crashInsight == null) return;
        String capture = liveHistory.toString().toLowerCase(Locale.ROOT);
        if (capture.trim().isEmpty()) {
            crashInsight.setText("No captured logs. Start Logcat and reproduce the problem first.");
            return;
        }
        boolean crash = capture.contains("sigsegv") || capture.contains("sigabrt") ||
                capture.contains("fatal signal") || capture.contains("fatal exception");
        boolean angle = capture.contains("libglesv2_angle.so");
        boolean vk = capture.contains("libvulkan.so");
        boolean egl = capture.contains("egl_initialize") || capture.contains("egl::display::initialize");
        if (crash && angle && vk && egl) {
            crashInsight.setText("⚠ Native crash during graphics initialization. Backtrace includes ANGLE and Vulkan. " +
                    "This indicates a failed ANGLE → Vulkan startup, but does not identify which driver has the defect. " +
                    "Restore System Default and compare. See relevant SIGSEGV / backtrace lines below.");
            crashInsight.setTextColor(WARNING);
        } else if (crash && angle && vk) {
            crashInsight.setText("⚠ Native crash stack mentions ANGLE and Vulkan. Look at surrounding frames " +
                    "to identify the failing operation; the exact cause is unconfirmed.");
            crashInsight.setTextColor(WARNING);
        } else if (crash) {
            crashInsight.setText("A possible app crash appears in the captured logs. No complete ANGLE/Vulkan " +
                    "initialization stack was found. Inspect the exported trace before attributing it to a driver.");
            crashInsight.setTextColor(WARNING);
        } else {
            crashInsight.setText("No fatal crash signature found in the current UID-filtered capture. " +
                    "System crash reporter processes may be missing because they use a different UID.");
            crashInsight.setTextColor(MUTED);
        }
    }

    private void saveLiveLog() {
        if (liveHistory.length() == 0) { log("No logs to save yet."); return; }
        new AlertDialog.Builder(this).setTitle("Export app logs?")
                .setMessage("Logs may contain account identifiers, file paths or other private details. " +
                        "Review the exported file before sharing it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose destination", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "angledeck-log-" +
                            (livePackage.isEmpty() ? "app" : livePackage) + ".txt");
                    startActivityForResult(intent, EXPORT_LOG_REQUEST);
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_LOG_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("File output unavailable");
            String header = "ANGLE Deck per-app UID capture: " + livePackage + "\n" +
                    "UID filtering does not include system crash reporter processes.\n\n";
            out.write((header + liveHistory).getBytes(StandardCharsets.UTF_8));
            liveStatus.setText("Log exported · " + livePackage);
        } catch (Exception e) { log("Could not export logs: " + e.getMessage()); }
    }

    private void pickApp() {
        final ArrayList<String[]> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (ApplicationInfo a : pm.getInstalledApplications(0)) {
            if (!a.enabled || a.packageName.equals(getPackageName())) continue;
            apps.add(new String[]{String.valueOf(pm.getApplicationLabel(a)), a.packageName});
        }
        apps.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        final EditText search = new EditText(this); search.setSingleLine(true); search.setHint("Search app or package");
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        ListView list = new ListView(this); LinearLayout box = column();
        box.setPadding(dp(16), 0, dp(16), 0); box.addView(search);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(370)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Choose application")
                .setView(box).setNegativeButton("Cancel", null).create();
        ArrayList<String[]> filtered = new ArrayList<>();
        Runnable update = () -> {
            filtered.clear(); adapter.clear(); String query = search.getText().toString().toLowerCase(Locale.ROOT);
            for (String[] a : apps) if (a[0].toLowerCase(Locale.ROOT).contains(query) || a[1].contains(query)) {
                filtered.add(a); adapter.add(a[0] + "\n" + a[1]);
            }
            adapter.notifyDataSetChanged();
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(Editable s) {}
        });
        update.run();
        list.setOnItemClickListener((parent, v, position, id) -> {
            String[] item = filtered.get(position);
            if (liveCapturing && !item[1].equals(livePackage)) stopLiveLog();
            chosen = item[1]; friendly = item[0];
            appliedMode = "default"; draftMode = "default"; draftDirty = false;
            gpuApplied = "default"; gpuDraft = "default"; gpuDirty = false;
            updateSelectedApp();
            prefs.edit().putString("selected_package", chosen).putString("selected_name", friendly).apply();
            dialog.dismiss(); refreshDriver(); refreshGpuDriver(); refreshTestLabel();
        });
        dialog.show();
    }
    private String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private void requireValidPackage(String pkg) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
            throw new Exception("Invalid package name");
    }
    private LinkedHashMap<String, String> readOverrides() throws Exception {
        String p = command("settings get global " + PKGS);
        String v = command("settings get global " + VALUES);
        if ("null".equals(p)) p = "";
        if ("null".equals(v)) v = "";
        String[] ps = p.isEmpty() ? new String[0] : p.split(",", -1);
        String[] vs = v.isEmpty() ? new String[0] : v.split(",", -1);
        if (ps.length != vs.length) throw new Exception("Stored package and driver lists have different lengths. No settings changed.");
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < ps.length; i++) {
            requireValidPackage(ps[i]);
            if (map.containsKey(ps[i])) throw new Exception("Duplicate package in stored settings. No settings changed.");
            map.put(ps[i], vs[i]);
        }
        return map;
    }
    private String labelFor(String mode) {
        switch (mode) {
            case "angle": return "ANGLE selected (runtime unverified)";
            case "native": return "Native OpenGL ES selected";
            case "default": return "System default (no per-app override)";
            default: return "Unknown system override: " + mode;
        }
    }
    private void refreshDriver() {
        String pkg = chosen;
        if (pkg.isEmpty()) { showDriver("Choose an app first"); return; }
        if (remote == null) { showDriver("Connect Shizuku to read the current setting"); return; }
        showDriver("Reading driver setting...");
        worker.execute(() -> {
            try {
                String mode = readOverrides().getOrDefault(pkg, "default");
                boolean globalAngle = "1".equals(command("settings get global angle_gl_driver_all_angle"));
                String statusText = labelFor(mode) +
                        (globalAngle ? "\nWARNING: System-wide ANGLE override is enabled. Per-app status may not reflect the effective driver." : "");
                runOnUiThread(() -> {
                    if (!pkg.equals(chosen)) return;
                    appliedMode = mode;
                    if (!draftDirty) draftMode = mode;
                    else draftDirty = !draftMode.equals(mode);
                    SharedPreferences.Editor edit = prefs.edit().putString("last_mode_" + pkg, mode);
                    // Recover crash reporting from v0.2.1 when ANGLE is still selected.
                    if ("angle".equals(mode) && prefs.getBoolean("failed_" + pkg, false)) {
                        edit.putBoolean("incompatible_angle_" + pkg, true);
                    }
                    edit.apply();
                    showDriver(statusText);
                    updateRouteUi();
                    refreshHomeSummary();
                });
            } catch (Exception e) { showDriver("Unable to read setting: " + e.getMessage()); }
        });
    }
    private void setDriver(String mode) { setDriver(mode, false); }
    private void setDriver(String mode, boolean relaunch) {
        if (chosen.isEmpty()) { log("Select an application first."); return; }
        if (remote == null) { log("Connect Shizuku first."); return; }
        if (launcherComponent(chosen) == null) { log("Select a launchable non-protected app to change its driver safely."); return; }
        final String pkg = chosen, title = friendly;
        final String component = relaunch ? launcherComponent(pkg) : null;
        if (relaunch && component == null) { log("Selected app has no accessible launcher activity."); return; }
        boolean previouslyFailed = "angle".equals(mode) &&
                prefs.getBoolean("incompatible_angle_" + pkg, false);
        String friendlyMode = mode.equals("angle") ? "ANGLE → Vulkan" :
                mode.equals("native") ? "Native OpenGL ES" : "System default";
        new AlertDialog.Builder(this).setTitle("Apply " + friendlyMode + "?")
                .setMessage("App: " + title + "\n" + pkg +
                        "\n\nThis changes the GLES driver selection and stops the game" +
                        (relaunch ? ", then attempts to relaunch it." : ".") +
                        " It does not change the game's native graphics API or hardware GPU." +
                        (previouslyFailed ? "\n\nWARNING: You previously reported a crash with ANGLE for this game." : "") +
                        "\n\nOther apps' driver settings will be preserved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(relaunch ? "Apply & restart" : "Apply", (dialog, which) ->
                        worker.execute(() -> apply(mode, pkg, false, relaunch, component)))
                .show();
    }
    private void writeSetting(String key, String value) throws Exception {
        command(value.isEmpty() ? "settings delete global " + key :
                "settings put global " + key + " " + quote(value));
    }
    private String[] serialize(LinkedHashMap<String, String> map) {
        StringJoiner p = new StringJoiner(","), v = new StringJoiner(",");
        for (Map.Entry<String, String> entry : map.entrySet()) { p.add(entry.getKey()); v.add(entry.getValue()); }
        return new String[]{p.toString(), v.toString()};
    }
    private void apply(String mode, String pkg, boolean undo) { apply(mode, pkg, undo, false, null); }
    private void apply(String mode, String pkg, boolean undo, boolean relaunch, String component) {
        try {
            requireValidPackage(pkg);
            if (launcherComponent(pkg) == null) throw new Exception("Protected or non-launchable package: refusing force-stop");
            if (!mode.equals("angle") && !mode.equals("native") && !mode.equals("default"))
                throw new Exception("Unsupported driver mode");
            LinkedHashMap<String, String> original = readOverrides();
            String before = original.getOrDefault(pkg, "default");
            if (before.equals(mode)) {
                if (relaunch && component != null) restartInBackground(pkg, component);
                else log("Already configured: " + labelFor(mode));
                refreshDriver(); return;
            }
            String[] oldSettings = serialize(original);
            LinkedHashMap<String, String> updated = new LinkedHashMap<>(original);
            if (mode.equals("default")) updated.remove(pkg); else updated.put(pkg, mode);
            String[] newSettings = serialize(updated);
            try {
                writeSetting(PKGS, newSettings[0]);
                writeSetting(VALUES, newSettings[1]);
                String actualP = command("settings get global " + PKGS);
                String actualV = command("settings get global " + VALUES);
                if (!Objects.equals(newSettings[0].isEmpty() ? "null" : newSettings[0], actualP) ||
                        !Objects.equals(newSettings[1].isEmpty() ? "null" : newSettings[1], actualV))
                    throw new Exception("Android did not accept the settings");
            } catch (Exception ex) {
                try { writeSetting(PKGS, oldSettings[0]); writeSetting(VALUES, oldSettings[1]); }
                catch (Exception rollback) { throw new Exception(ex.getMessage() + "; rollback failed: " + rollback.getMessage()); }
                throw ex;
            }
            if (undo) {
                prefs.edit().remove("undo_before_" + pkg).remove("undo_after_" + pkg)
                        .remove("tested_" + pkg).remove("failed_" + pkg)
                        .remove("launched_" + pkg).apply();
            } else {
                prefs.edit().putString("undo_before_" + pkg, before)
                        .putString("undo_after_" + pkg, mode)
                        .remove("tested_" + pkg).remove("failed_" + pkg)
                        .remove("launched_" + pkg).apply();
            }
            try { command("am force-stop " + quote(pkg)); }
            catch (Exception stopError) { log("Driver changed, but could not force-stop: " + stopError.getMessage()); refreshDriver(); return; }
            if (relaunch && component != null) {
                try { launchAfterStop(pkg, component); }
                catch (Exception launchError) { log("Driver changed and app stopped, but relaunch failed: " + launchError.getMessage()); }
            }
            log((undo ? "Restored" : "Applied") + ": " + labelFor(mode) + "\nPackage: " + pkg +
                    (relaunch ? "\n\nRestart requested. Check whether the app opened and works." :
                    "\n\nLaunch your game to check stability.") +
                    " ANGLE selection alone is not Vulkan verification.");
            runOnUiThread(() -> {
                if (pkg.equals(chosen)) { draftMode = mode; draftDirty = false; refreshTestLabel(); updateRouteUi(); refreshHomeSummary(); }
            });
            refreshDriver();
        } catch (Exception e) { log("Driver change failed: " + e.getMessage()); refreshDriver(); }
    }
    /** Start the selected launchable activity as adb shell; avoids Android 11+ launch restrictions. */
    private String launcherComponent(String pkg) {
        try {
            requireValidPackage(pkg);
            if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") ||
                    pkg.equals("com.android.settings") || pkg.startsWith("com.android.launcher") ||
                    pkg.startsWith("com.miui.home") || pkg.equals("com.google.android.gms") ||
                    pkg.equals("com.google.android.permissioncontroller") ||
                    pkg.equals("com.android.permissioncontroller")) return null;
            // Dynamically protect the current HOME launcher, even on an unknown OEM ROM.
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo homeInfo = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (homeInfo != null && homeInfo.activityInfo != null &&
                    pkg.equals(homeInfo.activityInfo.packageName)) return null;
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            ComponentName c = launch == null ? null : launch.getComponent();
            return c == null ? null : c.flattenToString();
        } catch (Exception ignored) { return null; }
    }
    private void launchAfterStop(String pkg, String component) throws Exception {
        if (component == null || !component.startsWith(pkg + "/"))
            throw new Exception("Launcher component does not match selected package");
        String result = command("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n " + quote(component));
        if (result.contains("Error:") || result.contains("Exception")) throw new Exception(result);
        prefs.edit().putLong("launched_" + pkg, System.currentTimeMillis())
                .remove("tested_" + pkg).remove("failed_" + pkg).apply();
        runOnUiThread(this::refreshTestLabel);
    }
    private void restartInBackground(String pkg, String component) {
        try {
            command("am force-stop " + quote(pkg));
            launchAfterStop(pkg, component);
            log("Restart requested for " + pkg + ". Driver use must be verified in logs; check the game manually.");
        } catch (Exception e) { log("Could not restart " + pkg + ": " + e.getMessage()); }
    }
    private void confirmRestartSelected() {
        final String pkg = chosen;
        if (pkg.isEmpty()) { log("Choose an app first."); return; }
        if (remote == null) { log("Connect Shizuku first."); return; }
        final String component = launcherComponent(pkg);
        if (component == null) { log("No safe launcher activity was found for this app."); return; }
        new AlertDialog.Builder(this).setTitle("Restart selected app?")
                .setMessage("Only " + (friendly.isEmpty() ? pkg : friendly) + " (" + pkg + ") will be force-stopped and launched again." +
                        "\n\nSave your progress first. Existing driver settings stay unchanged. " +
                        "This doesn't force native Vulkan rendering.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", (d, w) -> worker.execute(() -> restartInBackground(pkg, component)))
                .show();
    }

    private void addHwuiRoute(LinearLayout holder, String mode, String name, String subtitle) {
        LinearLayout item = column(); item.setBackground(outlined(SECONDARY, BORDER, 15)); pad(item, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(9); holder.addView(item, lp);
        LinearLayout r = row(); TextView mark = text("○", 22, MUTED, false);
        r.addView(mark, new LinearLayout.LayoutParams(dp(32), -2));
        LinearLayout labels = column(); r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(name, 15, INK, true)); labels.addView(text(subtitle, 12, MUTED, false));
        item.addView(r); hwuiCards.put(mode, item); hwuiMarks.put(mode, mark);
        item.setOnClickListener(v -> {
            hwuiSelected = mode;
            hwuiEdited = !hwuiSelected.equals(hwuiApplied);
            updateHwuiUi();
        });
    }
    private void updateHwuiUi() {
        for (String k : hwuiCards.keySet()) {
            boolean selected = k.equals(hwuiSelected);
            hwuiCards.get(k).setBackground(outlined(selected ? ACTIVE : SECONDARY, selected ? ACCENT : BORDER, 15));
            hwuiMarks.get(k).setText(selected ? "●" : "○");
            hwuiMarks.get(k).setTextColor(selected ? ACCENT : MUTED);
        }
        if (hwuiWarning != null) hwuiWarning.setText(hwuiEdited ?
                "Preview only — not yet applied. This change is system-wide." :
                "HWUI affects Android Views; 3D games control their own graphics API.");
    }
    private void refreshHwui() {
        if (hwuiStatus == null) return;
        if (remote == null) { hwuiStatus.setText("Connect Shizuku to read debug.hwui.renderer"); return; }
        worker.execute(() -> {
            try {
                String value = command("getprop debug.hwui.renderer");
                runOnUiThread(() -> {
                    hwuiApplied = value.isEmpty() ? "none" : value;
                    if (!hwuiEdited) hwuiSelected = hwuiCards.containsKey(hwuiApplied) ? hwuiApplied : "none";
                    else hwuiEdited = !hwuiSelected.equals(hwuiApplied);
                    hwuiStatus.setText("Current system property: " + (value.isEmpty() ? "unset (system default)" : value) +
                            "\nThis is a property readback, not proof that each process uses Vulkan.");
                    updateHwuiUi();
                });
            } catch (Exception e) { runOnUiThread(() -> hwuiStatus.setText("Unable to read renderer: " + e.getMessage())); }
        });
    }
    private void setHwui(boolean restart) {
        if (remote == null) { log("Connect Shizuku first."); return; }
        final String mode = hwuiSelected;
        final String propValue = "none".equals(mode) ? "" : mode;
        if (!mode.equals("skiagl") && !mode.equals("skiavk") && !mode.equals("none")) return;
        final String pkg = chosen;
        final String component = restart ? launcherComponent(pkg) : null;
        if (restart && component == null) { log("Choose a launchable non-system app to restart."); return; }
        new AlertDialog.Builder(this).setTitle("Change Android UI renderer?")
                .setMessage("Set the GLOBAL debug.hwui.renderer property to '" + mode + "'." +
                        "\n\nThis is not a per-game graphics API override. New UI processes may use the chosen Skia backend." +
                        (restart ? "\n\nAfterward only " + pkg + " will be stopped and relaunched." :
                                "\n\nRunning apps are not automatically restarted.") +
                        "\n\nThe property typically resets after reboot. Results depend on your Android build.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(restart ? "Apply & restart" : "Apply globally", (d, w) -> worker.execute(() -> {
                    try {
                        String before = command("getprop debug.hwui.renderer");
                        if (!before.equals(propValue)) {
                            command("setprop debug.hwui.renderer " + quote(propValue));
                            String actual = command("getprop debug.hwui.renderer");
                            if (!propValue.equals(actual)) {
                                try { command("setprop debug.hwui.renderer " + quote(before)); }
                                catch (Exception ignored) {}
                                throw new Exception("Property readback was '" + actual + "' instead of '" + mode + "'");
                            }
                            prefs.edit().putString("hwui_undo_before", before)
                                    .putString("hwui_undo_after", propValue).apply();
                        }
                        if (restart) restartInBackground(pkg, component);
                        log("HWUI property " + mode + " requested successfully. " +
                                "This does NOT make game engines render through Vulkan.");
                    } catch (Exception e) { log("HWUI property update failed: " + e.getMessage()); }
                    refreshHwui();
                })).show();
    }
    private void undoHwui() {
        if (remote == null) { log("Connect Shizuku first."); return; }
        if (!prefs.contains("hwui_undo_before")) { log("No earlier renderer value saved in ANGLE Deck."); return; }
        final String before = prefs.getString("hwui_undo_before", "");
        final String after = prefs.getString("hwui_undo_after", "");
        new AlertDialog.Builder(this).setTitle("Restore previous UI renderer?")
                .setMessage("Restore previous raw property: '" + before + "' (blank means unset)." +
                        "\n\nThis action will be canceled if another app already changed the property.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> worker.execute(() -> {
                    try {
                        String current = command("getprop debug.hwui.renderer");
                        if (!after.equals(current)) throw new Exception("Current property changed outside ANGLE Deck. Refusing to overwrite it.");
                        command("setprop debug.hwui.renderer " + quote(before));
                        String verify = command("getprop debug.hwui.renderer");
                        if (!before.equals(verify)) throw new Exception("Restore failed verification: " + verify);
                        prefs.edit().remove("hwui_undo_before").remove("hwui_undo_after").apply();
                        log("Previous UI renderer property restored. Existing apps may need a restart.");
                    } catch (Exception e) { log("Cannot restore: " + e.getMessage()); }
                    refreshHwui();
                })).show();
    }

    private void launchGame() {
        String pkg = chosen;
        if (pkg.isEmpty()) { log("Choose a game first."); return; }
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) { log("This application has no launcher activity. Open it manually to test."); return; }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            // Record the launch instead of accidentally deleting the launch timestamp.
            prefs.edit().putLong("launched_" + pkg, System.currentTimeMillis())
                    .remove("tested_" + pkg).remove("failed_" + pkg).apply();
            refreshTestLabel();
        } catch (Exception e) { log("Could not launch " + pkg + ": " + e.getMessage()); }
    }
    private void markWorked() {
        if (chosen.isEmpty()) { log("Choose a game first."); return; }
        if (prefs.getLong("launched_" + chosen, 0L) == 0L) {
            log("First launch the game from ANGLE Deck and play normally. Then return to mark it working."); return;
        }
        String pkg = chosen;
        SharedPreferences.Editor edit = prefs.edit().putBoolean("tested_" + pkg, true).remove("failed_" + pkg);
        if ("angle".equals(prefs.getString("last_mode_" + pkg, "default"))) {
            edit.remove("incompatible_angle_" + pkg);
        }
        edit.apply();
        refreshTestLabel(); updateRouteUi(); refreshHomeSummary();
        log("You marked " + chosen + " as working. This records your manual test, not a guaranteed crash-free result.");
    }
    private void refreshTestLabel() {
        if (testLabel == null) return;
        String pkg = chosen;
        String label = pkg.isEmpty() ? "Choose a game to start" :
                prefs.getBoolean("failed_" + pkg, false) ? "⚠ Problem reported — restore or undo the driver" :
                prefs.getBoolean("tested_" + pkg, false) ? "✓ Manually tested as working" :
                prefs.getLong("launched_" + pkg, 0L) != 0L ? "Launch attempted — confirm game stability manually" :
                "Not tested yet";
        if (testLabel != null) testLabel.setText(label);
        if (toolsTestLabel != null) toolsTestLabel.setText(label);
    }
    private void reportProblem() {
        if (chosen.isEmpty()) { log("Choose the affected game first."); return; }
        final String pkg = chosen;
        SharedPreferences.Editor edit = prefs.edit().putBoolean("failed_" + pkg, true)
                .remove("tested_" + pkg);
        if ("angle".equals(prefs.getString("last_mode_" + pkg, "default"))) {
            edit.putBoolean("incompatible_angle_" + pkg, true);
        }
        edit.apply(); refreshTestLabel(); updateRouteUi(); refreshHomeSummary();
        new AlertDialog.Builder(this).setTitle("Game failed or graphics look wrong?")
                .setMessage("You can undo your last driver change or restore the system default. Logs may not show every graphics problem.")
                .setPositiveButton("Undo last change", (dialog, which) -> undoChange())
                .setNeutralButton("Use system default", (dialog, which) -> setDriver("default"))
                .setNegativeButton("Cancel", null).show();
    }
    private void undoChange() {
        final String pkg = chosen;
        if (pkg.isEmpty()) { log("Choose an application first."); return; }
        final String before = prefs.getString("undo_before_" + pkg, "");
        final String after = prefs.getString("undo_after_" + pkg, "");
        if (before.isEmpty() || after.isEmpty()) {
            log("No previous driver change recorded for this application. Use System default if needed."); return;
        }
        if (remote == null) { log("Reconnect Shizuku before undoing."); return; }
        new AlertDialog.Builder(this).setTitle("Restore previous driver?")
                .setMessage("Restore " + labelFor(before) + " for " + pkg +
                        "?\n\nUndo will stop if the setting has changed outside ANGLE Deck.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (dialog, which) -> worker.execute(() -> {
                    try {
                        String current = readOverrides().getOrDefault(pkg, "default");
                        if (!current.equals(after)) {
                            log("Undo canceled: current setting is " + current + ", expected " + after +
                                    ". Another tool may have changed it."); return;
                        }
                        apply(before, pkg, true);
                    } catch (Exception e) { log("Undo failed: " + e.getMessage()); }
                })).show();
    }

    private void explainOtherDrivers() {
        new AlertDialog.Builder(this).setTitle("Other graphics technologies")
                .setMessage("Turnip (Mesa): an open-source Vulkan driver for some Qualcomm Adreno GPUs. It requires a compatible graphics loader, build and hardware.\n\n" +
                        "Zink (Mesa): translates desktop OpenGL into Vulkan. It is not a normal Android per-game driver setting.\n\n" +
                        "SwiftShader: a software Vulkan renderer using the CPU. Not a regular hardware performance driver.\n\n" +
                        "GPU debug/validation layers: diagnostic tools, not game-optimized graphics drivers.\n\n" +
                        "ANGLE Deck can explain and detect official Android driver packages, but Shizuku alone cannot install or swap these custom technologies into arbitrary games.")
                .setPositiveButton("OK", null).show();
    }
    private void explainAngleGles() {
        new AlertDialog.Builder(this)
                .setTitle("ANGLE + OpenGL ES backend")
                .setMessage("ANGLE can use OpenGL ES as its backend, but stock Android 16 only exposes default, native, and angle driver overrides. " +
                        "There is no standard Shizuku command to force ANGLE's GLES backend for another game. " +
                        "This option is informational until a compatible ROM-specific implementation can be detected. " +
                        "Selecting Native OpenGL ES bypasses ANGLE and is not the same thing.")
                .setPositiveButton("Understood", null).show();
    }
    private void addGpuRoute(LinearLayout holder, String mode, String title, String subtitle) {
        LinearLayout box = column(); box.setBackground(outlined(SECONDARY, BORDER, 15)); pad(box, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(9); holder.addView(box, lp);
        LinearLayout header = row();
        TextView dot = text("○", 22, MUTED, false);
        header.addView(dot, new LinearLayout.LayoutParams(dp(32), -2));
        LinearLayout labels = column(); header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(text(title, 15, INK, true)); labels.addView(text(subtitle, 12, MUTED, false));
        box.addView(header); gpuCards.put(mode, box); gpuMarks.put(mode, dot);
        box.setOnClickListener(v -> {
            if ("production".equals(mode) && !productionAvailable) {
                log("No usable production GPU driver package reported by Android. Cannot select it."); return;
            }
            if ("prerelease".equals(mode) && !prereleaseAvailable) {
                log("No usable prerelease GPU driver package reported by Android. Cannot select it."); return;
            }
            gpuDraft = mode; gpuDirty = !gpuDraft.equals(gpuApplied); updateGpuRouteUi();
        });
    }
    private void updateGpuRouteUi() {
        for (String mode : gpuCards.keySet()) {
            boolean available = !("production".equals(mode) && !productionAvailable) &&
                    !("prerelease".equals(mode) && !prereleaseAvailable);
            boolean active = mode.equals(gpuDraft);
            gpuCards.get(mode).setBackground(outlined(active ? ACTIVE : SECONDARY,
                    active ? ACCENT : BORDER, 15));
            gpuMarks.get(mode).setText(available ? (active ? "●" : "○") : "–");
            gpuMarks.get(mode).setTextColor(available ? (active ? ACCENT : MUTED) : WARNING);
            gpuCards.get(mode).setAlpha(available ? 1f : .60f);
        }
        if (gpuApplyButton != null) gpuApplyButton.setText(gpuDirty ? "Apply selected GPU driver" : "Confirm / reapply GPU driver");
        if (gpuDriverWarning != null) {
            String msg = "";
            if ("prerelease".equals(gpuDraft)) msg = "Prerelease drivers may be rejected for non-debuggable games, including Mobile Legends.";
            if ("production".equals(gpuDraft)) msg = "Requires a compatible updated GPU driver packaged by your manufacturer.";
            gpuDriverWarning.setText(msg);
            gpuDriverWarning.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
    private String readSetting(String key) throws Exception {
        String result = command("settings get global " + key).trim();
        return "null".equals(result) ? "" : result;
    }
    private java.util.LinkedHashSet<String> readPackageSet(String key) throws Exception {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String value = readSetting(key);
        if (value.isEmpty()) return set;
        for (String pkg : value.split(",", -1)) {
            requireValidPackage(pkg);
            if (!set.add(pkg)) throw new Exception("Duplicate package in " + key + "; refusing update");
        }
        return set;
    }
    private String serializePackages(java.util.LinkedHashSet<String> list) { return String.join(",", list); }
    private String gpuLabel(String mode) {
        switch (mode) {
            case "system": return "System graphics driver requested";
            case "production": return "Game / production driver requested";
            case "prerelease": return "Developer / prerelease driver requested";
            default: return "Device default (no per-app GPU override)";
        }
    }
    private String discoverDriverPackage(String prop) throws Exception {
        String pkg = command("getprop " + prop).trim();
        if (pkg.isEmpty() || !pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) return "";
        String out = command("pm path " + quote(pkg) + " 2>/dev/null || true");
        return out.contains("package:") ? pkg : "";
    }
    private void refreshGpuDriver() {
        final String pkg = chosen;
        if (remote == null) { if (gpuDriverLabel != null) gpuDriverLabel.setText("Connect Shizuku to detect GPU drivers"); return; }
        if (gpuDriverLabel != null) gpuDriverLabel.setText("Reading GPU driver configuration…");
        worker.execute(() -> {
            try {
                final String prod = discoverDriverPackage("ro.gfx.driver.0");
                final String pre = discoverDriverPackage("ro.gfx.driver.1");
                final String global = readSetting(GPU_ALL);
                final boolean hasProd = !prod.isEmpty(), hasPre = !pre.isEmpty();
                final java.util.LinkedHashSet<String> optIn = readPackageSet(GPU_PROD_IN);
                final java.util.LinkedHashSet<String> optOut = readPackageSet(GPU_PROD_OUT);
                final java.util.LinkedHashSet<String> prerelease = readPackageSet(GPU_PRE_IN);
                final String mode = pkg.isEmpty() ? "default" : optOut.contains(pkg) ? "system" :
                        prerelease.contains(pkg) ? "prerelease" : optIn.contains(pkg) ? "production" : "default";
                final String globalNote = global.isEmpty() || "0".equals(global) ? "" :
                        "\nGlobal GPU policy = " + global + ". Per-app choices may be overridden.";
                runOnUiThread(() -> {
                    gpuProductionPkg = prod; gpuPrereleasePkg = pre;
                    productionAvailable = hasProd; prereleaseAvailable = hasPre;
                    if (gpuDriverPackages != null) gpuDriverPackages.setText(
                            "Production: " + (hasProd ? prod : "Not installed / not exposed") +
                            "\nPrerelease: " + (hasPre ? pre : "Not installed / not exposed"));
                    if (!pkg.equals(chosen)) { updateGpuRouteUi(); return; }
                    gpuApplied = mode;
                    if (!gpuDirty) gpuDraft = mode;
                    else gpuDirty = !gpuDraft.equals(mode);
                    if (gpuDriverLabel != null) gpuDriverLabel.setText(gpuLabel(mode) + globalNote +
                            "\nDriver selection is a request; actual loading must be verified.");
                    updateGpuRouteUi();
                    refreshHomeSummary();
                });
            } catch (Exception e) { runOnUiThread(() -> {
                if (gpuDriverLabel != null) gpuDriverLabel.setText("GPU driver read failed: " + e.getMessage());
            }); }
        });
    }
    private void setGpuDriver() {
        if (chosen.isEmpty()) { log("Choose a game first."); return; }
        if (remote == null) { log("Connect Shizuku first."); connect(); return; }
        if (launcherComponent(chosen) == null) { log("Select a launchable non-protected app."); return; }
        final String mode = gpuDraft, pkg = chosen;
        if ("production".equals(mode) && !productionAvailable) { log("Production driver unavailable"); return; }
        if ("prerelease".equals(mode) && !prereleaseAvailable) { log("Prerelease driver unavailable"); return; }
        new AlertDialog.Builder(this).setTitle("Change GPU driver?")
                .setMessage("App: " + pkg + "\n" + gpuLabel(mode) +
                        "\n\nOnly this game's GPU driver override will be changed. ANGLE settings stay separate. " +
                        "Android can ignore unsupported driver requests or use its fallback. The game will be force-stopped." +
                        ("prerelease".equals(mode) ? "\n\nPrerelease is intended for compatible or debuggable apps; some games cannot use it." : ""))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> worker.execute(() -> applyGpuDriver(pkg, mode)))
                .show();
    }
    private void applyGpuDriver(String pkg, String mode) {
        try {
            requireValidPackage(pkg);
            if (launcherComponent(pkg) == null) throw new Exception("Protected or non-launchable package: refusing force-stop");
            if (!mode.equals("default") && !mode.equals("system") && !mode.equals("production") && !mode.equals("prerelease"))
                throw new Exception("Unknown GPU driver");
            String global = readSetting(GPU_ALL);
            if (!global.isEmpty() && !"0".equals(global))
                throw new Exception("Global GPU driver override is set to " + global + ". Disable it in Developer Options first.");
            if (mode.equals("production") && discoverDriverPackage("ro.gfx.driver.0").isEmpty())
                throw new Exception("Production driver missing");
            if (mode.equals("prerelease") && discoverDriverPackage("ro.gfx.driver.1").isEmpty())
                throw new Exception("Prerelease driver missing");
            java.util.LinkedHashMap<String,java.util.LinkedHashSet<String>> previous = new java.util.LinkedHashMap<>();
            for (String key : new String[]{GPU_PROD_IN,GPU_PROD_OUT,GPU_PRE_IN}) previous.put(key,readPackageSet(key));
            java.util.LinkedHashMap<String,java.util.LinkedHashSet<String>> next = new java.util.LinkedHashMap<>();
            for (String key : previous.keySet()) { next.put(key,new java.util.LinkedHashSet<>(previous.get(key))); next.get(key).remove(pkg); }
            if (mode.equals("system")) next.get(GPU_PROD_OUT).add(pkg);
            else if (mode.equals("production")) next.get(GPU_PROD_IN).add(pkg);
            else if (mode.equals("prerelease")) next.get(GPU_PRE_IN).add(pkg);
            // Settings.Global updates are not atomic; attempt rollback if any step fails.
            try {
                for (String key : next.keySet()) writeSetting(key,serializePackages(next.get(key)));
                for (String key : next.keySet()) {
                    if (!readSetting(key).equals(serializePackages(next.get(key))))
                        throw new Exception("Failed verification for " + key);
                }
            } catch (Exception failure) {
                String restoreError = "";
                for (String key : previous.keySet()) {
                    try { writeSetting(key,serializePackages(previous.get(key))); }
                    catch (Exception err) { restoreError += "\nRestore error " + key + ": " + err.getMessage(); }
                }
                throw new Exception(failure.getMessage() + restoreError);
            }
            prefs.edit().remove("tested_" + pkg).remove("launched_" + pkg).apply();
            try { command("am force-stop " + quote(pkg)); }
            catch (Exception e) { log("Driver selection saved, but could not stop app: " + e.getMessage()); }
            log("GPU driver preference saved: " + gpuLabel(mode) + "\n" + pkg +
                    "\nNow launch and test this game. Hardware may fall back to another driver.");
            runOnUiThread(() -> { if (pkg.equals(chosen)) { gpuApplied = mode; gpuDraft = mode; gpuDirty = false; refreshTestLabel(); updateGpuRouteUi(); } });
            refreshGpuDriver();
        } catch (Exception e) { log("GPU driver change failed: " + e.getMessage()); refreshGpuDriver(); }
    }
    private void discoverDrivers() {
        execute("echo '=== Graphics hardware ==='; getprop ro.hardware.egl; getprop ro.hardware.vulkan; " +
                "echo '=== Updatable driver packages ==='; getprop ro.gfx.driver.0; getprop ro.gfx.driver.1; " +
                "echo '=== Global updatable driver mode ==='; settings get global updatable_driver_all_apps; " +
                "echo '=== Current driver selections ==='; settings get global " + GPU_PROD_IN + "; " +
                "settings get global " + GPU_PROD_OUT + "; settings get global " + GPU_PRE_IN + "; " +
                "echo '=== Vulkan ==='; pm list features | grep -i vulkan || true; " +
                "echo '=== ANGLE ==='; getprop ro.angle.supported; settings get global " + PKGS + "; " +
                "settings get global " + VALUES, true);
    }

}
