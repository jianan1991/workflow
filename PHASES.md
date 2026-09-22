# Phases

The build plan, and the tests that decide whether a phase is finished.

**The rule: do not start a phase until the previous one's exit checks all pass.** Tick them
here and add a line to the verification log at the bottom. A phase with unticked boxes is
in progress, whatever the code looks like.

Each phase states four things. The fourth matters most:

- **Delivers** — what exists at the end that did not before
- **Exit checks** — the specific things you run, written so two people would agree
- **Stop if** — the result that means do not continue
- **Log** — when it was last verified, and by whom

A phase with no *stop if* is not a phase, it is a wish.

---

## Commands

```bash
cd orchestrator

mvn test                      # fast suite: unit + workflow. No Docker, no keys. ~6s
mvn verify                    # adds integration tests. Needs Docker.
mvn verify -Pgates            # adds the coverage and mutation gates. Phase 5 onward.
```

Or from the repository root:

```bash
./scripts/verify.sh           # fast suite
./scripts/verify.sh --all     # fast + integration
./scripts/verify.sh --gates   # everything, including the CI gates
```

Integration tests are named `*IT.java`, which surefire ignores and failsafe picks up. Keep
it that way — the moment `mvn test` needs a container, people stop running it.

**Run the fast suite on every change. Run the integration suite before ticking any exit
check, and at least weekly regardless.** Integration tests that only run at phase
boundaries rot between them.

---

## Status

| Phase | | Last verified |
| :-- | :-- | :-- |
| 0 · Baseline | ◐ collector built, needs a repo and 14 days of tally | 2026-09-22 |
| 1 · Cost of one epic | ◐ costs derived from real prices, token counts still estimates | 2026-09-22 |
| 2 · Infrastructure | ☑ **done** | 2026-09-22 |
| 3 · Retrieval | ◐ automated + integration green, manual pass outstanding | 2026-09-22 |
| 4 · Orchestration, stub agents | ☑ **done** | 2026-09-22 |
| 5 · CI gates | ◐ gate proven, CI wiring outstanding | 2026-09-22 |
| 6 · PM agent | ☐ not started | — |
| 7 · Architect | ☐ not started | — |
| 8 · First dev agent | ☐ not started | — |
| 9 · Reviewer and QA | ☐ not started | — |
| 10 · Escalation and write-back | ☐ not started | — |
| 11 · Auto-approval | ☐ not started | — |
| 12 · Blast radius | ☐ not started | — |

☐ not started · ◐ in progress · ☑ done

---

## Test layers

Cheapest first. Anything testable at a lower layer is tested there.

| Layer | Proves | Speed | Needs |
| :-- | :-- | :-- | :-- |
| Unit | Pure logic — chunking, dependency layering, cost arithmetic, cosine | ms | nothing |
| Workflow | Orchestration — blocking, resume, escalation, routing, gates | seconds | Temporal test env, in-process |
| Integration | Real infrastructure — Qdrant, Postgres, the build | minutes | Docker |
| Judgement | Whether an agent's output is any good | manual | a person |

**An agent's output quality is never asserted in an automated test, and its plumbing is
never left to manual judgement.** Conflating the two gives you a suite that passes while
the agents produce nonsense, or a person re-checking what a unit test settled in a
millisecond.

**Stubs are permanent, not scaffolding.** Every agent has a deterministic stub that fails in
a defined pattern — they are how orchestration stays testable after the real agents arrive.
A stub that always succeeds tests nothing.

---

## Phase 0 — Baseline

**Delivers:** two weeks of numbers describing how the team works today.

**Exit checks**

```bash
export GITHUB_TOKEN=ghp_...
python scripts/baseline.py --repo owner/name --days 14
```

- [x] Collector written — `scripts/baseline.py`, reproducible, writes `baseline/pull-requests.csv`
- [ ] Run against the real repository (blocked on Q1: which repo)
- [ ] Median PR-opened-to-merged time, for a 14-day window
- [ ] PRs merged per engineer per week
- [ ] **Review hours per engineer per week** — tally by hand in `baseline/review-hours.csv`

