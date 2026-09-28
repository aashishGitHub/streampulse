/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform;

/**
 * Placeholder so {@code libs:platform} compiles in Phase 0.
 *
 * <p>From Phase 2 this module becomes a real Spring Boot <b>starter</b>: auto-configuration
 * for tracing, the RFC 7807 error model, and the Redis-backed idempotency store that
 * {@code ingest-gateway} depends on.
 *
 * <p>Writing a starter is deliberate. "How does Spring Boot auto-configuration actually
 * work?" is a standard senior question, and the difference between reciting
 * {@code @ConditionalOnMissingBean} and having shipped a starter that other modules consume
 * is obvious in about ten seconds of follow-up.
 *
 * @see <a href="file:../../../../../../interview-qa/01-spring-core.md">interview-qa Q4</a>
 */
public final class HexagonalMarker {

    private HexagonalMarker() {}

    /** Package this module governs with its architecture rules. */
    public static final String ROOT_PACKAGE = "com.streampulse";
}
