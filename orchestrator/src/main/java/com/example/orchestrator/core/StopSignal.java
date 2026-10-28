package com.example.orchestrator.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A cooperative stop flag. The engine stops it on safe-stop or stage timeout;
 * agents check {@link #isStopped()} and register listeners to kill child processes.
 */
public final class StopSignal {

    private volatile boolean stopped;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isStopped() {
        return stopped;
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        listeners.forEach(Runnable::run);
    }

    public void onStop(Runnable listener) {
        listeners.add(listener);
        if (stopped) listener.run();
    }

    /** A signal that stops when this one does, but can also be stopped on its own. */
    public StopSignal child() {
        StopSignal child = new StopSignal();
        onStop(child::stop);
        return child;
    }

    public void throwIfStopped() {
        if (stopped) throw new OrchestratorException.Aborted();
    }
}
