/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

/** The result of offering one observation to one rule, for metrics. */
public enum EvaluationOutcome {
    BREACHING,
    CLEAR,
    NO_DECISION,
    /** Same window start as the latest one already evaluated. */
    DUPLICATE,
    /** An older window than the latest one already evaluated. */
    STALE;

    static EvaluationOutcome of(Verdict verdict) {
        return switch (verdict) {
            case BREACHING -> BREACHING;
            case CLEAR -> CLEAR;
            case NO_DECISION -> NO_DECISION;
        };
    }
}
