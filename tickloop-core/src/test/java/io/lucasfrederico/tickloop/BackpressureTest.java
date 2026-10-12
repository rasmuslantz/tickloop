package io.lucasfrederico.tickloop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackpressureTest {

    @Test
    @Timeout(5)
    void drop_oldest_evicts_first_item_when_full() throws Exception {
        AtomicInteger received = new AtomicInteger();

        // Loop NOT started: queue stays full so we can observe eviction behaviour.
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(60))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createBoundedQueue(
                "events", 3, BackpressureMode.DROP_OLDEST, v -> received.incrementAndGet());

        for (int i = 0; i < 10; i++) {
            assertThat(q.offer(i)).isTrue();
        }
        // 10 inserted, capacity 3 → 7 dropped.
        assertThat(q.droppedCount()).isEqualTo(7L);
        assertThat(q.size()).isEqualTo(3);
    }

    @Test
    @Timeout(5)
    void drop_newest_rejects_new_items_when_full() {
        AtomicInteger consumed = new AtomicInteger();
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(60))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createBoundedQueue(
                "events", 3, BackpressureMode.DROP_NEWEST, v -> consumed.incrementAndGet());

        assertThat(q.offer(1)).isTrue();
        assertThat(q.offer(2)).isTrue();
        assertThat(q.offer(3)).isTrue();
        assertThat(q.offer(4))
                .as("4th offer should fail (capacity 3 already used)")
                .isFalse();
        assertThat(q.offer(5)).isFalse();
        assertThat(q.droppedCount()).isEqualTo(2L);
        assertThat(q.size()).isEqualTo(3);
    }

    @Test
    @Timeout(5)
    void fail_fast_throws_when_full() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(60))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createBoundedQueue(
                "events", 2, BackpressureMode.FAIL_FAST, v -> {});

        q.offer(1);
        q.offer(2);
        assertThatThrownBy(() -> q.offer(3))
                .isInstanceOf(QueueFullException.class)
                .hasMessageContaining("events")
                .hasMessageContaining("capacity=2");
    }

    @Test
    @Timeout(5)
    void block_mode_waits_for_space() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofMillis(50))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createBoundedQueue(
                "events", 1, BackpressureMode.BLOCK, v -> consumed.incrementAndGet());

        q.offer(1); // fills capacity

        // Producer thread tries to offer; should block until tick drains.
        Thread producer = new Thread(() -> q.offer(2), "producer");
        producer.start();

        Thread.sleep(20);
        assertThat(producer.isAlive())
                .as("producer should be blocked while queue is full and loop not running")
                .isTrue();

        loop.start();
        producer.join(2000);

        // Wait for the drain of the second item before stopping.
        long deadline = System.currentTimeMillis() + 2000;
        while (consumed.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        loop.stop();

        assertThat(producer.isAlive()).as("producer should have unblocked after drain").isFalse();
        assertThat(consumed.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Timeout(5)
    void unbounded_queue_never_drops() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(60))
                .onTick(tick -> {})
                .build();
        TickQueue<Integer> q = loop.createQueue("unbounded", v -> {});

        for (int i = 0; i < 10_000; i++) q.offer(i);
        assertThat(q.droppedCount()).isEqualTo(0L);
        assertThat(q.size()).isEqualTo(10_000);
        assertThat(q.isBounded()).isFalse();
    }

    @Test
    void bounded_constructor_rejects_invalid_capacity() {
        TickLoop loop = TickLoop.builder()
                .tickPeriod(Duration.ofSeconds(60))
                .onTick(tick -> {})
                .build();
        assertThatThrownBy(() -> loop.createBoundedQueue(
                "x", 0, BackpressureMode.DROP_NEWEST, v -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop.createBoundedQueue(
                "x", -5, BackpressureMode.DROP_NEWEST, v -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(5)
    void block_mode_interruption_returns_false_and_restores_interrupt_flag() throws Exception {
        TickLoop loop = TickLoop.builder()
                .onTick(tick -> {})
                .build();

        TickQueue<Integer> queue = loop.createBoundedQueue(
                "events", 1, BackpressureMode.BLOCK, value -> {});

        // Arrange: fill the queue without starting the loop.
        assertThat(queue.offer(1)).isTrue();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean offerResult = new AtomicBoolean(true);
        AtomicBoolean interruptRestored = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            started.countDown();
            try {
                offerResult.set(queue.offer(2));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        }, "blocked-producer");

        producer.start();

        try {
            // Confirm the producer cannot complete while the queue is full.
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(100, TimeUnit.MILLISECONDS))
                    .as("producer should wait while the BLOCK queue is full")
                    .isFalse();

            // Act: interrupt the blocked producer.
            producer.interrupt();

            // Assert: the documented interruption contract is respected.
            assertThat(finished.await(2, TimeUnit.SECONDS))
                    .as("interrupted producer should finish promptly")
                    .isTrue();

            assertThat(offerResult.get()).isFalse();
            assertThat(interruptRestored.get()).isTrue();
            assertThat(queue.size()).isEqualTo(1);
        } finally {
            producer.interrupt();
            producer.join(2000);
        }
    }
}
