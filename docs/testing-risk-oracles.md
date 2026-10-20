# TickLoop project: risk, oracle, and test-evidence matrix

**Project scope:** `tickloop-core`, with reliability, concurrency/thread safety, and lifecycle robustness as primary quality concerns.
**Testing approach:** contract-based, risk-driven regression tests for important failure and boundary paths.
**Repository branch:** `project/testing` in the course group's TickLoop fork.
**Original baseline:** upstream commit `a7bf2d030e9d579f63a8bef7e0f2376ed35886a4`.

## 1. Project goal and why these risks were chosen

TickLoop is a Java library for fixed-rate loops, thread-confined application state, asynchronous offload, and event queues. An error in one of these areas could cause lost events, hidden failures, a loop that stops unexpectedly, or an incorrect shutdown result. We therefore selected observable failure paths with consequences for the library's documented behaviour, rather than adding tests simply to raise coverage. The baseline tests already covered many normal cases; the new tests target specific failure, boundary, and recovery conditions.

Our *test oracle* is the source of the expected result, not the observed result of the implementation being tested. We use this hierarchy:

1. **Public API documentation and README contracts**, especially return values, thread ownership, interruption, and lifecycle semantics.
2. **Explicit testable invariants derived from those contracts**, for example that a full queue must not gain a second event after an interrupted `BLOCK` offer.
3. **Clearly labelled characterisation assumptions** when the precise error policy is not explicitly documented; these require further confirmation from the maintainers and are weaker oracles.

Existing tests and code inspection help identify paths and formulate hypotheses, but passing an existing test or matching current implementation alone is not sufficient to establish correctness.

## 2. Risk-to-test traceability

### R1 — Unhandled asynchronous failure

