/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

public class AlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AlreadyExistsException(String what, String id) {
        super(what + " '" + id + "' already exists");
    }
}
