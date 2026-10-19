/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.util.Optional;

/**
 * The per-alert transition table, as a pure function. Time, ids and the root-cause search are the
 * caller's: it hands in the current alert (if any) and the nearest firing upstream alert (if any).
 *
 * <pre>
 *   current      BREACHING                          CLEAR                NO_DECISION
 *   none         root ? SUPPRESSED : FIRING         nothing              nothing
 *   FIRING       nothing (dedupe)                   resolve              nothing
 *   SUPPRESSED   nothing                            resolve              nothing
 * </pre>
 */
public final class AlertStateMachine {

    private AlertStateMachine() {}

    public sealed interface Transition {

        None NONE = new None();

        record None() implements Transition {}

        /** Open a new incident, firing, or suppressed when {@code rootCause} is present. */
        record Open(Optional<Alert> rootCause) implements Transition {}

        record Resolve() implements Transition {}
    }

    public static Transition next(Optional<Alert> active, Verdict verdict, Optional<Alert> rootCause) {
        return switch (verdict) {
            case NO_DECISION -> Transition.NONE;
            case BREACHING -> active.isPresent() ? Transition.NONE : new Transition.Open(rootCause);
            case CLEAR -> active.isPresent() ? new Transition.Resolve() : Transition.NONE;
        };
    }
}
