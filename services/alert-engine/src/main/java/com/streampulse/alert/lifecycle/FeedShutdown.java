/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.lifecycle;

import com.streampulse.alert.application.AlertFeed;
import org.springframework.context.SmartLifecycle;

/**
 * Ends every open SSE stream at shutdown. A stream never ends by itself, so graceful shutdown of
 * the web server would otherwise wait out its whole timeout for connections that are idle by design.
 * The highest phase stops first, ahead of the web server.
 */
public final class FeedShutdown implements SmartLifecycle {

    private final AlertFeed feed;
    private volatile boolean running;

    public FeedShutdown(AlertFeed feed) {
        this.feed = feed;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        feed.closeAll();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
