package dev.inertialgps.corrector;

import org.junit.Test;

import static org.junit.Assert.*;

public final class FusionTest {
    private static final long T = 10_000_000_000L;
    private static final long STEP = 100_000_000L;

    @Test public void needsFreshGpsAnchor() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        assertNull(fusion.estimate(T));
        assertFalse(fusion.accept(fix(T, 0, 5, 0, 0), T + 3_000_000_000L));
        assertFalse(fusion.accept(fix(T, 0, 90, 0, 0), T));
        assertNull(fusion.estimate(T));
    }

    @Test public void advancesEastFromGpsVelocityAndMeasuredAcceleration() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        assertTrue(fusion.accept(fix(T, 0, 5, 2, 90), T));
        for (int i = 1; i <= 20; i++) fusion.motion(T + i * STEP, 1, 0);
        Fusion.Estimate estimate = fusion.estimate(T + 20 * STEP);
        assertTrue(estimate.inertial());
        assertEquals(6, estimate.longitude() * 111195, 0.01);
        assertEquals(0, estimate.latitude(), 0.000001);
        assertTrue(estimate.accuracy() > 5);
    }

    @Test public void rejectsJumpEvenWhenGpsClaimsHighAccuracy() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        assertTrue(fusion.accept(fix(T, 0, 5, 0, 0), T));
        for (int i = 1; i <= 90; i++) fusion.motion(T + i * STEP, 0, 0);
        long now = T + 90 * STEP;
        assertFalse(fusion.accept(fix(now, 0.02, 4, 0, 0), now));
        assertEquals(Fusion.GpsState.UNRELIABLE, fusion.gpsState(now, T));
        assertEquals(0, fusion.fallback(now).longitude(), 0.000001);
        now += STEP;
        fusion.motion(now, 0, 0);
        assertTrue(fusion.accept(fix(now, 0, 5, 0, 0), now));
        assertEquals(Fusion.GpsState.GOOD, fusion.gpsState(now, T));
        assertNull(fusion.fallback(now));
    }

    @Test public void waitsEightSecondsThenFallsBackUntilUncertaintyGrowsTooLarge() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        fusion.accept(fix(T, 0, 5, 2, 90), T);
        for (int i = 1; i <= 80; i++) fusion.motion(T + i * STEP, 0, 0);
        assertNull(fusion.fallback(T + 80 * STEP));
        fusion.motion(T + 81 * STEP, 0, 0);
        assertEquals(Fusion.GpsState.UNAVAILABLE, fusion.gpsState(T + 81 * STEP, T));
        assertNotNull(fusion.fallback(T + 81 * STEP));
        for (int i = 82; i <= 210; i++) fusion.motion(T + i * STEP, 0, 0);
        long afterTwentySeconds = T + 210 * STEP;
        Fusion.Estimate estimate = fusion.fallback(afterTwentySeconds);
        assertNotNull(estimate);
        assertEquals(afterTwentySeconds, estimate.time());
        for (int i = 211; i <= 230; i++) fusion.motion(T + i * STEP, 0, 0);
        assertNull(fusion.fallback(T + 230 * STEP));
    }

    @Test public void requiresConsistentSamplesToRecoverFromLargeJumpWithoutMotion() {
        Fusion fusion = new Fusion();
        assertTrue(fusion.accept(fix(T, 0, 5, 0, 0), T));
        long second = 1_000_000_000L;
        assertFalse(fusion.accept(fix(T + second, 0.02, 4, 0, 0), T + second));
        assertFalse(fusion.accept(fix(T + 2 * second, 0.02, 4, 0, 0), T + 2 * second));
        assertTrue(fusion.accept(fix(T + 3 * second, 0.02, 4, 0, 0), T + 3 * second));
        assertNull(fusion.fallback(T + 3 * second));
    }

    @Test public void distinguishesPoorGpsFromMissingGpsWithoutAnAnchor() {
        Fusion fusion = new Fusion();
        assertEquals(Fusion.GpsState.WAITING, fusion.gpsState(T, T));
        assertFalse(fusion.accept(fix(T, 0, 100, 0, 0), T));
        assertEquals(Fusion.GpsState.UNRELIABLE, fusion.gpsState(T, T));
        long later = T + 9_000_000_000L;
        assertEquals(Fusion.GpsState.UNAVAILABLE, fusion.gpsState(later, T));
        assertNull(fusion.fallback(later));
    }

    @Test public void doesNotResumeInertialTrackingAfterSensorGapWithoutNewFix() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        fusion.accept(fix(T, 0, 5, 2, 90), T);
        for (int i = 10; i <= 25; i++) fusion.motion(T + i * STEP, 1, 0);
        assertNull(fusion.estimate(T + 25 * STEP));
        fusion.accept(fix(T + 25 * STEP, 0, 5, 2, 90), T + 25 * STEP);
        fusion.motion(T + 26 * STEP, 0, 0);
        assertTrue(fusion.estimate(T + 26 * STEP).inertial());
    }

    @Test public void anytimePublishesFreshGpsButNotMissingOrStaleEstimates() {
        Fusion fusion = new Fusion();
        assertNull(PublishMode.ANYTIME.estimate(fusion, T));
        assertTrue(fusion.accept(fix(T, 0, 5, 0, 0), T));
        assertNotNull(PublishMode.ANYTIME.estimate(fusion, T));
        assertNull(PublishMode.OUTAGES.estimate(fusion, T));
        assertNull(PublishMode.OFF.estimate(fusion, T));
        assertNull(PublishMode.ANYTIME.estimate(fusion, T + 3_000_000_000L));
    }

    @Test public void bothPublishingModesCanUseInertialFallbackButOffCannot() {
        Fusion fusion = new Fusion();
        fusion.motion(T, 0, 0);
        fusion.accept(fix(T, 0, 5, 2, 90), T);
        for (int i = 1; i <= 81; i++) fusion.motion(T + i * STEP, 0, 0);
        long now = T + 81 * STEP;
        assertTrue(PublishMode.ANYTIME.estimate(fusion, now).inertial());
        assertEquals(PublishMode.ANYTIME.estimate(fusion, now), PublishMode.OUTAGES.estimate(fusion, now));
        assertNull(PublishMode.OFF.estimate(fusion, now));
    }

    @Test public void manualPinStartsMotionWithoutCountingAsGps() {
        Fusion fusion = new Fusion();
        fusion.seedLocation(0, 12, T);
        assertTrue(PublishMode.ANYTIME.estimate(fusion, T).manual());
        assertEquals(Fusion.GpsState.WAITING, fusion.gpsState(T, T));
        for (int i = 1; i <= 80; i++) fusion.motion(T + i * STEP, 1, 0);
        assertNull(PublishMode.OUTAGES.estimate(fusion, T + 80 * STEP));
        long now = T + 81 * STEP;
        fusion.motion(now, 1, 0);
        Fusion.Estimate fallback = PublishMode.OUTAGES.estimate(fusion, now);
        assertTrue(fallback.manual());
        assertTrue(fallback.inertial());
        assertTrue(fallback.longitude() > 12);
        assertEquals(Fusion.GpsState.UNAVAILABLE, fusion.gpsState(now, T));
        assertTrue(fusion.accept(fix(now, 0, 5, 0, 0), now));
        assertFalse(fusion.estimate(now).manual());
        assertEquals(0, fusion.estimate(now).longitude(), 0.000001);
    }

    @Test public void manualPinDoesNotProduceStaleFallbackWithoutMotion() {
        Fusion fusion = new Fusion();
        fusion.seedLocation(0, 12, T);
        assertNull(PublishMode.ANYTIME.estimate(fusion, T + 3_000_000_000L));
        assertNull(PublishMode.OUTAGES.estimate(fusion, T + 9_000_000_000L));
    }

    private static Fusion.Fix fix(long time, double lon, double accuracy, double speed, double bearing) {
        return new Fusion.Fix(0, lon, accuracy, time, speed, bearing, 1);
    }
}
