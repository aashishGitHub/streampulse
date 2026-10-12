/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

/** One metric's value for a window, with how many events it was computed from. */
public record MetricValue(Metric metric, double value, long sampleCount) {}