**Review hours cannot be derived from any API.** GitHub records when a review was
submitted, never how long it took. Do not substitute review *latency* for it: a PR that sat
six hours and took four minutes to read is not six hours of anyone's attention, and treating
it as such makes the pipeline look like it saved time it never cost.

**Stop if:** you cannot collect them. Without a baseline the POC ends in a feeling rather
than an answer, and every later phase is unfalsifiable.

---

## Phase 1 — Cost of one epic, by hand

**Delivers:** one real epic walked through every stage on paper at current model prices.

**Exit checks**

```bash
cd orchestrator && mvn -q -DskipTests compile
java -cp target/classes com.loop.orchestrator.cost.CostWorksheet \
     --tier1 4 --tier2 2 --ui --model ../cost-model.properties
```

- [x] Worksheet written, running the same arithmetic gate 1 will use
- [x] Cost model externalised to `cost-model.properties`, read by the worksheet *and* the
      pipeline — one file, no second copy to drift
- [x] Unit costs **derived** from Anthropic list prices x per-agent token estimates, rather
      than typed in. Prices cached 2026-06-24; check before quoting
- [x] Tier mapping explicit: strong → Claude Opus 5, mid → Claude Sonnet 5,
      cheap → Claude Haiku 4.5
- [ ] Token estimates replaced with measured counts — use the token counting endpoint on
      **one real prompt per agent, carrying the context it would actually receive**
- [ ] Human minutes timed with a stopwatch on three real reviews
- [ ] A per-epic dollar figure you would defend

Current output: **$12.02 and 140 human minutes** for a six-story epic with a UI.

| agent | model | in | out | $/run |
| :-- | :-- | --: | --: | --: |
| pm | claude-opus-5 | 8k | 2k | 0.0900 |
| ba | claude-opus-5 | 6k | 1.5k | 0.0675 |
| architect | claude-opus-5 | 15k | 3k | 0.1500 |
| designer | claude-opus-5 | 10k | 4k | 0.1500 |
| dev-t1 | claude-haiku-4-5 | 150k | 15k | 0.2250 |
| **dev-t2** | **claude-opus-5** | **400k** | **40k** | **3.0000** |
| reviewer | claude-sonnet-5 | 20k | 2k | 0.0600 |
| qa | claude-sonnet-5 | 25k | 3k | 0.0800 |

**The prices are facts; the token counts are assumptions.** One tier-2 dev run costs more
than every planning and checking agent in the epic combined, and the two tier-2 stories
carry 83% of the model spend. Cost attention belongs there and nowhere else.

**Output tokens include thinking.** Adaptive thinking is on by default on the strong tier
and bills as output, so an agent doing real reasoning costs far more on the output side than
the length of its visible answer suggests. Do not estimate output from the reply you expect
to read.

**Stop if:** the number is far outside $15–40 per epic. Not because it is unaffordable, but
because the argument for this project changes and should be re-made before weeks are spent.

---

## Phase 2 — Infrastructure

**Delivers:** Qdrant, Temporal with Postgres, the Temporal UI and the LiteLLM proxy, from
one compose file.

**Exit checks**

- [x] `docker compose up -d` from empty volumes — all four containers healthy
- [x] `curl http://localhost:6333/collections` returns an empty list
- [x] The Temporal UI serves HTTP 200 at `http://localhost:8080`
- [x] `temporal operator cluster health` reports **SERVING**
- [x] `temporal` and `temporal_visibility` schemas created
- [x] Everything survives `docker compose restart`
- [x] Memory measured — **522 MiB** for the stack, against a 10 GB ceiling

**Stop if:** the stack needs more than 10 GB alongside a Spring app under QA. That is the
real per-developer ceiling and it is better known now than in phase 8.

**Two defects found and fixed getting here.**

*Postgres data was bind-mounted into a OneDrive-synced folder.* A file sync client touching
a live database directory corrupts it slowly, in a way that looks like a bug in your own
code. Both stores now use named Docker volumes.

*`POSTGRES_DB` must not be set on the Postgres service.* Postgres then defaults it to
`POSTGRES_USER`, creating a `temporal` database on first boot. Auto-setup connects to
`DBNAME` before it can create `temporal_visibility` and lay down the schema, so without that
database the stack restart-loops on `database "temporal" does not exist`. Both the original
value and `postgres` fail; leaving it unset is what works.

