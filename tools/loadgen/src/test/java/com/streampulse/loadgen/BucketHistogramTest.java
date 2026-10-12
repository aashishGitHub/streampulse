/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class BucketHistogramTest {

    private static final String METRIC = "streampulse_sink_freshness_seconds_bucket";

    private static BucketHistogram histogram(double[][] boundsAndCumulative) {
        return new BucketHistogram(java.util.Arrays.stream(boundsAndCumulative)
                .map(b -> new BucketHistogram.Bucket(b[0], b[1]))
                .toList());
    }

    @Test
    void interpolatesLinearlyInsideTheBucketHoldingTheRank() {
        BucketHistogram h = histogram(new double[][] {{1, 0}, {2, 50}, {4, 100}, {Double.POSITIVE_INFINITY, 100}});

        assertThat(h.quantile(0.5).getAsDouble()).isCloseTo(2.0, within(1e-9));
        assertThat(h.quantile(0.75).getAsDouble()).isCloseTo(3.0, within(1e-9));
        assertThat(h.quantile(0.99).getAsDouble()).isCloseTo(3.96, within(1e-9));
    }

    @Test
    void firstBucketInterpolatesFromZero() {
        BucketHistogram h = histogram(new double[][] {{1, 10}, {Double.POSITIVE_INFINITY, 10}});

        assertThat(h.quantile(0.5).getAsDouble()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void rankInPlusInfBucketReturnsHighestFiniteBound() {
        BucketHistogram h = histogram(new double[][] {{1, 90}, {10, 95}, {Double.POSITIVE_INFINITY, 100}});

        assertThat(h.quantile(0.99)).hasValue(10.0);
    }

    @Test
    void emptyHistogramHasNoQuantile() {
        assertThat(new BucketHistogram(List.of()).quantile(0.99)).isEmpty();
        assertThat(histogram(new double[][] {{1, 0}, {Double.POSITIVE_INFINITY, 0}})
                        .quantile(0.99))
                .isEmpty();
    }

    @Test
    void quantileZeroIsTheLowerBoundOfTheFirstNonEmptyBucket() {
        BucketHistogram h = histogram(new double[][] {{1, 0}, {2, 10}, {Double.POSITIVE_INFINITY, 10}});

        assertThat(h.quantile(0).getAsDouble()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void parsesPrometheusTextAndSortsBuckets() {
        String text =
                """
                # HELP streampulse_sink_freshness_seconds help
                # TYPE streampulse_sink_freshness_seconds histogram
                streampulse_sink_freshness_seconds_bucket{le="+Inf"} 8
                streampulse_sink_freshness_seconds_bucket{le="2.0"} 6
                streampulse_sink_freshness_seconds_bucket{le="1.0"} 2
                streampulse_sink_freshness_seconds_count 8
                streampulse_sink_freshness_seconds_sum 9.5
                unrelated_metric_bucket{le="1.0"} 99
                """;

        BucketHistogram h = BucketHistogram.parse(text, METRIC);

        assertThat(h.buckets())
                .extracting(BucketHistogram.Bucket::upperBound)
                .containsExactly(1.0, 2.0, Double.POSITIVE_INFINITY);
        assertThat(h.total()).isEqualTo(8);
    }

    @Test
    void sumsSeriesThatDifferOnlyByOtherLabels() {
        String text =
                """
                streampulse_sink_freshness_seconds_bucket{application="sink-writer",le="1.0"} 3
                streampulse_sink_freshness_seconds_bucket{application="sink-writer",le="+Inf"} 4
                streampulse_sink_freshness_seconds_bucket{application="other",le="1.0"} 1
                streampulse_sink_freshness_seconds_bucket{application="other",le="+Inf"} 1
                """;

        BucketHistogram h = BucketHistogram.parse(text, METRIC);

        assertThat(h.buckets())
                .containsExactly(
                        new BucketHistogram.Bucket(1.0, 4), new BucketHistogram.Bucket(Double.POSITIVE_INFINITY, 5));
    }

    @Test
    void parseIgnoresNaNScientificNotationIsAccepted() {
        String text =
                """
                streampulse_sink_freshness_seconds_bucket{le="1.0"} NaN
                streampulse_sink_freshness_seconds_bucket{le="2.0"} 1.0E1
                streampulse_sink_freshness_seconds_bucket{le="+Inf"} 10 1700000000000
                """;

        BucketHistogram h = BucketHistogram.parse(text, METRIC);

        assertThat(h.buckets())
                .containsExactly(
                        new BucketHistogram.Bucket(2.0, 10), new BucketHistogram.Bucket(Double.POSITIVE_INFINITY, 10));
    }

    @Test
    void parseOfTextWithoutTheMetricIsEmpty() {
        assertThat(BucketHistogram.parse("some_other 1\n", METRIC).buckets()).isEmpty();
    }

    @Test
    void minusKeepsOnlyObservationsMadeBetweenTwoScrapes() {
        BucketHistogram before = histogram(new double[][] {{1, 5}, {2, 10}, {Double.POSITIVE_INFINITY, 10}});
        BucketHistogram after = histogram(new double[][] {{1, 5}, {2, 60}, {Double.POSITIVE_INFINITY, 110}});

        BucketHistogram delta = after.minus(before).orElseThrow();

        assertThat(delta.total()).isEqualTo(100);
        assertThat(delta.quantile(0.4).getAsDouble()).isCloseTo(1.8, within(1e-9));
    }

    @Test
    void minusIsEmptyWhenACounterWentBackwardsOrBoundsDiffer() {
        BucketHistogram before = histogram(new double[][] {{1, 5}, {Double.POSITIVE_INFINITY, 10}});
        BucketHistogram restarted = histogram(new double[][] {{1, 1}, {Double.POSITIVE_INFINITY, 2}});
        BucketHistogram otherBounds = histogram(new double[][] {{3, 5}, {Double.POSITIVE_INFINITY, 10}});

        assertThat(restarted.minus(before)).isEmpty();
        assertThat(otherBounds.minus(before)).isEmpty();
    }
}
