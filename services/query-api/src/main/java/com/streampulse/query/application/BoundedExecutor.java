/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The capacity reserved for blocking calls. Fixed size, bounded queue, and it REJECTS when full:
 * a caller that would otherwise wait for capacity degrades instead (REQ-QA-05, REQ-QA-07).
 */
public final class BoundedExecutor {

    private BoundedExecutor() {}

    public static ThreadPoolExecutor create(String name, int size, int queueCapacity) {
        if (size < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("size and queueCapacity must be at least 1");
        }
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                size,
                size,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threads,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
