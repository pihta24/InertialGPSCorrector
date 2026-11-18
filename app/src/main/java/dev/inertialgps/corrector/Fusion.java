package dev.inertialgps.corrector;

final class Fusion {
    static final long GPS_TIMEOUT_NS = 8_000_000_000L;
    private static final double MAX_INERTIAL_ACCURACY_METERS = 200;

    enum GpsState { WAITING, GOOD, UNRELIABLE, UNAVAILABLE }
    private static final long MAX_SENSOR_GAP_NS = 250_000_000L;
    private static final double EARTH_RADIUS = 6_371_008.8;

    record Fix(double latitude, double longitude, double accuracy, long time,
               double speed, double bearing, double speedAccuracy) {}
    record Estimate(double latitude, double longitude, double accuracy, long time, boolean inertial) {}

    private Fix anchor;
    private Fix lastFix;
    private long lastFixTime, lastSampleTime;
    private boolean suspect;
    private Fix candidate;
    private long candidateSince;
    private int candidateCount;
    private long stateTime;
    private long motionTime;
    private boolean usableMotion;
    private double east, north, ve, vn, variance;
    private double motionVariance;

    boolean accept(Fix fix, long now) {
        if (fix.time <= lastSampleTime || fix.time > now || now - fix.time > 2_000_000_000L) return false;
        lastSampleTime = fix.time;
        if (!Double.isFinite(fix.latitude) || Math.abs(fix.latitude) > 90
                || !Double.isFinite(fix.longitude) || Math.abs(fix.longitude) > 180
                || !Double.isFinite(fix.accuracy) || fix.accuracy <= 0 || fix.accuracy > 80) {
            suspect = true;
            candidate = null;
            return false;
        }
        boolean canFuse = lastFix != null && withinInertialBudget() && motionReady(now);
        double age = seconds(stateTime - fix.time);
        double error = Math.max(3, fix.accuracy) + Math.abs(age) * (2 + Math.hypot(ve, vn) * 0.2);
        double residualE = 0, residualN = 0;
        boolean jump = lastFix != null && implausibleStep(lastFix, fix);
        if (canFuse) {
            double measuredEast = Math.toRadians(wrap(fix.longitude - anchor.longitude))
                    * EARTH_RADIUS * Math.cos(Math.toRadians(anchor.latitude));
            double measuredNorth = Math.toRadians(fix.latitude - anchor.latitude) * EARTH_RADIUS;
            residualE = measuredEast - (east - age * ve);
            residualN = measuredNorth - (north - age * vn);
            jump |= Math.hypot(residualE, residualN) > Math.max(25, 3 * Math.sqrt(variance + error * error));
        }
        if (jump || (candidate != null && !withinInertialBudget())) {
            suspect = true;
            boolean confirmed = confirmCandidate(fix);
            // Don't re-anchor to a conflicting track while a recent inertial estimate still exists.
            if (canFuse || !confirmed) return false;
            resetPosition(fix);
        } else if (!canFuse) {
            resetPosition(fix);
        } else {
            double gain = variance / (variance + error * error);
            east += gain * residualE;
            north += gain * residualN;
            variance = Math.max(9, (1 - gain) * variance);
        }
        suspect = false;
        candidate = null;
        if (fix.speed >= 0 && fix.speed <= 100 && fix.speedAccuracy >= 0 && fix.speedAccuracy <= 3
                && (fix.speed < 0.5 || Double.isFinite(fix.bearing))) {
            double bearing = Math.toRadians(fix.bearing);
            ve = fix.speed < 0.5 ? 0 : fix.speed * Math.sin(bearing);
            vn = fix.speed < 0.5 ? 0 : fix.speed * Math.cos(bearing);
            motionVariance = Math.max(1, fix.speedAccuracy * fix.speedAccuracy);
        }
        lastFix = fix;
        lastFixTime = fix.time;
        return true;
    }

    void motion(long time, double ae, double an) {
        if (time <= motionTime) return;
        long gap = time - motionTime;
        motionTime = time;
        if (anchor == null || time <= stateTime || !withinInertialBudget()) return;
        if (!Double.isFinite(ae) || !Double.isFinite(an) || gap > MAX_SENSOR_GAP_NS
                || time - stateTime > MAX_SENSOR_GAP_NS) {
            usableMotion = false;
            return;
        }
        if (!usableMotion) return;
        double dt = seconds(time - stateTime);
        ae = boundedAcceleration(ae);
        an = boundedAcceleration(an);
        east += (ve + 0.5 * ae * dt) * dt;
        north += (vn + 0.5 * an * dt) * dt;
        ve = clamp(ve + ae * dt, -100, 100);
        vn = clamp(vn + an * dt, -100, 100);
        // Position uncertainty grows with elapsed time since the last GNSS correction.
        double age = Math.max(0, seconds(time - lastFixTime));
        double previousAge = Math.max(0, seconds(stateTime - lastFixTime));
        variance += drift(age) - drift(previousAge);
        stateTime = time;
    }

