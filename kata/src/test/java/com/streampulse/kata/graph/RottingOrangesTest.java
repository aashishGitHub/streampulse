/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.kata.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RottingOranges}.
 *
 * <h2>How to read this file</h2>
 *
 * The grids are written out visually so you can verify the expected answer by eye. In an
 * interview you would talk through exactly these cases — especially the edge cases, because
 * naming them unprompted is most of what separates a strong candidate from a correct one.
 *
 * <p>The four that matter: no fresh oranges at all, no rotten oranges at all, an
 * unreachable fresh orange, and a single cell. Every off-by-one bug in this problem shows
 * up in one of them.
 */
class RottingOrangesTest {

    @Nested
    @DisplayName("the standard cases")
    class Standard {

        @Test
        @DisplayName("rot spreads outward from one source")
        void singleSourceSpreads() {
            // 2 1 1        minute 1: (0,1)
            // 1 1 0        minute 2: (0,2),(1,1)
            // 0 1 1        minute 3: (2,1) ... minute 4: (2,2)
            int[][] grid = {
                {2, 1, 1},
                {1, 1, 0},
                {0, 1, 1}
            };
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isEqualTo(4);
        }

        @Test
        @DisplayName("two sources meet in the middle — this is why multi-source BFS wins")
        void multipleSourcesExpandInLockstep() {
            // 2 1 1 1 2   both ends rot inward simultaneously; they meet at the centre.
            // A per-source BFS would compute two full traversals to learn the same thing.
            int[][] grid = {{2, 1, 1, 1, 2}};
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isEqualTo(2);
        }

        @Test
        @DisplayName("already fully rotten takes zero minutes")
        void allRottenIsZero() {
            int[][] grid = {
                {2, 2},
                {2, 2}
            };
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isZero();
        }
    }

    @Nested
    @DisplayName("edge cases — where the bugs live")
    class EdgeCases {

        @Test
        @DisplayName("an unreachable fresh orange returns -1")
        void unreachableFreshOrangeIsImpossible() {
            // 2 1 1
            // 0 1 1     the 0s wall off (2,0); nothing can ever reach it
            // 1 0 1
            int[][] grid = {
                {2, 1, 1},
                {0, 1, 1},
                {1, 0, 1}
            };
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isEqualTo(-1);
        }

        @Test
        @DisplayName("no fresh oranges at all is 0, not -1")
        void emptyGridOfZeroesIsZero() {
            // The early-exit path. Getting this wrong returns -1 for a grid that is
            // trivially already done.
            int[][] grid = {{0, 0, 0}};
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isZero();
        }

        @Test
        @DisplayName("fresh oranges with NO rotten source is -1")
        void noSourceMeansImpossible() {
            // The BFS queue starts empty, so the while loop never runs and fresh stays > 0.
            int[][] grid = {{1, 1, 1}};
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isEqualTo(-1);
        }

        @Test
        @DisplayName("a single fresh cell with no source is -1")
        void singleFreshCell() {
            assertThat(RottingOranges.minutesUntilAllRot(new int[][] {{1}})).isEqualTo(-1);
        }

        @Test
        @DisplayName("a single rotten cell is 0")
        void singleRottenCell() {
            assertThat(RottingOranges.minutesUntilAllRot(new int[][] {{2}})).isZero();
        }

        @Test
        @DisplayName("null and empty grids do not blow up")
        void degenerateInputs() {
            assertThat(RottingOranges.minutesUntilAllRot(null)).isZero();
            assertThat(RottingOranges.minutesUntilAllRot(new int[][] {})).isZero();
            assertThat(RottingOranges.minutesUntilAllRot(new int[][] {{}})).isZero();
        }
    }

    @Nested
    @DisplayName("the off-by-one trap")
    class OffByOne {

        @Test
        @DisplayName("adjacent fresh orange takes exactly 1 minute, not 2")
        void adjacentTakesOneMinute() {
            // This is THE test that catches a missing `fresh > 0` guard. Without it the
            // loop runs one extra time — finding nothing — and returns 2.
            int[][] grid = {{2, 1}};
            assertThat(RottingOranges.minutesUntilAllRot(grid)).isEqualTo(1);
        }

        @Test
        @DisplayName("a straight line of n fresh takes exactly n minutes")
        void lineOfFreshTakesLengthMinutes() {
            for (int n = 1; n <= 20; n++) {
                int[] row = new int[n + 1];
                row[0] = 2; // source at the left
                for (int i = 1; i <= n; i++) {
                    row[i] = 1;
                }
                assertThat(RottingOranges.minutesUntilAllRot(new int[][] {row}))
                        .as("a line of %d fresh oranges", n)
                        .isEqualTo(n);
            }
        }
    }

    @Nested
    @DisplayName("mutation behaviour")
    class Mutation {

        @Test
        @DisplayName("the in-place version mutates its input")
        void inPlaceVersionMutates() {
            int[][] grid = {{2, 1}};
            RottingOranges.minutesUntilAllRot(grid);
            assertThat(grid[0][1]).isEqualTo(2); // the fresh orange is now rotten
        }

        @Test
        @DisplayName("the copying version leaves the caller's grid untouched")
        void copyingVersionDoesNotMutate() {
            int[][] grid = {{2, 1}};
            int result = RottingOranges.minutesUntilAllRotCopying(grid);

            assertThat(result).isEqualTo(1);
            assertThat(grid[0][1]).isEqualTo(1); // still fresh — the original is intact
        }
    }
}
