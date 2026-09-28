/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.kata.graph;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Multi-source BFS on a grid: how many minutes until every fresh orange rots?
 *
 * <h2>The problem</h2>
 *
 * A grid where each cell is {@code 0} empty, {@code 1} fresh, {@code 2} rotten. Every
 * minute, a rotten orange rots each of its four orthogonal neighbours. Return the minutes
 * until no fresh orange remains, or {@code -1} if some fresh orange can never rot.
 *
 * <h2>Why this exercise matters beyond the interview</h2>
 *
 * This is <b>propagation through a graph in discrete time steps</b> — which is exactly the
 * shape of the alert root-cause suppression in StreamPulse's control plane (Phase 6). When
 * a CDN node fails, the failure propagates to packagers, then to players. Asking "which
 * alerts are downstream of this root cause?" is this algorithm with a dependency graph
 * instead of a grid. If you can explain that connection in the interview, a toy puzzle
 * becomes evidence of system thinking.
 *
 * <h2>The key insight: MULTI-SOURCE BFS</h2>
 *
 * The instinct is to BFS from each rotten orange separately and take the minimum. That is
 * O(sources × cells) and needlessly complicated.
 *
 * <p>Instead, seed the queue with <b>every</b> rotten orange at once, before the loop
 * starts. They are all at distance 0. BFS then expands them in lockstep, so the first time
 * any cell is reached is automatically via its nearest source. One pass, O(cells).
 *
 * <pre>
 *   minute 0            minute 1            minute 2
 *   ┌───┬───┬───┐       ┌───┬───┬───┐       ┌───┬───┬───┐
 *   │ 2 │ 1 │ 1 │       │ 2 │ 2 │ 1 │       │ 2 │ 2 │ 2 │
 *   ├───┼───┼───┤       ├───┼───┼───┤       ├───┼───┼───┤
 *   │ 1 │ 1 │ 0 │  ───▶ │ 2 │ 1 │ 0 │  ───▶ │ 2 │ 2 │ 0 │
 *   ├───┼───┼───┤       ├───┼───┼───┤       ├───┼───┼───┤
 *   │ 0 │ 1 │ 1 │       │ 0 │ 1 │ 1 │       │ 0 │ 1 │ 1 │
 *   └───┴───┴───┘       └───┴───┴───┘       └───┴───┴───┘
 *      fresh = 5           fresh = 3           fresh = 2
 *
 *   The bottom-right pair is walled off by the 0s → unreachable → return -1.
 * </pre>
 *
 * <h2>The level-by-level trick</h2>
 *
 * Plain BFS visits nodes; here we need to count <em>minutes</em>. The standard technique:
 * at the top of each loop iteration, snapshot {@code queue.size()} and drain exactly that
 * many nodes. Those are precisely the nodes at the current distance. Everything added
 * during that drain belongs to the next minute.
 *
 * <p>Miss this and you count nodes instead of levels — the single most common bug in this
 * problem.
 *
 * <h2>The other classic bug</h2>
 *
 * Incrementing minutes even when nothing rots. If the last level of BFS adds no new cells,
 * no minute passed. The {@code fresh > 0} guard below is what prevents an off-by-one that
 * makes every correct-looking solution return one too many.
 *
 * <h2>Complexity</h2>
 *
 * <pre>
 *   time  O(rows × cols)   every cell is enqueued at most once
 *   space O(rows × cols)   worst case the queue holds most of the grid
 * </pre>
 */
public final class RottingOranges {

    private static final int EMPTY = 0;
    private static final int FRESH = 1;
    private static final int ROTTEN = 2;

    /**
     * Four-directional neighbour offsets as {row, col}.
     *
     * <p>A direction array beats four copy-pasted blocks: it is impossible to typo one of
     * them differently, and switching to 8-directional movement is a one-line change.
     */
    private static final int[][] DIRECTIONS = {
        {-1, 0}, // up
        {1, 0}, // down
        {0, -1}, // left
        {0, 1} // right
    };

    private RottingOranges() {} // static-only utility

    /**
     * @param grid mutated in place as oranges rot (call a copy if you need the original)
     * @return minutes until no fresh orange remains, or -1 if that is impossible
     */
    public static int minutesUntilAllRot(int[][] grid) {
        if (grid == null || grid.length == 0 || grid[0].length == 0) {
            return 0; // nothing to rot — vacuously done
        }

        int rows = grid.length;
        int cols = grid[0].length;

        Deque<int[]> queue = new ArrayDeque<>();
        int fresh = 0;

        // ---- PASS 1: seed EVERY rotten orange, and count the fresh ones. ----
        // Seeding all sources before the loop is what makes this multi-source BFS.
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == ROTTEN) {
                    queue.add(new int[] {r, c});
                } else if (grid[r][c] == FRESH) {
                    fresh++;
                }
            }
        }

        // Early exit. Note this MUST come before the loop: with no fresh oranges the
        // answer is 0 minutes, even if the grid is full of rotten ones.
        if (fresh == 0) {
            return 0;
        }

        int minutes = 0;

        // ---- PASS 2: BFS, one level (= one minute) per outer iteration. ----
        //
        // The `fresh > 0` guard is load-bearing. Without it, the final iteration — which
        // finds no new fresh neighbours — would still increment `minutes`, and every
        // answer would be one too high.
        while (!queue.isEmpty() && fresh > 0) {
            int levelSize = queue.size(); // snapshot BEFORE draining: this is one minute

            for (int i = 0; i < levelSize; i++) {
                int[] cell = queue.poll();

                for (int[] dir : DIRECTIONS) {
                    int nr = cell[0] + dir[0];
                    int nc = cell[1] + dir[1];

                    // Bounds check first, then the value check. Reversing these order
                    // throws ArrayIndexOutOfBounds — Java has no short-circuit rescue
                    // if you index before you check.
                    if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) {
                        continue;
                    }
                    if (grid[nr][nc] != FRESH) {
                        continue; // empty, or already rotten (= already visited)
                    }

                    // Mark rotten IMMEDIATELY on enqueue, not on dequeue. The grid itself
                    // is our "visited" set — no separate boolean[][] needed. Marking late
                    // lets the same cell be enqueued by two different neighbours in the
                    // same minute, which corrupts the fresh count.
                    grid[nr][nc] = ROTTEN;
                    fresh--;
                    queue.add(new int[] {nr, nc});
                }
            }

            minutes++; // one full level drained = one minute elapsed
        }

        // If anything is still fresh, it was unreachable from any rotten orange.
        return fresh == 0 ? minutes : -1;
    }

    /**
     * Convenience overload that does not mutate the caller's grid.
     *
     * <p>Mutating an input argument is a design smell worth naming in an interview: it is
     * fine for a puzzle, dangerous in shared code. Offering both shows you noticed.
     */
    public static int minutesUntilAllRotCopying(int[][] grid) {
        if (grid == null) {
            return 0;
        }
        int[][] copy = new int[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i].clone(); // clone() per row: a 2-D array is an array of arrays
        }
        return minutesUntilAllRot(copy);
    }
}
