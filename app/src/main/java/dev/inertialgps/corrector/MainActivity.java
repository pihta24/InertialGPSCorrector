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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
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
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            tracking = ((TrackingService.LocalBinder) service).service();
        }

        @Override public void onServiceDisconnected(ComponentName name) { tracking = null; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        int pad = dp(24);
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
        scroll.addView(content);
        heading(content, getString(R.string.app_name), 30);
        text(content, "Compare the GPS fix with a motion-assisted estimate. Sensor drift limits correction to short gaps.");
        heading(content, "Tracking", 20);
        status = text(content, "Tracking stopped");
        heading(content, "Raw GPS", 20);
        raw = text(content, "—");
        heading(content, "Corrected estimate", 20);
        corrected = text(content, "—");
        heading(content, "Mock location", 20);
        if (state != null) mockMode = PublishMode.fromName(state.getString("mockMode"));
        mock = button(content, modeLabel(), this::choosePublishMode);
        mockStatus = text(content, "Off");
        text(content, "Select this app as your mock location app in Developer options. GPS outages only waits 8 seconds without a trusted fix. Anytime publishes whenever an estimate is available. Allow notifications for GPS warnings.");
        button(content, "Open Developer options", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        start = button(content, "Start tracking", this::requestStart);
        stop = button(content, "Stop tracking", () -> {
            stop.setEnabled(false);
            startService(new Intent(this, TrackingService.class).setAction(TrackingService.STOP));
        });
        stop.setEnabled(false);
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("mockMode", mockMode.name());
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
            startForegroundService(new Intent(this, TrackingService.class).setAction(TrackingService.START)
                    .putExtra(TrackingService.MOCK_MODE, mockMode.name()));
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
                            + (estimate.inertial() ? " · GPS + sensors" : " · GPS only"));
        }
        ui.postDelayed(update, 300);
    }

    private static String format(double lat, double lon, double accuracy, long timestamp) {
        String error = Double.isNaN(accuracy) ? "accuracy unknown" : String.format(Locale.US, "±%.0f m", accuracy);
        return String.format(Locale.US, "%.6f, %.6f\n%s · %.1f s old", lat, lon, error,
                Math.max(0, SystemClock.elapsedRealtimeNanos() - timestamp) / 1e9);
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this);
        view.setTextSize(16);
        view.setText(value);
        view.setPadding(0, dp(4), 0, dp(10));
        parent.addView(view);
        return view;
    }

    private void heading(LinearLayout parent, String value, int size) {
        TextView view = text(parent, value);
        view.setTextSize(size);
        view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        int match = LinearLayout.LayoutParams.MATCH_PARENT, wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
        parent.addView(button, new LinearLayout.LayoutParams(match, wrap));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
