/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter.in;

/**
 * The function's input. Both fields are optional: absent means "everything".
 *
 * @param tenant a tenant id, to restrict the run to one tenant
 * @param date {@code yyyy-mm-dd}; requires a tenant
 */
public record CompactionRequest(String tenant, String date) {}
