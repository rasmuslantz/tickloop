# tickloop

> Real-time JVM toolkit. Tick-based scheduler with main-thread safety
> guarantees, async I/O offload, and per-tick event queues. Building blocks
> for low-latency real-time servers: game backends, trading engines, live
> ops platforms.

[![CI](https://github.com/lucasfrederico/tickloop/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lucasfrederico/tickloop/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)

## Why this exists

Many real-time servers follow the same pattern and reinvent the same
primitives every time:

1. One thread owns the state of truth (the "main thread" / "game thread").
2. That thread runs a tight loop at fixed rate, e.g. 20 Hz = 50 ms per tick.
3. Each tick: drain incoming events, run business logic, emit outgoing events.
4. Async I/O (DB, network, files) runs off the main thread; results funnel back.
5. Slow ticks are bugs — they ripple latency to every connected user.

Minecraft does it. Factorio does it. MMORPGs do it. High-frequency trading
engines do it. Live event platforms do it. Each codebase has its own ad-hoc
version of the same scheduler, the same "drain queue at start of tick"
pattern, the same "run this back on the main thread" callback.

`tickloop` is that pattern extracted into a small library you can drop into
a Java app and stop reinventing.

## Status

**v0.2.1.** Core API stable. Spring Boot 4 starter, virtual-thread offload,
percentile histograms, bounded queues with backpressure, multi-rate groups,
and a JMH benchmark module. See [CHANGELOG.md](CHANGELOG.md).

## DV033G / HT26 testing project (Group 9)

This fork's `project/testing` branch contains a risk-driven testing extension of **TickLoop 0.2.1** for Mid Sweden University's DV033G course. Five new regression tests focus on asynchronous errors, queue interruption, shutdown timeout, listener failure and repeated offload. The project did not modify production Java behaviour.

- [Risk-to-test/oracle matrix](docs/testing-risk-oracles.md) — API expectations, tests, findings and limitations.
- [Evidence and reproducibility index](docs/project-testing-evidence.md) — baseline/final counts, exact commits, recorded CI and commands.
- [GitHub Actions for `project/testing`](https://github.com/rasmuslantz/tickloop/actions/workflows/ci.yml?query=branch%3Aproject%2Ftesting) — inspect the CI results of the revision actually submitted.

Run `mvn clean verify` using Java 21 or 23 to reproduce the full suite. The recorded extension has 58 tests overall (53 core + five Spring starter). Test outcomes are scoped evidence, not proof of all possible concurrent schedules.

## Install

### JitPack (recommended for now)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<!-- Core library -->
<dependency>
    <groupId>com.github.lucasfrederico.tickloop</groupId>
    <artifactId>tickloop-core</artifactId>
    <version>v0.2.1</version>
</dependency>

<!-- Spring Boot 4 auto-configuration (optional) -->
<dependency>
    <groupId>com.github.lucasfrederico.tickloop</groupId>
    <artifactId>tickloop-spring-boot-starter</artifactId>
    <version>v0.2.1</version>
</dependency>
```

### Direct jar download

Grab the jar from the [latest release page](https://github.com/lucasfrederico/tickloop/releases/latest):

- `tickloop-core-0.2.1.jar` — the library, zero runtime deps
- `tickloop-spring-boot-starter-0.2.1.jar` — Spring Boot 4 auto-config (optional)
- `tickloop-example-echo-game-0.2.1.jar` — runnable demo (fat-jar)

### Build from source

```bash
git clone https://github.com/lucasfrederico/tickloop.git
cd tickloop
mvn -q package -DskipTests
# core jar: tickloop-core/target/tickloop-core-0.2.1.jar
# echo demo (runnable): examples/echo-game/target/tickloop-example-echo-game-0.2.1.jar
```

Maven Central publish is on the v0.3.0 roadmap.

## Quickstart

Hello loop:

```java
import io.lucasfrederico.tickloop.TickLoop;
import java.time.Duration;

TickLoop loop = TickLoop.builder()
        .tickPeriod(Duration.ofMillis(50))    // 20 Hz
        .onTick(tick -> {
            // Runs on the loop thread, every 50 ms.
            // Mutate game/business state here freely.
        })
        .onSlowTick(stats -> System.out.println(
                "slow tick " + stats.tickNumber() + ": " + stats.durationMs() + " ms"))
        .build();

loop.start();
// ... later ...
loop.stop();
```

## Core concepts

### TickLoop — the central scheduler

Single-threaded fixed-rate loop. The thread it owns is the only thread
allowed to read/write your business state.

- **Tick rate.** Default 50 ms (20 Hz). Configurable.
- **Drift correction.** Scheduling is absolute (`startNanos + N * tickPeriod`),
  not relative — a sequence of slow ticks does not permanently push the
  schedule late.
- **Slow tick recovery.** When a tick overruns its budget, the next tick
  runs immediately, no padding sleep.
- **Slow tick listener.** Optional callback fires after any tick whose
  user-work duration exceeds a configurable threshold.

### `runOnMain` — bring work to the loop thread

```java
// From any thread:
loop.runOnMain(() -> {
    // Safe to mutate state owned by the loop thread.
    player.position = new Vec3(x, y, z);
});
```

Drained at the start of each tick, before any TickQueue and before the
user `onTick` handler.

### `offload` — push work off the loop thread

```java
// During a tick, fetch from a database without blocking the loop:
loop.offload(() -> database.loadPlayerProfile(id))
    .thenOnMain(profile -> {
        // Back on the loop thread; safe to mutate state.
        player.profile = profile;
    });
```

The `OffloadResult` only exposes `thenOnMain` (with optional error
handler) — there is no `thenApply` on the async pool. The library's
single-threaded contract holds by construction.

### TickQueue — MPSC events with auto-drain

```java
TickQueue<NetworkEvent> incoming =
    loop.createQueue("network", event -> handleNetworkEvent(event));

// From a network handler thread (multi-producer):
incoming.offer(new ConnectEvent(socket));

// On every tick, drained automatically into handleNetworkEvent on the loop thread.
```

Drain order per tick: `runOnMain` queue → registered TickQueues (in
registration order) → user `onTick` handler.

### TickMetrics — built-in counters and histograms

```java
TickMetrics m = loop.metrics();
m.tickCount();             // total ticks
m.slowTickCount();         // count above slow-tick threshold
m.lastDurationNanos();     // most recent tick's user work
m.maxDurationNanos();      // running max
m.avgDurationNanos();      // running mean
m.lastJitterNanos();       // how late the most recent tick started

// v0.2.0 percentile queries (fixed-bucket log-scale histogram, 2x precision):
m.p50DurationNanos();
m.p95DurationNanos();
m.p99DurationNanos();
m.pDurationNanos(0.999);   // arbitrary percentile
m.p99JitterNanos();
```

### Bounded TickQueue with backpressure (v0.2.0)

```java
// Bounded queue with policy for what to do when full:
TickQueue<Position> q = loop.createBoundedQueue(
        "positions", 1024, BackpressureMode.DROP_OLDEST, this::applyPosition);

// Available modes:
//   DROP_OLDEST  → evict oldest pending item on overflow
//   DROP_NEWEST  → reject new item, offer() returns false
//   BLOCK        → producer blocks until space available
//   FAIL_FAST    → throws QueueFullException

q.droppedCount();  // observability counter
```

### Virtual-thread offload pool (v0.2.0)

```java
TickLoop loop = TickLoop.builder()
        .tickPeriod(Duration.ofMillis(16))
        .useVirtualThreadsForOffload(true)   // Java 21+ virtual threads
        .onTick(this::gameTick)
        .build();
```

The loop thread itself remains a platform thread — virtual threads are
unsuitable for the deterministic-timing main loop. Only the offload pool
switches.

### Multiple tick rates: TickGroup (v0.2.0)

```java
TickGroup group = TickGroup.builder()
    .addLoop("physics",
        TickLoop.builder().tickPeriod(Duration.ofMillis(16))
            .onTick(this::physicsUpdate))           // 60 Hz
    .addLoop("ai",
        TickLoop.builder().tickPeriod(Duration.ofMillis(200))
            .onTick(this::aiUpdate))                // 5 Hz
    .build();

group.start();
// ...later:
group.stop();

// Cross-loop messaging reuses TickQueue (thread-safe by design):
TickQueue<DamageEvent> physicsInbox = group.loop("physics")
    .createQueue("damage", this::applyDamage);
physicsInbox.offer(new DamageEvent(playerId, 10));   // from AI loop
```

### Spring Boot 4 auto-configuration (v0.2.0+)

Add `tickloop-spring-boot-starter` to your `pom.xml`, then:

```java
@SpringBootApplication
public class GameApp {
    @Bean
    public TickHandler gameLoop() {
        return tick -> world.update(tick);
    }
}
```

```yaml
# application.yml
tickloop:
  tick-period: 16ms                       # 60 Hz
  thread-name: my-game-loop
  use-virtual-threads-for-offload: true
  auto-start: true                        # default
```

The starter wires a `TickLoop` bean from your `TickHandler`, exposes
`TickMetrics` for autowire, starts the loop on `ApplicationReadyEvent`,
and stops it via `DisposableBean` on context shutdown.

## Demo: `examples/echo-game`

A 100-line TCP server that broadcasts the current tick to every connected
client every second, and rebroadcasts what clients send. Shows
`TickLoop` + `TickQueue` + `runOnMain` working together. See
[`examples/echo-game/README.md`](examples/echo-game/README.md).

```
[server] listening on port 8765 (connect with: nc localhost 8765)
[server] client 1 connected from /[0:0:0:0:0:0:0:1]:50667
[server] welcome client 1. tick rate: 1 Hz.
[server] tick=2 clients=1
[client 1] hello from smoke test
[server] tick=3 clients=1
```

## Numbers (from the stress tests)

Measured on Apple Silicon (M-series), JDK 21, single shard, no other load:

| Workload | Result |
|----------|--------|
| 100 Hz no-op loop, 5 seconds | 501 ticks (target 500), 0 slow ticks, avg jitter 1.6 ms |
| Single TickQueue, 4 producers × 100k events | 400,000 events delivered, ~7M events/sec sustained, 0 loss |

Run with `mvn -pl tickloop-core test`. The stress tests print their own
numbers to stdout.

## Why not...

| Tool | Reason `tickloop` exists alongside it |
|------|---------------------------------------|
| **Vert.x** | Reactive event-loop model; not deterministic fixed-rate ticking. `tickloop` guarantees every tick completes within a bounded budget and reports slow ticks. |
| **Akka / Pekko** | Actor model, distributed-first. Heavy. `tickloop` is for one process owning local state with strict timing. |
| **Disruptor (LMAX)** | High-throughput message bus, a different abstraction. `tickloop` could use Disruptor internally for queues; the value the toolkit adds is tick semantics on top. |
| **Project Loom (virtual threads)** | Different problem: virtual threads make blocking-style async cheap. `tickloop`'s main thread is non-blocking by design. (`offload` can be wired to use virtual threads in v0.2.0.) |
| **Game engine threads** (Spigot, Unreal, Unity) | All ship ad-hoc versions of this pattern, baked into their engine. `tickloop` extracts the pattern for reuse. |

## Roadmap

### v0.2.0 (this release)

- ✅ Spring Boot 3 starter
- ✅ p50 / p95 / p99 latency histograms (vendored log-scale, zero deps)
- ✅ Multiple tick rates running in parallel (`TickGroup`)
- ✅ Backpressure modes on `TickQueue` (DROP_OLDEST, DROP_NEWEST, BLOCK, FAIL_FAST)
- ✅ Virtual-thread option for the offload pool
- ✅ JMH benchmark module (`tickloop-bench`)
- ✅ 53 tests across modules

### v0.1.0 (previous release)

- ✅ `TickLoop` with fixed-rate scheduling + drift correction + slow tick detection
- ✅ `runOnMain` + `offload` + `thenOnMain`
- ✅ `TickQueue` MPSC events with auto-drain
- ✅ `TickMetrics` counters
- ✅ 29 tests including property-based + stress
- ✅ MIT license, CI multi-OS

### v0.3.0 (planned)

- Maven Central publishing (currently JitPack only)
- Spring Boot Actuator endpoint for `TickMetrics` (e.g. `/actuator/tickloop`)
- Micrometer integration as an opt-in `SlowTickListener` + counter bridge
- HdrHistogram opt-in dependency for 3-digit-precision percentiles
- Snapshot-on-tick hooks (persistence helper)

## License

MIT — see [LICENSE](LICENSE).