- **Motivation / quality concern:** Background work may fail. If the failure is hidden or reported on the wrong thread, applications relying on single-thread ownership may not be able to handle the error safely.
- **Oracle:** [`OffloadResult.thenOnMain` Javadoc](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/OffloadResult.java#L28-L46) states that a failed task without an explicit failure callback is routed to the loop thread's uncaught-exception handler; the public [`TickLoop` thread contract](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/TickLoop.java#L17-L35) supports that expectation.
- **Test / technique:** [`offload_failure_without_error_handler_is_reported_on_loop_thread`](../tickloop-core/src/test/java/io/lucasfrederico/tickloop/RunOnMainAndOffloadTest.java), fault injection with a known `IllegalStateException`, an observable error handler, and a latch.
- **Observable assertions:** An error is reported within the test's wait; it is a `RuntimeException` describing failed offload work; a cause remains available; the reporting thread has the expected loop-thread name.
- **Conclusion and limit:** The observed local passing test supports the documented error-reporting path for this injected failure. It does not prove every possible background failure or error-handler interaction is safe; the test does not separately assert that the success callback never executes.

### R2 — Full bounded queue interrupted in `BLOCK` mode

- **Motivation / quality concern:** A producer waiting on a full queue must not hang indefinitely or silently add an event after interruption. This matters to backpressure and predictable shutdown/recovery.
- **Oracle:** [`TickQueue.offer` Javadoc and `BLOCK` contract](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/TickQueue.java#L74-L85) require waiting for capacity, and returning `false` while restoring the interrupt flag if interrupted.
- **Test / technique:** [`block_mode_interruption_returns_false_and_restores_interrupt_flag`](../tickloop-core/src/test/java/io/lucasfrederico/tickloop/BackpressureTest.java), capacity boundary (one slot, already full), controlled concurrent producer, and interruption.
- **Observable assertions:** The producer initially waits; after interruption, it finishes, `offer` returns `false`, its interrupt flag is set, and the queue size remains 1.
- **Conclusion and limit:** The observed local passing test supports this particular documented interruption outcome. The queue-size assertion does not inspect the identity/content of the remaining event; other producer schedules and simultaneous operations are not exhaustively explored.

### R3 — Timeout when stopping a blocked loop

- **Motivation / quality concern:** Reporting successful shutdown while the main loop is still executing would mislead callers about whether state is safe to release or a process is ready to exit.
- **Oracle:** [`TickLoop.stop(Duration)` API Javadoc](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/TickLoop.java#L96-L120) defines `true` as termination within the specified timeout. A later successful stop is expected once the blocked handler has been released.
- **Test / technique:** [`stop_returns_false_when_tick_exceeds_timeout`](../tickloop-core/src/test/java/io/lucasfrederico/tickloop/TickLoopTest.java), lifecycle/timeout transition with latches that deliberately block and release a tick handler.
- **Observable assertions:** `stop(100 ms)` returns `false` while the handler remains blocked; after release, `stop(2 s)` returns `true`.
- **Conclusion and limit:** The observed local passing test supports the documented return-value contract in this blocked-handler scenario. It does not establish strict timing accuracy or cover every possible stop/offload interleaving.

### R4 — Exception in the slow-tick listener

- **Motivation / quality concern:** Diagnostic and monitoring callbacks should not unexpectedly stop the main scheduler: a reporting failure must be kept separate from core ticking where possible.
- **Oracle (partly inferred):** The [`TickLoop` execution and error-isolation behaviour](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/TickLoop.java#L247-L310) shows exceptions routed to the thread's uncaught-exception handler. The README documents the slow-tick listener, but the exact failure policy for this callback is not a standalone public specification. We treat continued ticking and visible error reporting as a justified characterisation expectation that should be confirmed with maintainers.
- **Test / technique:** [`exception_in_slow_tick_listener_does_not_kill_the_loop`](../tickloop-core/src/test/java/io/lucasfrederico/tickloop/TickLoopTest.java), injected callback exception and recovery observation.
- **Observable assertions:** The listener is invoked; at least three ticks occur despite the injected listener failure; the test's exception handler observes the injected `RuntimeException`.
- **Conclusion and limit:** The observed local passing test supports fault isolation under this scenario. Its oracle is weaker than an explicit API promise. The test temporarily replaces the JVM-wide default uncaught-exception handler, a possible test-isolation concern if test execution becomes parallel.

### R5 — Multiple offloaded tasks after lazy pool initialisation

- **Motivation / quality concern:** Applications need repeated background tasks to complete and deliver results without silently dropping work after the first offload.
- **Oracle:** [`TickLoop.offload` and lazy-pool documentation](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/TickLoop.java#L198-L245) and [`thenOnMain` continuation contract](../tickloop-core/src/main/java/io/lucasfrederico/tickloop/OffloadResult.java#L28-L46).
- **Test / technique:** [`reuses_initialized_offload_pool`](../tickloop-core/src/test/java/io/lucasfrederico/tickloop/TickLoopTest.java), repeated operation with two offloads, a completion latch, and an order-independent result assertion.
- **Observable assertions:** Both continuations complete within the allowed wait and the collected results contain exactly 1 and 2.
- **Conclusion and limit:** The observed local passing test supports successful repeated offload and result delivery. **It does not directly prove the same executor instance was reused or assert the actual continuation thread.** The current test name is broader than its observable proof; direct reuse/thread-identity verification remains a possible improvement.

## 3. What evidence exists and what must still be measured

| Evidence | Observation | Interpretation / boundary |
| --- | --- | --- |
| Original baseline | Original suite passed: 53 tests overall, 48 in `tickloop-core`. Baseline JaCoCo: 88% instructions and 82% branches in `tickloop-core`. | The original suite was substantial, but these percentages do not establish correctness of the selected failure paths. |
| After first three new tests | Previous local JaCoCo measurement: 90% instructions and 84% branches for `tickloop-core`. | This demonstrates a modest structural increase **at that intermediate stage**, not a final measurement after all five tests. |
| PR #5 review (19 Oct 2026) | `mvn -pl tickloop-core -Dtest=TickLoopTest test`: 10 run, 0 failures, 0 errors; `mvn clean verify`: all reactor modules `SUCCESS`, on the PR #5 branch. | Reproducible local evidence for that tested branch. The five test changes have subsequently been merged into `project/testing`; the *final combined branch* should be run and recorded again. |
| GitHub Actions | No workflow run has yet been confirmed for the group's `project/testing` branch. | **Do not claim CI is green.** The workflow currently triggers on `main` and PRs to `main`, not on pushes/PRs to `project/testing`. |
| Defects / fixes | No new production-code defect or production fix has been established by these five added regression tests. | The achievement is more explicit regression detection for selected contracts and risks, not a demonstrated bug fix. |

### Reproduction on the combined branch

From the repository root after switching to `project/testing` and pulling the latest changes:

```bash
java -version
mvn -version
mvn -pl tickloop-core -Dtest=TickLoopTest,BackpressureTest,RunOnMainAndOffloadTest test
mvn clean verify
```

Record the date, Java/Maven versions, exact branch commit SHA, test totals, build result, and JaCoCo report numbers. For `tickloop-core`, inspect `tickloop-core/target/site/jacoco/index.html` after `verify`. Attach or link the final CI run once the GitHub Actions trigger is configured and a green run is available.

## 4. Risk reduction versus test-suite evidence

These tests provide evidence that the current implementation behaves as expected in the **specific exercised scenarios**, and they can detect some future regressions when re-run. They do **not** prove absence of concurrency defects, complete mitigation of each real-world risk, or suitability of the system for every deployment.

Residual risks include untested scheduling interleavings, machine-dependent timing, interactions between failures and shutdown, the global exception-handler side effect in some tests, and the unverified executor-identity claim in R5. A useful next improvement would be to strengthen the least-direct assertions and run repeated tests on multiple JDK/OS environments, especially in CI.

## 5. Link to project goals and proposed upstream contribution

The overall project goal is to evaluate the reliability of TickLoop's tick loop, queue backpressure, asynchronous work and lifecycle under abnormal conditions. The five tests turn selected quality risks into explicit, executable checks. The results are meaningful because they check documented or justified behaviour, not just executed lines of code. The project report should discuss this traceability and contrast the results with the untested paths.

The five tests are committed and merged in the group's fork on `project/testing`. We can also **propose** the tests to the original `lucasfrederico/tickloop` repository through a cross-repository pull request, after reviewing the proposed diff and confirming its test suite is green. An upstream acceptance is not assumed and is outside our control; if a PR is opened, record its URL and current status rather than claiming it was accepted.

**Note:** This matrix is a test-design and interpretation artifact. Add final-run numbers only after collecting them, and reconcile its conclusions with the final code and report before submission.
