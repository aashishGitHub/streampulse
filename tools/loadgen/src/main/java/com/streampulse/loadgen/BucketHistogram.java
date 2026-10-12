/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cumulative Prometheus histogram (each bucket counts observations {@code <= upperBound}).
 *
 * <p>The quantile is an ESTIMATE: it assumes observations are spread evenly inside the bucket
 * that holds the rank, which is what PromQL's {@code histogram_quantile} assumes too. The
 * error is therefore bounded by the bucket width, not by sample count.
 */
public record BucketHistogram(List<Bucket> buckets) {

    public record Bucket(double upperBound, double cumulativeCount) {}

    private static final Pattern LE = Pattern.compile("le=\"([^\"]+)\"");

    public BucketHistogram {
        buckets = List.copyOf(buckets);
    }

    public double total() {
        return buckets.isEmpty() ? 0 : buckets.get(buckets.size() - 1).cumulativeCount();
    }

    public OptionalDouble quantile(double q) {
        double total = total();
        if (total <= 0) {
            return OptionalDouble.empty();
        }
        double rank = q * total;
        double lowerBound = 0;
        double lowerCount = 0;
        for (Bucket bucket : buckets) {
            if (bucket.cumulativeCount() >= rank && bucket.cumulativeCount() > lowerCount) {
                if (Double.isInfinite(bucket.upperBound())) {
                    return OptionalDouble.of(lowerBound);
                }
                double inBucket = bucket.cumulativeCount() - lowerCount;
                double fraction = (rank - lowerCount) / inBucket;
                return OptionalDouble.of(lowerBound + (bucket.upperBound() - lowerBound) * fraction);
            }
            lowerBound = Double.isInfinite(bucket.upperBound()) ? lowerBound : bucket.upperBound();
            lowerCount = bucket.cumulativeCount();
        }
        return OptionalDouble.empty();
    }

    /**
     * The observations made between two scrapes. Empty when the bucket layout differs or a
     * count went backwards, which means the process restarted in between.
     */
    public Optional<BucketHistogram> minus(BucketHistogram earlier) {
        if (buckets.size() != earlier.buckets.size()) {
            return Optional.empty();
        }
        List<Bucket> delta = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            Bucket now = buckets.get(i);
            Bucket then = earlier.buckets.get(i);
            if (Double.compare(now.upperBound(), then.upperBound()) != 0
                    || now.cumulativeCount() < then.cumulativeCount()) {
                return Optional.empty();
            }
            delta.add(new Bucket(now.upperBound(), now.cumulativeCount() - then.cumulativeCount()));
        }
        return Optional.of(new BucketHistogram(delta));
    }

    /** Sums every series of {@code bucketMetricName}, so extra labels (application, instance) fold together. */
    public static BucketHistogram parse(String prometheusText, String bucketMetricName) {
        TreeMap<Double, Double> byBound = new TreeMap<>(Comparator.naturalOrder());
        for (String line : prometheusText.split("\n")) {
            if (!line.startsWith(bucketMetricName) || line.startsWith("#")) {
                continue;
            }
            int labelsStart = line.indexOf('{');
            int labelsEnd = line.lastIndexOf('}');
            if (labelsStart != bucketMetricName.length() || labelsEnd < 0) {
                continue;
            }
            Matcher le = LE.matcher(line.substring(labelsStart, labelsEnd));
            if (!le.find()) {
                continue;
            }
            String[] valueAndTimestamp = line.substring(labelsEnd + 1).trim().split("\\s+");
            Double bound = number(le.group(1));
            Double count = number(valueAndTimestamp[0]);
            if (bound == null || count == null || count.isNaN()) {
                continue;
            }
            byBound.merge(bound, count, Double::sum);
        }
        List<Bucket> buckets = new ArrayList<>();
        byBound.forEach((bound, count) -> buckets.add(new Bucket(bound, count)));
        return new BucketHistogram(buckets);
    }

    private static Double number(String raw) {
        if (raw.equals("+Inf") || raw.equals("Inf")) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
