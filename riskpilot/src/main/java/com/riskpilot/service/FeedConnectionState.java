package com.riskpilot.service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strict state machine for feed lifecycle.
 * Prevents invalid transitions and reconnect storms.
 *
 * Valid transitions:
 *   DISCONNECTED -> CONNECTING
 *   CONNECTING   -> CONNECTED, DISCONNECTED, RECONNECTING, SHUTDOWN
 *   CONNECTED    -> RECONNECTING, DISCONNECTED, SHUTDOWN
 *   RECONNECTING -> CONNECTING, DISCONNECTED, SHUTDOWN
 *   SHUTDOWN     -> (terminal, no transitions out)
 */
public enum FeedConnectionState {

    DISCONNECTED {
        @Override
        public Set<FeedConnectionState> allowedTransitions() {
            return EnumSet.of(CONNECTING, SHUTDOWN);
        }
    },
    CONNECTING {
        @Override
        public Set<FeedConnectionState> allowedTransitions() {
            return EnumSet.of(CONNECTED, DISCONNECTED, RECONNECTING, SHUTDOWN);
        }
    },
    CONNECTED {
        @Override
        public Set<FeedConnectionState> allowedTransitions() {
            return EnumSet.of(RECONNECTING, DISCONNECTED, SHUTDOWN);
        }
    },
    RECONNECTING {
        @Override
        public Set<FeedConnectionState> allowedTransitions() {
            return EnumSet.of(CONNECTING, DISCONNECTED, SHUTDOWN);
        }
    },
    SHUTDOWN {
        @Override
        public Set<FeedConnectionState> allowedTransitions() {
            return EnumSet.noneOf(FeedConnectionState.class);
        }
    };

    /**
     * Returns the set of states this state can transition to.
     */
    public abstract Set<FeedConnectionState> allowedTransitions();

    /**
     * Checks whether transitioning from this state to the target is valid.
     */
    public boolean canTransitionTo(FeedConnectionState target) {
        return allowedTransitions().contains(target);
    }

    /**
     * Returns true if the feed is in a recovery phase (connecting or reconnecting).
     */
    public boolean isRecovering() {
        return this == CONNECTING || this == RECONNECTING;
    }

    /**
     * Returns true if the feed is actively connected and receiving data.
     */
    public boolean isActive() {
        return this == CONNECTED;
    }

    /**
     * Returns true if the feed is in a terminal shutdown state.
     */
    public boolean isTerminal() {
        return this == SHUTDOWN;
    }
}
