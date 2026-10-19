/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

/**
 * Writes an announcement.
 *
 * <p>Implementations MUST take part in the caller's transaction and refuse to run without one: an
 * announcement that can commit on its own can outlive a rolled-back change.
 */
public interface ConfigChangeOutbox {
    void append(ChangeEvent event);
}
