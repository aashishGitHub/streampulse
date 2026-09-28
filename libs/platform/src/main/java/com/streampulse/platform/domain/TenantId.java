/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform.domain;

import java.util.regex.Pattern;

/**
 * The identity of a tenant — {@code streamflix}, {@code sportsnow}, {@code kidsplus}.
 *
 * <h2>Why a type instead of a {@code String}</h2>
 *
 * This is the single highest-value habit to bring into a multi-tenant system. Consider:
 *
 * <pre>{@code
 *   // Both are String. The compiler is perfectly happy. The bug ships.
 *   void publish(String tenantId, String titleId) { ... }
 *   publish(titleId, tenantId);   // ← arguments swapped, silently
 * }</pre>
 *
 * With distinct types that call does not compile. In a system where the tenant id decides
 * <em>data isolation</em>, a swapped argument is not a bug — it is a cross-tenant data leak.
 * The type is the cheapest possible control against the worst possible outcome.
 *
 * <p>Validating once at the boundary also means every layer downstream can assume the value
 * is well-formed. No defensive re-checking, no "is this already lowercased?" uncertainty.
 *
 * <h2>Why a {@code record}</h2>
 *
 * Java 16+ records give you, for free and correctly: a canonical constructor, an accessor
 * per component, {@code equals}, {@code hashCode}, and {@code toString}. Records are also
 * <b>implicitly final and shallowly immutable</b> — the fields cannot be reassigned.
 *
 * <p><b>.NET note:</b> this is close to a C# {@code record}, with two differences worth
 * knowing. Java's accessor is {@code tenantId.value()}, not {@code tenantId.Value} — there
 * are no properties in Java. And Java records have no {@code with} expression; you construct
 * a new instance explicitly.
 *
 * <h2>The compact constructor</h2>
 *
 * The {@code TenantId { ... }} form below (no parameter list) is a <em>compact
 * constructor</em>. It runs before the fields are assigned, so it is the natural place for
 * validation and normalisation. Reassigning the parameter — as we do with
 * {@code value = value.trim()} — changes what gets stored.
 *
 * @param value the canonical tenant identifier: lowercase, already validated
 */
public record TenantId(String value) {

    /**
     * Lowercase letters, digits and single hyphens; must start and end alphanumeric.
     *
     * <p>This is deliberately narrow. The value ends up in Kafka record keys, Redis key
     * prefixes, DynamoDB partition keys, S3 object prefixes and Prometheus label values.
     * A tenant id containing {@code /}, a space or a {@code #} would break at least one of
     * those, and it would break it in production, months later, for one customer.
     */
    private static final Pattern VALID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

    private static final int MAX_LENGTH = 32;

    /**
     * Compact constructor: validates and normalises before the field is assigned.
     *
     * @throws IllegalArgumentException if the value is null, blank, too long, or malformed
     */
    public TenantId {
        if (value == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        // Normalise BEFORE validating, so " StreamFlix " is accepted and stored as
        // "streamflix". Normalising after validation would reject values we intend to allow.
        value = value.trim().toLowerCase(java.util.Locale.ROOT);

        // Locale.ROOT matters. With a Turkish default locale, "I".toLowerCase() produces a
        // dotless "ı", so the same input yields different ids on different machines. This is
        // a genuine, well-known JDK footgun — always pass an explicit Locale.
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "tenantId must be at most " + MAX_LENGTH + " characters, was " + value.length());
        }
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "tenantId must be lowercase alphanumeric with " + "internal hyphens, was: '" + value + "'");
        }
    }

    /**
     * Static factory. Reads better at call sites than {@code new TenantId(s)} and gives us
     * room to add caching or interning later without changing callers.
     *
     * <p>A static factory is Effective Java Item 1, and it is the idiom Java reviewers
     * expect for value types.
     */
    public static TenantId of(String value) {
        return new TenantId(value);
    }

    /**
     * Key prefix for Redis, DynamoDB and S3.
     *
     * <p>Putting this here — rather than string-concatenating at each call site — means the
     * layout is defined once. When Phase 4 changes the DynamoDB key scheme, exactly one
     * method changes.
     */
    public String keyPrefix() {
        return "tenant#" + value;
    }

    @Override
    public String toString() {
        // Records generate `TenantId[value=streamflix]`, which is noisy in logs and in
        // Kafka keys. Overriding toString is allowed and is usually right for a wrapper
        // over a single value.
        return value;
    }
}
