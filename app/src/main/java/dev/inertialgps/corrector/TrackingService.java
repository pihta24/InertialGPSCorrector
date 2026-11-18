package dev.inertialgps.corrector;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

public final class TrackingService extends Service implements LocationListener, SensorEventListener {
    static final String START = "dev.inertialgps.corrector.START";
    static final String STOP = "dev.inertialgps.corrector.STOP";
    static final String MOCK_MODE = "mockMode";
    private static final String CHANNEL = "tracking";
    private static final String ALERT_CHANNEL = "gps_warnings";
    private static final int NOTIFICATION = 1;
    private static final int GPS_ALERT = 2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] rotation = new float[9];
    private final Binder binder = new LocalBinder();
    private final Runnable tick = this::publish;
    private FusedLocationProviderClient fused;
    private LocationManager gps;
    private SensorManager sensors;
    private PowerManager.WakeLock motionWakeLock;
    private Fusion fusion;
    private Location raw;
    private boolean running, stopping, destroyed, sensorPair;
    private PublishMode mockMode = PublishMode.OFF;
    private boolean mockFailed, enablingMock, disablingMock, mockActive, sending;
    private long orientationTime, publishedTime, startedAt;
    private Fusion.GpsState notifiedState;
    private String warning;
    private double declination;
    private String status = "Tracking stopped";
    private String mockStatus = "Off";

    final class LocalBinder extends Binder {
        TrackingService service() { return TrackingService.this; }
    }

    record Snapshot(boolean running, boolean stopping, Location raw, Fusion.Estimate corrected,
                    String status, String mockStatus, PublishMode mockMode) {}

    Snapshot snapshot() {
        return new Snapshot(running, stopping, raw,
                running ? fusion.estimate(SystemClock.elapsedRealtimeNanos()) : null,
                warning != null ? warning : status, mockStatus, mockMode);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        gps = getSystemService(LocationManager.class);
        sensors = getSystemService(SensorManager.class);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Location tracking", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(
                new NotificationChannel(ALERT_CHANNEL, "GPS warnings", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            endTracking();
        } else if (START.equals(intent.getAction()) && !running && !stopping) {
            try {
                startForeground(NOTIFICATION, notification());
                startTracking(PublishMode.fromName(intent.getStringExtra(MOCK_MODE)));
            } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                status = "Couldn't start tracking. Check precise location permission and device settings.";
                endTracking();
            }
        }
        return START_NOT_STICKY;
    }

    private void startTracking(PublishMode mode) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Precise location is required");
        }
        fusion = new Fusion();
        startedAt = SystemClock.elapsedRealtimeNanos();
        notifiedState = null;
        warning = null;
        raw = null;
        running = true;
        orientationTime = publishedTime = 0;
        declination = 0;
        mockStatus = "Off";
        Sensor orientation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorPair = orientation != null && acceleration != null
                && sensors.registerListener(this, orientation, 20_000, main)
                && sensors.registerListener(this, acceleration, 20_000, main);
        if (!sensorPair) sensors.unregisterListener(this);
        status = gps.isProviderEnabled(LocationManager.GPS_PROVIDER) ? trackingStatus() : "Turn on device location";
        gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this, Looper.getMainLooper());
        mockMode = mode;
        mockFailed = false;
        mockStatus = standbyStatus();
        // Non-wakeup sensors and the outage timer need the CPU while the screen is off.
        motionWakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InertialGPSCorrector:tracking");
        motionWakeLock.acquire();
        main.post(tick);
    }

    private String trackingStatus() {
        return sensorPair ? "Tracking GPS and motion sensors" : "Motion sensors unavailable · GPS only";
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TrackingService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL).setContentTitle(getString(R.string.app_name))
                .setContentText("Tracking location · tap to see estimates")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void updateWarning(Fusion.GpsState state, boolean hasFallback, boolean waitingForFallback) {
        String message = switch (state) {
            case UNRELIABLE -> "GPS unreliable · rejected an inaccurate or implausible fix";
            case UNAVAILABLE -> "GPS unavailable · no recent fix";
            default -> null;
        };
        if (message != null) message += hasFallback
                ? "\nMotion estimate available; accuracy will degrade."
                : waitingForFallback ? "\nWaiting for 8 seconds without a trusted fix."
                : "\nNo inertial estimate available.";
        if (state == notifiedState && java.util.Objects.equals(message, warning)) return;
        notifiedState = state;
        warning = message;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (message == null) {
            notifications.cancel(GPS_ALERT);
        } else if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notifications.notify(GPS_ALERT, new Notification.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle(state == Fusion.GpsState.UNAVAILABLE ? "GPS unavailable" : "GPS unreliable")
                    .setContentText(message.replace('\n', ' ')).setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(open).setOnlyAlertOnce(true).build());
        }
    }

    private Task<Void> setMockMode(boolean enabled) {
        try {
            return fused.setMockMode(enabled);
        } catch (SecurityException e) {
            return Tasks.forException(e);
        }
    }

    private Task<Void> setMockLocation(Location location) {
        try {
            return fused.setMockLocation(location);
        } catch (SecurityException e) {
            return Tasks.forException(e);
        }
    }

    void setPublishMode(PublishMode mode) {
        if (!running || stopping) return;
        if (mockMode == mode && !mockFailed) return;
        mockMode = mode;
        mockFailed = false;
        mockStatus = standbyStatus();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private String standbyStatus() {
        return switch (mockMode) {
            case OFF -> "Off";
            case OUTAGES -> "Standby · waiting for GPS fallback";
            case ANYTIME -> "Standby · waiting for a usable estimate";
        };
    }

    private void enableMock() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            mockFailed = true;
            mockStatus = "Google Play services unavailable · preview only";
            return;
        }
        if (fused == null) fused = LocationServices.getFusedLocationProviderClient(this);
        enablingMock = true;
        mockStatus = "Enabling…";
        setMockMode(true).addOnCompleteListener(task -> {
            enablingMock = false;
            mockActive = task.isSuccessful();
            if (destroyed || stopping) {
                disableMock();
            } else {
                if (mockMode.estimate(fusion, SystemClock.elapsedRealtimeNanos()) == null) {
                    mockStatus = standbyStatus();
                    disableMock();
                } else {
                    mockFailed = !mockActive;
                    mockStatus = mockActive ? "Ready to publish"
                            : "Not enabled · select this app in Developer options";
                }
            }
        });
    }

    private void disableMock() {
        if (enablingMock || disablingMock || sending) return;
        if (stopping && !mockActive) mockStatus = "Off";
        if (mockActive) {
            disablingMock = true;
            setMockMode(false).addOnCompleteListener(task -> {
                disablingMock = false;
                mockActive = false;
                publishedTime = 0;
                if (!task.isSuccessful()) mockFailed = true;
                if (!task.isSuccessful()) {
                    mockStatus = "Couldn't clear mock mode · deselect this app in Developer options";
                } else if (!running) {
                    mockStatus = "Off";
                } else if (!mockFailed) {
                    mockStatus = standbyStatus();
                }
                if (stopping) finishStop();
            });
        } else if (stopping) {
            finishStop();
        }
    }

    private void finishStop() {
        stopping = false;
        if (!destroyed) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void endTracking() {
        if (stopping) return;
        if (running) status = "Tracking stopped";
        running = false;
        stopping = true;
        main.removeCallbacks(tick);
        if (motionWakeLock != null && motionWakeLock.isHeld()) motionWakeLock.release();
        motionWakeLock = null;
        warning = null;
        getSystemService(NotificationManager.class).cancel(GPS_ALERT);
        gps.removeUpdates(this);
        sensors.unregisterListener(this);
        disableMock();
    }

    @Override public void onDestroy() {
        destroyed = true;
        endTracking();
        super.onDestroy();
    }

    private void publish() {
        if (!running) return;
        long now = SystemClock.elapsedRealtimeNanos();
        updateWarning(fusion.gpsState(now, startedAt), fusion.fallback(now) != null, fusion.inFallbackDelay(now));
        Fusion.Estimate estimate = mockFailed ? null : mockMode.estimate(fusion, now);
        if (estimate == null) {
            if (mockActive && !sending) disableMock();
        } else if (!mockActive && !enablingMock && !disablingMock) {
            enableMock();
        } else if (mockActive && !disablingMock && !sending && estimate.time() > publishedTime) {
            Location corrected = new Location("fused");
            corrected.setLatitude(estimate.latitude());
            corrected.setLongitude(estimate.longitude());
            corrected.setAccuracy((float) estimate.accuracy());
            corrected.setElapsedRealtimeNanos(estimate.time());
            corrected.setTime(System.currentTimeMillis()
                    - (SystemClock.elapsedRealtimeNanos() - estimate.time()) / 1_000_000);
            publishedTime = estimate.time();
            sending = true;
            setMockLocation(corrected).addOnCompleteListener(task -> {
                sending = false;
                if (stopping || mockMode.estimate(fusion, SystemClock.elapsedRealtimeNanos()) == null) {
                    disableMock();
                } else if (running && mockActive) {
                    if (task.isSuccessful()) {
                        mockStatus = estimate.inertial() ? "Publishing motion-assisted location"
                                : "Publishing GPS-based location";
                    } else {
                        mockFailed = true;
                        mockStatus = "Publishing failed · check Developer options";
                        disableMock();
                    }
                }
            });
        }
        main.postDelayed(tick, 500);
    }

    @Override public void onLocationChanged(Location location) {
        if (!running || (Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider())) return;
        if (raw == null || location.getElapsedRealtimeNanos() > raw.getElapsedRealtimeNanos()) raw = location;
        double speedAccuracy = location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : -1;
        double bearing = location.hasBearing() && (!location.hasBearingAccuracy() || location.getBearingAccuracyDegrees() <= 35)
                ? location.getBearing() : Double.NaN;
        if (fusion.accept(new Fusion.Fix(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : -1, location.getElapsedRealtimeNanos(),
                location.hasSpeed() ? location.getSpeed() : -1, bearing, speedAccuracy), SystemClock.elapsedRealtimeNanos())) {
            declination = Math.toRadians(new GeomagneticField((float) location.getLatitude(),
                    (float) location.getLongitude(), location.hasAltitude() ? (float) location.getAltitude() : 0,
                    location.getTime()).getDeclination());
        }
    }

    @Override public void onProviderEnabled(String provider) { if (running) status = trackingStatus(); }
    @Override public void onProviderDisabled(String provider) { if (running) status = "Turn on device location"; }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running || !sensorPair) return;
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || (event.values.length >= 5
                    && event.values[4] >= 0 && event.values[4] > Math.PI / 4)) {
                orientationTime = 0;
                fusion.invalidateMotion();
            } else {
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                orientationTime = event.timestamp;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            if (orientationTime == 0 || Math.abs(event.timestamp - orientationTime) > 200_000_000L) {
                fusion.invalidateMotion();
                return;
            }
            float x = event.values[0], y = event.values[1], z = event.values[2];
            double magneticEast = rotation[0] * x + rotation[1] * y + rotation[2] * z;
            double magneticNorth = rotation[3] * x + rotation[4] * y + rotation[5] * z;
            double sin = Math.sin(declination), cos = Math.cos(declination);
            fusion.motion(event.timestamp, cos * magneticEast + sin * magneticNorth,
                    cos * magneticNorth - sin * magneticEast);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
