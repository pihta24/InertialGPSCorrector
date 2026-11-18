package dev.inertialgps.corrector;

enum PublishMode {
    OFF, OUTAGES, ANYTIME;

    static PublishMode fromName(String name) {
        if ("OUTAGES".equals(name)) return OUTAGES;
        if ("ANYTIME".equals(name)) return ANYTIME;
        return OFF;
    }

    Fusion.Estimate estimate(Fusion fusion, long now) {
        return switch (this) {
            case OFF -> null;
            case OUTAGES -> fusion.fallback(now);
            case ANYTIME -> fusion.estimate(now);
        };
    }
}
