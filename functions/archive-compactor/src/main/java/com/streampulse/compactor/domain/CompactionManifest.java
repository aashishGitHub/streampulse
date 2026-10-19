/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commit record of one compaction: written only after the combined object has been verified,
 * so its existence means "these sources are safely folded in".
 */
public record CompactionManifest(long records, List<String> sources) {

    private static final Pattern HEADER = Pattern.compile("records=(\\d+)");

    public CompactionManifest {
        sources = List.copyOf(sources);
    }

    public String render() {
        return "records=" + records + "\n" + String.join("\n", sources) + "\n";
    }

    public static CompactionManifest parse(String text) {
        String[] lines = text.split("\n", -1);
        Matcher header = HEADER.matcher(lines[0]);
        if (!header.matches()) {
            throw new IllegalArgumentException("manifest does not start with 'records=<n>'");
        }
        List<String> sources =
                Arrays.stream(lines).skip(1).filter(l -> !l.isBlank()).toList();
        return new CompactionManifest(Long.parseLong(header.group(1)), sources);
    }
}