    void invalidateMotion() { usableMotion = false; }

    GpsState gpsState(long now, long startedAt) {
        if (now - Math.max(startedAt, lastSampleTime) > GPS_TIMEOUT_NS) return GpsState.UNAVAILABLE;
        if (suspect) return GpsState.UNRELIABLE;
        return lastFix == null ? GpsState.WAITING : GpsState.GOOD;
    }

    boolean inFallbackDelay(long now) {
        return lastFix != null && now - lastFixTime <= GPS_TIMEOUT_NS;
    }

    Estimate fallback(long now) {
        if (lastFix == null || inFallbackDelay(now)) return null;
        Estimate result = estimate(now);
        return result != null && result.inertial ? result : null;
    }

    private boolean withinInertialBudget() {
        return variance <= MAX_INERTIAL_ACCURACY_METERS * MAX_INERTIAL_ACCURACY_METERS;
    }

    private boolean confirmCandidate(Fix fix) {
        if (candidate == null || fix.time - candidate.time > 2_000_000_000L
                || candidate.accuracy > 25 || fix.accuracy > 25
                || implausibleStep(candidate, fix)) {
            candidateSince = fix.time;
            candidateCount = 0;
        }
        candidate = fix;
        return ++candidateCount >= 3 && fix.time - candidateSince >= 2_000_000_000L;
    }

    private static boolean implausibleStep(Fix from, Fix to) {
        double dt = seconds(to.time - from.time);
        double speed = reliableSpeed(from) ? from.speed : 100;
        double reachable = Math.min(100 * dt, (speed + 3) * dt + 5 * dt * dt);
        double lat1 = Math.toRadians(from.latitude), lat2 = Math.toRadians(to.latitude);
        double halfLat = Math.sin((lat2 - lat1) / 2);
        double halfLon = Math.sin(Math.toRadians(wrap(to.longitude - from.longitude)) / 2);
        double a = halfLat * halfLat + Math.cos(lat1) * Math.cos(lat2) * halfLon * halfLon;
        double distance = 2 * EARTH_RADIUS * Math.asin(Math.sqrt(clamp(a, 0, 1)));
        return distance > reachable + Math.max(25, 3 * Math.hypot(from.accuracy, to.accuracy));
    }

    private static boolean reliableSpeed(Fix fix) {
        return fix.speed >= 0 && fix.speed <= 100 && fix.speedAccuracy >= 0 && fix.speedAccuracy <= 3;
    }

    Estimate estimate(long now) {
        if (anchor == null) return null;
        if (!withinInertialBudget()) return null;
        if (!motionReady(now)) {
            if (now - lastFixTime > 2_000_000_000L) return null;
            return new Estimate(lastFix.latitude, lastFix.longitude, lastFix.accuracy, lastFixTime, false);
        }
        boolean used = stateTime > lastFixTime;
        double lat = Math.cos(Math.toRadians(anchor.latitude));
        return new Estimate(clamp(anchor.latitude + Math.toDegrees(north / EARTH_RADIUS), -90, 90),
                wrap(anchor.longitude + (lat > 1e-6 ? Math.toDegrees(east / (EARTH_RADIUS * lat)) : 0)),
                Math.sqrt(variance), Math.max(lastFixTime, stateTime), used);
    }

    private boolean motionReady(long now) {
        return usableMotion && now - stateTime <= MAX_SENSOR_GAP_NS;
    }

    private void resetPosition(Fix fix) {
        anchor = fix;
        east = north = ve = vn = 0;
        variance = Math.pow(Math.max(3, fix.accuracy), 2);
        motionVariance = 9;
        stateTime = fix.time;
        usableMotion = Math.abs(fix.latitude) < 89.9 && motionTime != 0
                && Math.abs(fix.time - motionTime) <= MAX_SENSOR_GAP_NS;
    }

    private double drift(double age) { return motionVariance * age * age + 0.16 * Math.pow(age, 4); }
    private static double boundedAcceleration(double value) {
        return Math.abs(value) < 0.12 ? 0 : clamp(value, -8, 8);
    }
    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(value, high));
    }
    private static double seconds(long nanoseconds) { return nanoseconds / 1e9; }
    private static double wrap(double degrees) { return ((degrees + 180) % 360 + 360) % 360 - 180; }
}