---

## Phase 3 — Retrieval

**Delivers:** the knowledge base indexed and searchable; the attempt cap that decides when
an agent must ask a person.

**Exit checks**

- [x] `mvn test` — `MarkdownChunkerTest` (6) and `RetrievalTest` (8) green
- [x] `mvn verify` — `QdrantVectorStoreIT` green (4 tests, 9.4s), proving Qdrant and the
      in-memory store rank the same corpus identically
- [ ] `knowledge.embedder=litellm` set, collection dropped and reindexed
- [ ] **A dozen real questions run by hand, results read by a person:**
      ```bash
      curl -X POST http://localhost:8081/knowledge/reindex
      curl "http://localhost:8081/knowledge/search?q=how+do+rate+limits+key"
      ```
- [ ] For questions whose answers are genuinely in the notes, the right section is in the
      top three every time

**Stop if:** it is not. An agent with bad retrieval does not error, it invents, and
everything downstream inherits that silently.

**The hashing embedder cannot settle this.** It matches on shared words, so "throttling"
and "rate limiting" look unrelated to it. It proves the plumbing; a real model proves the
retrieval.

---

## Phase 4 — Orchestration with stub agents

**Delivers:** the epic and story workflows, three gates, the question queue, the rework
loop, escalation, dependency-ordered fan-out.

**Exit checks**

- [x] `mvn test` — `EpicWorkflowTest` (5) and `StoryWorkflowTest` (4) green
- [x] **The restart check, against a real Temporal server** — run in a stronger order than
      originally written, signalling while nothing was listening:
      1. Epic started; blocked on `Q-101` at journey stage 5
      2. Worker process killed outright — HTTP dead, `historyLength` 29, status **RUNNING**
      3. Answer signalled with **no worker alive** — accepted, `historyLength` 31, still RUNNING
      4. New worker started; picked up the queued signal and resumed from the `Workflow.await`
         line — re-mapped the journey, decomposed, estimated, reached gate 1
- [x] Full epic driven to **MERGED** end to end: 4 tier-1 stories auto-approved, 2 tier-2
      through gate 3, **2 rework loops with no human in them**

**Stop if:** the resume fails. That is the single assumption the whole architecture rests
on, and every other component is built on top of it.

**Known gap found while running this.** The story workflow accumulates spend from whatever
`QaResult.costUsd()` reports, and the stub hardcodes that — so the run reported $2.92 actual
against a $12.02 estimate. That difference is a stub artifact, **not** a finding about cost.
Actual-versus-estimate reconciliation is not wired yet: real spend has to come from the
LiteLLM proxy's per-call accounting, keyed by agent. Until it does, the "actual" column on
the cost screen is fiction.

---

## Phase 5 — CI gates

**Delivers:** JaCoCo branch coverage and PIT mutation thresholds, enforced.

**Exit checks — deliberately write a bad test**

A test that runs every line of `EstimateCalculator` and asserts nothing, then:

```bash
# coverage from the bad test alone
mvn test jacoco:report -Dtest=GateProofTest -DfailIfNoSpecifiedTests=false

# mutation from the bad test alone
mvn test-compile org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses=com.loop.orchestrator.cost.EstimateCalculator \
  -DtargetTests=com.loop.orchestrator.cost.GateProofTest \
  -DmutationThreshold=70
```

- [x] Coverage **passes** on that test — measured **100% branch, 100% line**
- [x] Mutation **fails** on that test — **19%**, build stops on the 70% threshold
- [x] The same class with real tests scores **92%**, so the gate passes good work
- [x] The bad test deleted afterwards
- [ ] Diff-coverage check added to CI against the merge base, with the project rule kept as
      a ratchet that only forbids regression — **blocked: no CI pipeline exists yet**

**Stop if:** mutation also passes. The gate protects nothing, and an agent told to reach 90%
will produce exactly that test by the hundred.

**What the numbers mean.** A single test asserting nothing produced perfect coverage. If
coverage were the gate, that test would have merged. Mutation testing changed 37 things
about the code and the test noticed 7 of them — that is the difference between exercising a
line and checking what it does.

