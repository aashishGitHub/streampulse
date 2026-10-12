/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts.codec;

/** Bytes that are not a valid record of the expected type. Callers route these to a DLT. */
public class AvroDecodeException extends RuntimeException {

    public AvroDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public AvroDecodeException(String message) {
        super(message);
    }
}
