package dev.inertialgps.corrector;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowInsets;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int NAVY = 0xff091628;
    private static final int PANEL = 0xff132743;
    private static final int MINT = 0xff57e4c6;
    private static final int PERIWINKLE = 0xffb5c8ff;
    private static final int PALE = 0xffe1fff8;
    private static final int LOCATION_PERMISSION = 10;
    private static final int NOTIFICATION_PERMISSION = 11;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable update = this::update;
    private TextView status, raw, corrected, mockStatus;
    private Button start, stop;
    private Button mock;
    private PublishMode mockMode = PublishMode.OFF;
    private static final String[] MODE_LABELS = {"Off", "GPS outages only", "Anytime"};
    private TrackingService tracking;
    private boolean bound, starting;
    private double selectedLatitude, selectedLongitude;
    private boolean hasSelectedLocation;
    private TextView selectedLocation;
    private Button mapButton;
    private AlertDialog mapDialog;
    private static final String MAP_HOST = "appassets.androidplatform.net";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            tracking = ((TrackingService.LocalBinder) service).service();
        }

        @Override public void onServiceDisconnected(ComponentName name) { tracking = null; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(NAVY);
        int pad = dp(20);
        scroll.setPadding(pad, pad, pad, pad);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            }
            return insets;
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(8), dp(4), dp(16));
        scroll.addView(content);
        LinearLayout hero = card(content);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher);
        mark.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        heading(hero, getString(R.string.app_name), 28);
        text(hero, "GPS and motion tracking");
        status = text(hero, "Tracking stopped");
        status.setTextColor(MINT);
        start = button(hero, "Start tracking", this::requestStart, true);
        stop = button(hero, "Stop tracking", () -> {
            stop.setEnabled(false);
            startService(new Intent(this, TrackingService.class).setAction(TrackingService.STOP));
        });
        stop.setEnabled(false);

        LinearLayout position = card(content);
        heading(position, "Best available position", 20);
        corrected = text(position, "—");
        heading(position, "Raw GPS signal", 17);
        raw = text(position, "—");

        LinearLayout origin = card(content);
        heading(origin, "Start location", 20);
        selectedLocation = text(origin, "Use the first trusted GPS fix");
        mapButton = button(origin, "Select on map", this::showMapPicker);

        LinearLayout output = card(content);
        heading(output, "Mock location output", 20);
        if (state != null) {
            mockMode = PublishMode.fromName(state.getString("mockMode"));
            hasSelectedLocation = state.getBoolean("hasSelectedLocation");
            selectedLatitude = state.getDouble("selectedLatitude");
            selectedLongitude = state.getDouble("selectedLongitude");
        }
        mock = button(output, modeLabel(), this::choosePublishMode);
        if (hasSelectedLocation) updateSelectedLocation();
        mockStatus = text(output, "Off");
        text(output, "Outages only waits 8 seconds without a trusted GPS fix. To publish, select this app as your mock location app in Developer options.");
        button(output, "Open Developer options", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("mockMode", mockMode.name());
        out.putBoolean("hasSelectedLocation", hasSelectedLocation);
        out.putDouble("selectedLatitude", selectedLatitude);
        out.putDouble("selectedLongitude", selectedLongitude);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, TrackingService.class), connection, Context.BIND_AUTO_CREATE);
        ui.post(update);
    }

    @Override protected void onStop() {
        ui.removeCallbacks(update);
        if (bound) unbindService(connection);
        bound = false;
        tracking = null;
        super.onStop();
    }

    private String modeLabel() {
        return "Publish: " + MODE_LABELS[mockMode.ordinal()];
    }

    private void choosePublishMode() {
        new AlertDialog.Builder(this).setTitle("Mock publishing mode")
                .setSingleChoiceItems(MODE_LABELS, mockMode.ordinal(), (dialog, index) -> {
                    mockMode = PublishMode.values()[index];
                    mock.setText(modeLabel());
                    if (tracking != null) tracking.setPublishMode(mockMode);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
        } else {
            withNotificationPermission();
        }
    }

    private void withNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        } else {
            startTracking();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                withNotificationPermission();
            } else {
                Toast.makeText(this, "Grant precise location in Android app permissions to use GPS.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION && results.length > 0) {
            startTracking();
        }
    }

    private void startTracking() {
        try {
            starting = true;
            start.setEnabled(false);
            Intent intent = new Intent(this, TrackingService.class).setAction(TrackingService.START)
                    .putExtra(TrackingService.MOCK_MODE, mockMode.name());
            if (hasSelectedLocation) intent.putExtra(TrackingService.START_LAT, selectedLatitude)
                    .putExtra(TrackingService.START_LON, selectedLongitude);
            startForegroundService(intent);
            ui.postDelayed(() -> starting = false, 1_000);
        } catch (IllegalStateException | SecurityException e) {
            starting = false;
            Toast.makeText(this, "Couldn't start tracking. Open the app and check location permission.", Toast.LENGTH_LONG).show();
        }
    }

    private void update() {
        TrackingService.Snapshot data = tracking == null ? null : tracking.snapshot();
        boolean busy = data != null && (data.running() || data.stopping());
        start.setEnabled(!busy && !starting);
        mapButton.setEnabled(!busy && !starting);
        stop.setEnabled(data != null && data.running());
        mock.setEnabled((!starting || (data != null && data.running())) && (data == null || !data.stopping()));
        if (data != null && data.running() && mockMode != data.mockMode()) {
            mockMode = data.mockMode();
            mock.setText(modeLabel());
        }
        if (data != null) {
            status.setText(data.stopping() ? "Stopping…" : data.status());
            mockStatus.setText(data.mockStatus());
            Location gps = data.raw();
            raw.setText(gps == null ? (data.running() ? "Waiting for a GPS fix…" : "—")
                    : format(gps.getLatitude(), gps.getLongitude(), gps.hasAccuracy() ? gps.getAccuracy() : Double.NaN,
                            gps.getElapsedRealtimeNanos()) + (data.running() ? "" : " · last fix"));
            Fusion.Estimate estimate = data.corrected();
            corrected.setText(estimate == null ? (data.running() ? "No current estimate" : "—")
                    : format(estimate.latitude(), estimate.longitude(), estimate.accuracy(), estimate.time())
                            + (estimate.manual() ? (estimate.inertial() ? " · Manual start + sensors" : " · Manual start")
                            : estimate.inertial() ? " · GPS + sensors" : " · GPS only"));
        }
        ui.postDelayed(update, 300);
    }

    private static String format(double lat, double lon, double accuracy, long timestamp) {
        String error = Double.isNaN(accuracy) ? "accuracy unknown" : String.format(Locale.US, "±%.0f m", accuracy);
        return String.format(Locale.US, "%.6f, %.6f\n%s · %.1f s old", lat, lon, error,
                Math.max(0, SystemClock.elapsedRealtimeNanos() - timestamp) / 1e9);
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled") // Only bundled scripts run; no native bridge.
    private void showMapPicker() {
        WebView map = new WebView(this);
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " InertialGPSCorrector/1.1");
        map.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getPath();
                if ("https".equals(url.getScheme()) && path != null) {
                    if ("tile.openstreetmap.org".equals(url.getHost()) && path.matches("/[0-9]+/[0-9]+/[0-9]+\\.png")) return null;
                    if (MAP_HOST.equals(url.getHost()) && path.matches("/map/([a-zA-Z.-]+|images/[a-zA-Z0-9@.-]+)")) {
                        String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css"
                                : path.endsWith(".png") ? "image/png" : "text/html";
                        try {
                            return new WebResourceResponse(mime, mime.equals("image/png") ? null : "UTF-8",
                                    getAssets().open(path.substring(1)));
                        } catch (IOException ignored) { }
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (hasSelectedLocation) view.evaluateJavascript(
                        "window.centerMap(" + selectedLatitude + "," + selectedLongitude + ")", null);
            }
        });
        LinearLayout panel = new LinearLayout(this);
        panel.addView(map, new LinearLayout.LayoutParams(-1,
                Math.min(dp(480), getResources().getDisplayMetrics().heightPixels / 2)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Choose start location").setView(panel)
                .setPositiveButton("Use pin", null).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Use GPS", (d, which) -> {
                    hasSelectedLocation = false;
                    updateSelectedLocation();
                }).create();
        mapDialog = dialog;
        dialog.setOnDismissListener(d -> {
            mapDialog = null;
            panel.removeView(map);
            map.destroy();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                map.evaluateJavascript("window.startPoint || null", result -> {
                    if (!dialog.isShowing()) return;
                    try {
                        JSONArray point = new JSONArray(result == null ? "null" : result);
                        double lat = point.getDouble(0), lon = point.getDouble(1);
                        if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) throw new JSONException("Invalid point");
                        selectedLatitude = lat;
                        selectedLongitude = lon;
                        hasSelectedLocation = true;
                        updateSelectedLocation();
                        dialog.dismiss();
                    } catch (JSONException e) {
                        Toast.makeText(this, "Tap the map to place a pin first.", Toast.LENGTH_SHORT).show();
                    }
                }));
        map.loadUrl("https://" + MAP_HOST + "/map/picker.html");
    }

    @Override protected void onDestroy() {
        if (mapDialog != null) mapDialog.dismiss();
        super.onDestroy();
    }

    private void updateSelectedLocation() {
        selectedLocation.setText(hasSelectedLocation ? String.format(Locale.US,
                "Manual start: %.6f, %.6f", selectedLatitude, selectedLongitude) : "Use the first trusted GPS fix");
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this);
        view.setTextSize(16);
        view.setTextColor(PERIWINKLE);
        view.setText(value);
        view.setPadding(dp(4), dp(6), dp(4), dp(12));
        parent.addView(view);
        return view;
    }

    private void heading(LinearLayout parent, String value, int size) {
        TextView view = text(parent, value);
        view.setTextSize(size);
        view.setTextColor(PALE);
        view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(PANEL, 0xff304765, 24));
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(1), stroke);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        return button(parent, label, action, false);
    }

    private Button button(LinearLayout parent, String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        int[] disabled = {-android.R.attr.state_enabled};
        button.setTextColor(new ColorStateList(new int[][]{disabled, {}},
                new int[]{0xff9aaac5, primary ? NAVY : PALE}));
        button.setAllCaps(false);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        StateListDrawable states = new StateListDrawable();
        states.addState(disabled, shape(PANEL, 0xff304765, 18));
        states.addState(new int[0], shape(primary ? MINT : NAVY, primary ? MINT : 0xff526c93, 18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33091628 : 0x33b5c8ff), states, null));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
