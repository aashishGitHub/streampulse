/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

/** Which timestamp the age was measured from: query-api's range query does not report a window's end. */
public enum AgeBasis {
    WINDOW_END,
    WINDOW_START
}
