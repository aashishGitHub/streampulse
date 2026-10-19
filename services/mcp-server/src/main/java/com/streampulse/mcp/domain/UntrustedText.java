/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

/**
 * Free text whose author is a tenant. It is made safe to carry, not safe to obey: control and invisible
 * characters are removed, line breaks collapsed and the length capped, so it stays one short visible line
 * inside the one field a client has been told not to trust.
 */
public record UntrustedText(String text, boolean truncated) {

    public static final String MARKER = "[truncated]";

    /** {@code maxChars} counts UTF-16 chars, so the bound on the carried text is exact. */
    public static UntrustedText of(String raw, int maxChars) {
        if (raw == null) {
            return new UntrustedText("", false);
        }
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), maxChars + 16));
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpaceLike(cp)) {
                pendingSpace = clean.length() > 0;
                continue;
            }
            if (isInvisible(cp)) {
                continue;
            }
            int needed = Character.charCount(cp) + (pendingSpace ? 1 : 0);
            if (clean.length() + needed > maxChars) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                clean.append(' ');
                pendingSpace = false;
            }
            clean.appendCodePoint(cp);
        }
        return new UntrustedText(truncated ? clean + MARKER : clean.toString(), truncated);
    }

    private static boolean isSpaceLike(int cp) {
        return cp == ' '
                || cp == '\t'
                || cp == '\n'
                || cp == '\r'
                || Character.getType(cp) == Character.LINE_SEPARATOR
                || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR
                || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED;
    }
}
