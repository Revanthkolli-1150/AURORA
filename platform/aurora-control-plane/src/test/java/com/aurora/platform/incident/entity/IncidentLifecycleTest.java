package com.aurora.platform.incident.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentLifecycleTest {

    @Test
    @DisplayName("Legal Lifecycle Transitions: DETECTED -> INVESTIGATING -> DIAGNOSED -> RECOVERING -> VERIFYING -> RESOLVED")
    void testLegalHappyPathTransitions() {
        IncidentStatus s1 = IncidentStatus.DETECTED;
        assertThat(s1.canTransitionTo(IncidentStatus.INVESTIGATING)).isTrue();
        assertThatCode(() -> s1.validateTransitionTo(IncidentStatus.INVESTIGATING)).doesNotThrowAnyException();

        IncidentStatus s2 = IncidentStatus.INVESTIGATING;
        assertThat(s2.canTransitionTo(IncidentStatus.DIAGNOSED)).isTrue();
        assertThatCode(() -> s2.validateTransitionTo(IncidentStatus.DIAGNOSED)).doesNotThrowAnyException();

        IncidentStatus s3 = IncidentStatus.DIAGNOSED;
        assertThat(s3.canTransitionTo(IncidentStatus.RECOVERING)).isTrue();
        assertThatCode(() -> s3.validateTransitionTo(IncidentStatus.RECOVERING)).doesNotThrowAnyException();

        IncidentStatus s4 = IncidentStatus.RECOVERING;
        assertThat(s4.canTransitionTo(IncidentStatus.VERIFYING)).isTrue();
        assertThatCode(() -> s4.validateTransitionTo(IncidentStatus.VERIFYING)).doesNotThrowAnyException();

        IncidentStatus s5 = IncidentStatus.VERIFYING;
        assertThat(s5.canTransitionTo(IncidentStatus.RESOLVED)).isTrue();
        assertThatCode(() -> s5.validateTransitionTo(IncidentStatus.RESOLVED)).doesNotThrowAnyException();

        assertThat(IncidentStatus.RESOLVED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Legal Transitions: Any active state can transition to FAILED")
    void testTransitionsToFailed() {
        for (IncidentStatus active : IncidentStatus.values()) {
            if (!active.isTerminal()) {
                assertThat(active.canTransitionTo(IncidentStatus.FAILED))
                        .as("Active status %s must be permitted to transition to FAILED", active)
                        .isTrue();
                assertThatCode(() -> active.validateTransitionTo(IncidentStatus.FAILED))
                        .doesNotThrowAnyException();
            }
        }
        assertThat(IncidentStatus.FAILED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    @DisplayName("Idempotent Transitions: Transitioning from status S to status S is permitted as a no-op")
    void testIdempotentSelfTransitions(IncidentStatus status) {
        assertThat(status.canTransitionTo(status)).isTrue();
        assertThatCode(() -> status.validateTransitionTo(status)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Illegal Transitions: DETECTED cannot skip intermediate phases directly to RESOLVED or RECOVERING")
    void testDetectedCannotSkipPhases() {
        assertThat(IncidentStatus.DETECTED.canTransitionTo(IncidentStatus.RESOLVED)).isFalse();
        assertThatThrownBy(() -> IncidentStatus.DETECTED.validateTransitionTo(IncidentStatus.RESOLVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal incident lifecycle transition");

        assertThat(IncidentStatus.DETECTED.canTransitionTo(IncidentStatus.RECOVERING)).isFalse();
        assertThatThrownBy(() -> IncidentStatus.DETECTED.validateTransitionTo(IncidentStatus.RECOVERING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Illegal Transitions: Terminal state RESOLVED cannot transition to any other status")
    void testTerminalResolvedCannotTransition() {
        for (IncidentStatus target : IncidentStatus.values()) {
            if (target != IncidentStatus.RESOLVED) {
                assertThat(IncidentStatus.RESOLVED.canTransitionTo(target)).isFalse();
                assertThatThrownBy(() -> IncidentStatus.RESOLVED.validateTransitionTo(target))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    @DisplayName("Illegal Transitions: Terminal state FAILED cannot transition to any other status")
    void testTerminalFailedCannotTransition() {
        for (IncidentStatus target : IncidentStatus.values()) {
            if (target != IncidentStatus.FAILED) {
                assertThat(IncidentStatus.FAILED.canTransitionTo(target)).isFalse();
                assertThatThrownBy(() -> IncidentStatus.FAILED.validateTransitionTo(target))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    @DisplayName("Illegal Transitions: Transitioning to null status is invalid")
    void testTransitionToNullThrows() {
        assertThat(IncidentStatus.DETECTED.canTransitionTo(null)).isFalse();
        assertThatThrownBy(() -> IncidentStatus.DETECTED.validateTransitionTo(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1} (Expected legal: {2})")
    @org.junit.jupiter.params.provider.MethodSource("provideAll49StatusPairs")
    @DisplayName("Complete 49-Pair Transition Matrix: Validates all legal and illegal combinations")
    void testComplete49StatusMatrix(IncidentStatus from, IncidentStatus to, boolean expectedLegal) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expectedLegal);

        if (expectedLegal) {
            assertThatCode(() -> from.validateTransitionTo(to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> from.validateTransitionTo(to))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Illegal incident lifecycle transition");
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> provideAll49StatusPairs() {
        List<org.junit.jupiter.params.provider.Arguments> pairs = new java.util.ArrayList<>();
        for (IncidentStatus from : IncidentStatus.values()) {
            for (IncidentStatus to : IncidentStatus.values()) {
                boolean legal;
                if (from == to) {
                    legal = true; // idempotent
                } else if (from == IncidentStatus.DETECTED) {
                    legal = (to == IncidentStatus.INVESTIGATING || to == IncidentStatus.FAILED);
                } else if (from == IncidentStatus.INVESTIGATING) {
                    legal = (to == IncidentStatus.DIAGNOSED || to == IncidentStatus.FAILED);
                } else if (from == IncidentStatus.DIAGNOSED) {
                    legal = (to == IncidentStatus.RECOVERING || to == IncidentStatus.FAILED);
                } else if (from == IncidentStatus.RECOVERING) {
                    legal = (to == IncidentStatus.VERIFYING || to == IncidentStatus.FAILED);
                } else if (from == IncidentStatus.VERIFYING) {
                    legal = (to == IncidentStatus.RESOLVED || to == IncidentStatus.FAILED);
                } else { // RESOLVED or FAILED
                    legal = false;
                }
                pairs.add(org.junit.jupiter.params.provider.Arguments.of(from, to, legal));
            }
        }
        return pairs.stream();
    }
}