---

## Phase 6 — The PM agent, journey mapping only

**Delivers:** the first real model call. No stories generated.

**Exit checks**

- [ ] Run against **five epics that already shipped**, where you know what was missed
- [ ] For at least three of five, its gap questions include one a person would genuinely
      have asked and nobody did
- [ ] No question asks about something plainly stated in the epic
- [ ] Cost per run recorded, and compared against the $0.71 the estimator assumes

**Stop if:** its questions are generic ("what are the requirements?"). That is a prompt or
retrieval problem, and shipping it means every downstream agent inherits invented
requirements.

**Do not substitute live epics for shipped ones.** You need the right answer in advance, or
you are grading an exam you have not marked.

---

## Phase 7 — The architect

**Delivers:** decomposition, tier assignment, the dependency graph.

**Exit checks**

- [ ] Decompose three shipped epics; compare against what was actually built
- [ ] No dependency ordering that would have been impossible
- [ ] Tier assignments match a person's judgement on at least 70% of stories
- [ ] The graph is derived from the code index, not from the model's reading of the epic

**Stop if:** dependencies are invented or missed. The compiler knows what imports what with
certainty — paying a model to guess at it is slower and wrong more often.

---

## Phase 8 — The first dev agent, one tier 1 story

**Delivers:** the first real pull request.

**Exit checks**

- [ ] The simplest tier-1 story, start to finish
- [ ] A PR opens and CI passes, including the mutation gate
- [ ] A person would merge it without changes
- [ ] Fewer than three attempts
- [ ] Wall-clock per attempt recorded

**Stop if:** the tests it writes pass coverage while failing mutation. That means the agent
has learned to game the number rather than write assertions, and it will keep doing it.

**Keep measuring wall-clock from here on.** This is where the Java tax lands, and it
compounds through every later phase.

---

## Phase 9 — Reviewer and QA

**Delivers:** the two checks that fail differently, which is why both exist.

**Exit checks — inject known defects into a merged PR**

| Defect | Reviewer | QA |
| :-- | :-- | :-- |
| Logic inverted in a conditional | catches | catches |
| Endpoint returns 429 with an empty body | **misses** | catches |
| Scope creep — edits an unrelated config file | catches | misses |
| Meets the criteria, criteria are wrong | misses | catches, routes to the PM |

- [ ] The pattern above holds
- [ ] QA asserts against the PM journey and BA criteria, verified by reading its prompt
- [ ] The reviewer runs on a different model family from the authoring agent

**Currently unmet.** Every tier in `cost-model.properties` resolves to an Anthropic model,
so reviewer and author share a family and the self-agreement mitigation is weakened. Either
point `tier.review` at a non-Anthropic model through LiteLLM, or accept the weaker
mitigation knowingly and lean harder on the auto-approval revert counter.

**Stop if:** QA validates against the dev agent's own tests. If the agent writes the tests
and QA confirms they pass, nothing has been measured — and this will not show up as a
failure. Check it by hand.

The reviewer missing the empty body is not a bug. If the reviewer catches everything, you
have not proved you need QA.

---

## Phase 10 — Escalation and write-back

**Delivers:** MID to SENIOR reassignment, and answers becoming knowledge-base commits.

**Exit checks**

- [ ] Give MID a story it cannot do; confirm reassignment to SENIOR, not escalation to a
      person
- [ ] Only a SENIOR failure reaches a human
- [ ] Twenty merges run, and every proposed knowledge-base commit read
- [ ] Fewer than a quarter of merges produce a commit
- [ ] Questions asked per epic falling week over week

**Stop if:** status is being written into the knowledge base. An agent asking "how does
audit export work?" and getting back "LOOP-09-2 is pending" means retrieval is being
poisoned at nineteen entries a week.

If questions per epic does not fall, the write-back loop is decorative and the whole
premise of the system is unproven.

---

## Phase 11 — Auto-approval

**Delivers:** tier 1 stories merging with no person involved.

**Exit checks**

- [ ] **Shadow mode for two weeks** — log what *would* have been auto-approved, and review
      every one of them by hand anyway
