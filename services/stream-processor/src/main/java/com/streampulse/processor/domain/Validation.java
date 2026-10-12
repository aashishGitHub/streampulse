/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

/** The outcome of validating one event. */
public sealed interface Validation permits Validation.Valid, Validation.Invalid {

    record Valid() implements Validation {}

    record Invalid(RejectionReason reason) implements Validation {}

    static Validation valid() {
        return new Valid();
    }

    static Validation invalid(RejectionReason reason) {
        return new Invalid(reason);
    }
}
