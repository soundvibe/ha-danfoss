package net.soundvibe.hasio.danfoss.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Runs several attempts at the same result in parallel and returns the first one that succeeds.
 * <p>
 * The caller owns the attempts: when this method returns or throws, the remaining attempts have been
 * interrupted, the cancel action has run (it should close the attempts' sockets, because interruption
 * alone does not unblock socket I/O) and the worker threads have been given a moment to finish.
 */
final class FirstSuccess {

    private static final Logger logger = LoggerFactory.getLogger(FirstSuccess.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /** Thrown when no attempt succeeded; carries the error of every attempt, in the order given. */
    static final class AllFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient List<Throwable> errors;

        AllFailedException(List<Throwable> errors) {
            super("All attempts failed", errors.isEmpty() ? null : errors.get(0));
            this.errors = List.copyOf(errors);
        }

        List<Throwable> errors() {
            return errors;
        }
    }

    private FirstSuccess() {
    }

    static <T> T await(List<Callable<T>> attempts, long timeout, TimeUnit unit, Runnable cancel)
            throws AllFailedException, TimeoutException, InterruptedException {
        var errors = new AtomicReferenceArray<Throwable>(attempts.size());
        var tasks = new ArrayList<Callable<T>>();
        for (int i = 0; i < attempts.size(); i++) {
            int index = i;
            var attempt = attempts.get(i);
            tasks.add(() -> {
                try {
                    return attempt.call();
                } catch (Throwable error) {
                    errors.set(index, error);
                    throw error;
                }
            });
        }
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return workers.invokeAny(tasks, timeout, unit);
        } catch (ExecutionException e) {
            var all = new ArrayList<Throwable>();
            for (int i = 0; i < errors.length(); i++) {
                all.add(errors.get(i) != null ? errors.get(i) : e.getCause());
            }
            throw new AllFailedException(all);
        } finally {
            workers.shutdownNow();
            cancel.run();
            boolean interrupted = false;
            try {
                if (!workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("Cancelled attempts are still running after {} s", SHUTDOWN_WAIT_SECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
