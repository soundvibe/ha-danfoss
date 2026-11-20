package net.soundvibe.hasio.danfoss.protocol;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FirstSuccessTest {

    @Test
    void should_return_the_first_success_and_cancel_the_other_attempt() throws Exception {
        var slowStarted = new CountDownLatch(1);
        var slowInterrupted = new CompletableFuture<Boolean>();
        var cancelled = new AtomicInteger();
        Callable<String> slow = () -> blockUntilInterrupted(slowStarted, slowInterrupted);
        Callable<String> fast = () -> {
            slowStarted.await();
            return "fast";
        };

        var result = FirstSuccess.await(List.of(slow, fast), 5, TimeUnit.SECONDS, cancelled::incrementAndGet);

        assertEquals("fast", result);
        assertTrue(slowInterrupted.get(2, TimeUnit.SECONDS));
        assertEquals(1, cancelled.get());
    }

    @Test
    void should_prefer_a_success_over_an_earlier_failure() throws Exception {
        Callable<String> failing = () -> {
            throw new IOException("refused");
        };
        Callable<String> succeeding = () -> {
            Thread.sleep(100);
            return "configuration";
        };

        assertEquals("configuration", FirstSuccess.await(List.of(failing, succeeding), 5, TimeUnit.SECONDS, () -> { }));
    }

    @Test
    void should_report_the_error_of_every_attempt_when_all_fail() {
        var first = new IllegalStateException("first");
        var second = new IOException("second");
        var cancelled = new AtomicInteger();
        Callable<String> failingFirst = () -> {
            throw first;
        };
        Callable<String> failingSecond = () -> {
            throw second;
        };

        var error = assertThrows(FirstSuccess.AllFailedException.class,
                () -> FirstSuccess.await(List.of(failingFirst, failingSecond), 5, TimeUnit.SECONDS, cancelled::incrementAndGet));

        assertEquals(List.of(first, second), error.errors());
        assertSame(first, error.getCause());
        assertEquals(1, cancelled.get());
    }

    @Test
    void should_time_out_and_cancel_every_attempt() throws Exception {
        var started = new CountDownLatch(2);
        var firstInterrupted = new CompletableFuture<Boolean>();
        var secondInterrupted = new CompletableFuture<Boolean>();
        var cancelled = new AtomicInteger();
        Callable<String> first = () -> blockUntilInterrupted(started, firstInterrupted);
        Callable<String> second = () -> blockUntilInterrupted(started, secondInterrupted);

        long start = System.nanoTime();
        assertThrows(TimeoutException.class,
                () -> FirstSuccess.await(List.of(first, second), 200, TimeUnit.MILLISECONDS, cancelled::incrementAndGet));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(firstInterrupted.get(2, TimeUnit.SECONDS));
        assertTrue(secondInterrupted.get(2, TimeUnit.SECONDS));
        assertEquals(1, cancelled.get());
        assertTrue(elapsedMillis < 3_000, "took " + elapsedMillis + " ms");
    }

    private static String blockUntilInterrupted(CountDownLatch started, CompletableFuture<Boolean> interrupted) throws InterruptedException {
        started.countDown();
        try {
            new CountDownLatch(1).await();
            return "never";
        } catch (InterruptedException e) {
            interrupted.complete(true);
            throw e;
        }
    }
}