- [ ] Over at least 20 shadow decisions, you agreed with the machine every time
- [ ] The revert counter is visible before the feature is switched on

**Stop if:** you disagree even once before turning it on. Once live, the count of
auto-approvals later reverted — not your confidence — tells you whether the rule is right.

---

## Phase 12 — Blast radius

**Delivers:** the answer to "an auto-approved PR merged, and then what?"

**Exit checks**

- [ ] Merge a change that breaks production behaviour behind a feature flag
- [ ] Turn the flag off; measure merge-to-safe
- [ ] A bad merge can be neutralised without a revert, a redeploy, or waking anyone

**Stop if:** this is still unstarted when auto-approval goes live. Auto-approval without a
rollback path is more dangerous than the reviewer agent it depends on, and it is currently
the largest undesigned gap in the system.

---

## Verification log

Append a line whenever exit checks are run. Date, phase, what was run, outcome.

| Date | Phase | Ran | Outcome |
| :-- | :-- | :-- | :-- |
| 2026-09-22 | 3, 4 | `mvn test` | 23 tests green. Automated checks only — the manual retrieval pass and the worker-restart check are still outstanding. |
| 2026-09-22 | 3 | `mvn verify` | Not run. Docker daemon was not available; `QdrantVectorStoreIT` compiles but has never executed. |
| 2026-09-22 | 5 | JaCoCo + PIT against an assertion-free test | **Gate proven.** Coverage 100% branch / 100% line, mutation 19% — build failed on the threshold, as it must. Same class with real tests: 92%. Bad test deleted. CI diff-coverage still outstanding because no pipeline exists. |
| 2026-09-22 | — | `mvn test` | 36 tests green after extracting `EstimateCalculator` out of the stub agent. |
| 2026-09-22 | 2 | `docker compose up` from empty volumes, restart, health probes | **PASS.** Two defects found and fixed first: Postgres data bind-mounted into OneDrive, and `POSTGRES_DB` set on the Postgres service causing a Temporal restart loop. Stack 522 MiB. |
| 2026-09-22 | 3 | `./scripts/verify.sh --all` | **PASS.** 36 fast + 4 integration. Store parity between Qdrant and the in-memory store confirmed. Manual retrieval quality pass still outstanding — needs the litellm embedder. |
| 2026-09-22 | 0, 1 | built `scripts/baseline.py` and `CostWorksheet` | Instruments ready. Neither phase can close until the repo is named (Q1) and the unit costs are measured rather than guessed. |
| 2026-09-22 | 4 | worker kill/restart against real Temporal + full epic run | **PASS.** Workflow survived the worker being killed, a signal was accepted with no worker alive, and the new worker resumed from the await line. Epic ran through to MERGED with 2 rework loops and no human in them. Found that actual-cost reconciliation is not wired — see the phase 4 note. |
| 2026-09-22 | 1 | `AgentCostModel` + `mvn test` | Unit costs now **derived** from Anthropic list prices x per-agent tokens, with tier→model mapping. Six-story epic: **$12.02**. 49 tests green. Token counts are still estimates — that is what remains before phase 1 closes. |

---

## What is currently green

| Suite | Tests | Covers |
| :-- | --: | :-- |
| `EstimateCalculatorTest` | 13 | The worked example, which stages loop, tier cost, designer only with a UI, human minutes, four rejections |
| `MarkdownChunkerTest` | 6 | Section splitting, frontmatter, stable ids, folding short sections |
| `RetrievalTest` | 8 | Search accuracy, attribution, re-indexing, the attempt cap, failure modes |
| `EpicWorkflowTest` | 5 | Journey blocking and resume, abandonment, rejected gate, dependency layers |
| `StoryWorkflowTest` | 4 | Auto-approve, the rework loop, the PM route, an unreviewed PR |
| `QdrantVectorStoreIT` | 4 | Store parity, payload round trip, upsert replacing, idempotent setup |
| **Fast suite** | **36** | No Docker, no API key, about 6 seconds |

Mutation score on `EstimateCalculator`: **92%** against a 70% threshold.

Phases 0, 1, 5 and 12 have no code and are the ones most likely to be skipped. They are also
the four that decide whether any of this was worth doing.
