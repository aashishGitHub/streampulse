/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

public class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String what, String id) {
        super(what + " '" + id + "' does not exist");
    }
}
