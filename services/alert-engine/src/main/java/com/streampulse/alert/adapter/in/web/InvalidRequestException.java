/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.web;

final class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidRequestException(String message) {
        super(message);
    }
}
