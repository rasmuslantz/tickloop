# TickLoop — DV033G HT26 testing evidence and reproduction

This index accompanies the risk/oracle matrix and the course project report. It distinguishes the **test changes** from **what each CI run actually checked**. The submitted revision must be identified by a pinned commit (and a course tag, if created); do not infer that a run for one revision validates a later revision.

## Scope and method

- Group fork: https://github.com/rasmuslantz/tickloop ; study branch: `project/testing`.
- System: TickLoop v0.2.1, primarily `tickloop-core`; whole-build regression includes the Spring starter.
- Five added Java regression tests address R1 failed offload reporting, R2 interrupted BLOCK queue offer, R3 stop timeout, R4 slow-listener exception, and R5 repeated offload.
- The added tests check behaviour using the real loop, queue and concurrency primitives. No production Java fix was made. See the [risk/oracle matrix](testing-risk-oracles.md) for assertions and threats to validity.

## Measured before/after

The baseline is a snapshot of the original suite with only JaCoCo instrumentation. The final measured suite below is the integrated five-test version checked in the recorded CI artifact.

| Metric, tickloop-core unless noted | 8 Oct baseline | 24 Oct measured extension |
| --- | ---: | ---: |
| Core tests | 48 | 53 |
| Whole-build tests | 53 | 58 |
| Instructions | 1,371 / 1,546 (88.7%) | 1,404 / 1,546 (90.8%) |
| Branches | 107 / 129 (82.9%) | 110 / 129 (85.3%) |
| Lines | 332 / 376 (88.3%) | 346 / 376 (92.0%) |

The original baseline was measured locally and the later counters came from CI; this is a documented before/after comparison, not a controlled causal experiment. A green suite and higher coverage do not establish thread safety for every possible schedule.

## Immutable source and CI evidence

- Upstream starting point: [`a7bf2d0`](https://github.com/rasmuslantz/tickloop/commit/a7bf2d030e9d579f63a8bef7e0f2376ed35886a4).
- Baseline + JaCoCo only: [`57f0f0b`](https://github.com/rasmuslantz/tickloop/commit/57f0f0bfc3834ed055f096f0ed6d7a41d487eb50).
- Combined five tests and original project-branch CI workflow: [`71ef3c9`](https://github.com/rasmuslantz/tickloop/commit/71ef3c9bbb31f6bb289fadb1d0bbd4a8e0835627).
- Manual CI trigger integrated in [PR #8](https://github.com/rasmuslantz/tickloop/pull/8) on 26 Oct 2026: [merge commit `ae79b25`](https://github.com/rasmuslantz/tickloop/commit/ae79b25e50c878fc93b19556169c36c8f808e0ff).
- **Confirmed four-job CI result:** [run 40060902726](https://github.com/rasmuslantz/tickloop/actions/runs/40060902726), 24 Oct 2026, on the PR's synthetic merge revision [`925d1e3`](https://github.com/rasmuslantz/tickloop/commit/925d1e3bfac428cbfd40d417f94a43ed0fef1a79). Java 21 + 23 on Ubuntu and macOS succeeded.
- **Saved coverage/test reports:** [artifact 12547009262](https://github.com/rasmuslantz/tickloop/actions/runs/40060902726/artifacts/12547009262), `tickloop-java21-ubuntu-evidence`, with core JaCoCo HTML/XML/CSV and core/Spring Surefire XML.
- **Current branch and later CI:** [`project/testing` Actions runs](https://github.com/rasmuslantz/tickloop/actions/workflows/ci.yml?query=branch%3Aproject%2Ftesting). Always select the run whose recorded commit matches the submission commit/tag, not merely a green result for another revision.

## Reproduce the suite

Requirements: Git, JDK 21 or 23, Maven; the codebase uses a multi-module Maven reactor.

```bash
git clone https://github.com/rasmuslantz/tickloop.git tickloop-review
cd tickloop-review
git fetch origin project/testing
git switch --detach 71ef3c9bbb31f6bb289fadb1d0bbd4a8e0835627
java -version
mvn -version
mvn -pl tickloop-core -Dtest=TickLoopTest,BackpressureTest,RunOnMainAndOffloadTest test
mvn clean verify
```

That pinned revision reproduces the five-test source snapshot; to check the **submission revision**, replace the SHA after `git switch --detach` with its exact full commit or tag. JaCoCo: `tickloop-core/target/site/jacoco/index.html`; Surefire XML: `*/target/surefire-reports/`. Tests involving real thread scheduling use bounded waits and are not guaranteed to explore every interleaving.

## Evidence limits

R4 partly characterises the current implementation rather than a maintainer-confirmed exception policy. The R2 test checks remaining queue size but not retained-item identity; R5 checks two completed results but does not directly assert executor identity. No production incident reduction, timing guarantee, mutation-testing result, performance benchmark or upstream acceptance is claimed.
