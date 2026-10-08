package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunOnMainAndOffloadTest {

    /**
     * Property: every Runnable submitted via runOnMain runs on the loop
     * thread, never on a caller thread.
     */
    @Test
    @Timeout(5)
    void run_on_main_executes_on_loop_thread() throws Exception {
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(50);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(5))
                .threadName("test-loop-A")
                .onTick(tick -> {})
                .build();
        loop.start();

        // Submit from 5 different caller threads, 10 tasks each.
        for (int t = 0; t < 5; t++) {
            new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    loop.runOnMain(() -> {
                        threadNames.add(Thread.currentThread().getName());
                        done.countDown();
                    });
                }
            }, "submitter-" + t).start();
        }

        assertThat(done.await(2, TimeUnit.SECONDS))
                .as("all 50 submissions should run within 2s").isTrue();
        loop.stop();

        assertThat(threadNames)
                .as("every runOnMain task should observe the loop thread name")
                .containsExactly("test-loop-A");
    }

    @Test
    @Timeout(5)
    void run_on_main_drains_before_handler_runs() throws Exception {
        AtomicInteger handlerObserved = new AtomicInteger(-1);
        AtomicInteger drainOrder = new AtomicInteger();

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(20))
                .onTick(tick -> {
                    // by the time the handler runs, the queued work must
                    // have already incremented drainOrder
                    handlerObserved.compareAndSet(-1, drainOrder.get());
                })
                .build();

        loop.runOnMain(drainOrder::incrementAndGet);
        loop.runOnMain(drainOrder::incrementAndGet);
        loop.runOnMain(drainOrder::incrementAndGet);

        loop.start();
        Thread.sleep(60); // ~3 ticks; first tick should drain everything
        loop.stop();

        assertThat(handlerObserved.get())
                .as("handler should observe drained value (3), not pre-drain (0)")
                .isEqualTo(3);
    }

    @Test
    @Timeout(5)
    void offload_runs_off_loop_thread_and_continuation_runs_on_loop() throws Exception {
        AtomicReference<String> offloadThread = new AtomicReference<>();
        AtomicReference<String> continuationThread = new AtomicReference<>();
        AtomicReference<TickLoop> loopRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .threadName("test-loop-B")
                .onTick(tick -> {
                    if (tick == 0) {
                        loopRef.get().offload(() -> {
                            offloadThread.set(Thread.currentThread().getName());
                            return 42;
                        }).thenOnMain(value -> {
                            continuationThread.set(Thread.currentThread().getName());
                            assertThat(value).isEqualTo(42);
                            done.countDown();
                        });
                    }
                })
                .build();
        loopRef.set(loop);
        loop.start();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        assertThat(offloadThread.get())
                .as("offload Supplier should NOT run on the loop thread")
                .isNotEqualTo("test-loop-B");
        assertThat(offloadThread.get())
                .as("offload Supplier should run on a thread from the offload pool")
                .startsWith("test-loop-B-offload-");
        assertThat(continuationThread.get())
                .as("thenOnMain continuation should run on the loop thread")
                .isEqualTo("test-loop-B");
    }

    @Test
    @Timeout(5)
    void offload_failure_invokes_error_handler_on_loop_thread() throws Exception {
        AtomicReference<String> errorThread = new AtomicReference<>();
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        AtomicReference<TickLoop> loopRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .threadName("test-loop-C")
                .onTick(tick -> {
                    if (tick == 0) {
                        loopRef.get().<Integer>offload(() -> {
                            throw new IllegalStateException("intentional");
                        }).thenOnMain(
                                value -> { /* unreachable */ },
                                err -> {
                                    errorThread.set(Thread.currentThread().getName());
                                    capturedError.set(err);
                                    done.countDown();
                                }
                        );
                    }
                })
                .build();
        loopRef.set(loop);
        loop.start();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        assertThat(errorThread.get()).isEqualTo("test-loop-C");
        // CompletableFuture wraps the supplier's exception in CompletionException;
        // we don't care which it is, just that the cause is preserved.
        Throwable t = capturedError.get();
        assertThat(t).isNotNull();
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        assertThat(cause).hasMessage("intentional");
    }

    @Test
    @Timeout(5)
    void offload_failure_without_error_handler_is_reported_on_loop_thread() throws Exception {
        AtomicReference<Throwable> reportedError = new AtomicReference<>();
        AtomicReference<String> reportingThread = new AtomicReference<>();
        AtomicReference<TickLoop> loopRef = new AtomicReference<>();
        CountDownLatch errorReported = new CountDownLatch(1);

        Thread.UncaughtExceptionHandler previousHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            reportingThread.set(thread.getName());
            reportedError.set(error);
            errorReported.countDown();
        });

        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(10))
                .threadName("test-loop-no-error-handler")
                .onTick(tick -> {
                    if (tick == 0) {
                        loopRef.get().<Integer>offload(() -> {
                            throw new IllegalStateException("intentional failure");
                        }).thenOnMain(value -> {
                            // Success callback must not run.
                        });
                    }
                })
                .build();

        loopRef.set(loop);

        try {
            loop.start();

            assertThat(errorReported.await(2, TimeUnit.SECONDS))
                    .as("failed offload should be reported through the loop thread")
                    .isTrue();

            Throwable error = reportedError.get();
            assertThat(error)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("offload work failed");

            assertThat(error.getCause()).isNotNull();

            assertThat(reportingThread.get())
                    .isEqualTo("test-loop-no-error-handler");
        } finally {
            if (loop.isRunning()) {
                loop.stop();
            }
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

}
