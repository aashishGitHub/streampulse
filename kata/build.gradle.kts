// ============================================================================
//  kata — algorithm practice with real tests.
//
//  Two jobs:
//    1. Coding-interview prep (LRU cache, BFS/DFS) that is RUN, not just read.
//    2. A safe place to learn Java 21 idioms with zero framework noise.
//
//  Plain Java on purpose: no Spring, no Kafka. If something fails here, it is
//  your code — there is nothing else in the room.
// ============================================================================

plugins {
    id("streampulse.java-conventions")   // ← the entire build config, one line
}

// No dependencies block at all: the conventions plugin already supplied JUnit,
// AssertJ, Mockito and ArchUnit. That is the payoff.
