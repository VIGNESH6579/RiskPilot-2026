package com.riskpilot.service;

/**
 * Strict state machine for feed lifecycle.
 * Prevents invalid transitions and reconnect storms.
 */
public enum FeedConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    SHUTDOWN;

    public boolean isRecovering() {
        return this == CONNECTING || this == RECONNECTING;
    }

    public boolean isActive() {
        return this == CONNECTED;
    }
}
