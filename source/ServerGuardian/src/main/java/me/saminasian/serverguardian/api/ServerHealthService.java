package me.saminasian.serverguardian.api;

public interface ServerHealthService {
    enum LoadState {
        NORMAL,
        PRESSURE,
        EMERGENCY
    }

    LoadState state();

    double tps1m();

    double mspt();

    double memoryPercent();

    /**
     * Recommended multiplier for cosmetic work in cooperating plugins.
     * NORMAL=1.0 by default, PRESSURE=0.55, EMERGENCY=0.25.
     */
    double cosmeticMultiplier();

    default boolean shouldReduceEffects() {
        return state() != LoadState.NORMAL;
    }
}
