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
| 0 · Baseline | ◐ greenfield — no history to measure, forward tally only | 2026-09-22 |
| 1 · Cost of one epic | ◐ costs derived from real prices, token counts still estimates | 2026-09-22 |
| 2 · Infrastructure | ☑ **done** | 2026-09-22 |
| 3 · Retrieval | ◐ automated + integration green, manual pass outstanding | 2026-09-22 |
| 4 · Orchestration, stub agents | ☑ **done** | 2026-09-22 |
| 5 · CI gates | ◐ gate proven, CI wiring outstanding | 2026-09-22 |
| 6 · PM agent | ◐ wired and running; needs a key to judge | 2026-09-22 |
| 7 · Architect | ◐ wired and running; needs a key to judge | 2026-09-22 |
| 8 · First dev agent | ◐ dispatch written, never executed | 2026-09-22 |
| 9 · Reviewer and QA | ◐ wired and running; needs a key to judge | 2026-09-22 |
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

### The scripted model, and the line it must not cross

The fake sits at the **model** boundary, not the agent boundary:

```
PmAgent / BaAgent / ArchitectAgent / ReviewerAgent / QaAgent    real code
  ↓
ChatModel                                                       ← the seam
  ↓
ScriptedChatModel  |  LiteLlmChatModel
```

`llm.provider=scripted` is the default. No key, no network, no cost, and the whole pipeline
runs end to end.

**What it proves:** prompt assembly, retrieval reaching the prompt, parsing, every way a
reply can be malformed, routing decisions, completeness checks, token usage reaching the
cost path.

**What it cannot prove:** whether any agent's answers are good. Not partially — not at all.
A green suite here and a working agent are unrelated claims.

This is why phases 6 to 9 stay ◐ with every automated box ticked. Writing the agent is not
the phase; judging its output is, and that needs a key and a person.

### What the pipeline does today

Five of six planning and checking agents are wired and run on every epic. Verified against a
real Temporal server, scripted model, no key:

```
[pm]        maps the journey, finds a gap, blocks
[pm]        raises a question — 3 of 3 searches used, nothing found
            (a person answers)
[pm]        re-maps: 0 gaps
[architect] decomposes into 4 stories, flags structural
[ba]        writes criteria per story — 4 each, 3 automated
[estimate]  $10.67 and 99 human minutes
gate 1      12 criteria automated, 4 need a person
            $10.67 estimated; actual accrues per activity as stories run
[mid]       SCRIPTED dev agent: no code was written
            $0.4236 per story — the dev agent dominates, as the estimate says it should
```

**The dev agent is the one that is not wired.** `implement()` and `runCi()` are honestly
stubbed: neither is a model call, one needs a coding harness with a sandbox and the other
needs the build to actually run. `runCi` reports coverage and mutation as *unmeasured*
rather than returning a pass — faking the mutation gate would remove the only check an agent
cannot talk its way past.

**Two invariants worth not losing.** A story with no acceptance criteria is blocked rather
than built, because building it would pass review against nothing and reach a person looking
finished. And QA with no observations returns UNVERIFIABLE for everything rather than
inferring outcomes from the criteria — which is what stops a pipeline that executes nothing
from reporting green.

---

## Phase 0 — Baseline

**Decided 2026-09-22: this is a greenfield project.** There is no prior repository, no
merged pull requests, no cycle time. The before-and-after comparison the original plan
assumed cannot be made, and pretending otherwise would be worse than admitting it.

**What this costs you.** Screen 12 ("is this working?") loses its comparison, and the
question *did the pipeline save time* becomes unanswerable for this project. That was the
falsifiability argument in the original plan, and it is now gone. Accept it deliberately
rather than discovering it in three months.

**What is still worth measuring, from today forward**

- [x] Decision recorded: no historical baseline exists
- [ ] Review-hours tally started in `baseline/review-hours.csv` — **from today, going
      forward**. It is the only number that will tell you whether reviewing machine output
      is sustainable, and it works without a before-picture
- [ ] Cycle time recorded per epic from the pipeline's own data once epics run for real
- [ ] `scripts/baseline.py` kept for the day this points at a repository with history —
      it is written and tested, just not applicable yet

**Stop if:** the review-hours tally is not started before the first real agent runs. Without
a before-picture, the *trend* in review hours is the only evidence you will ever have, and a
trend needs a first data point taken before the thing you are measuring changes.

**The substitute for a baseline.** Since there is nothing to compare against, the POC has to
be judged on absolute numbers instead: review hours per week, rework loops per story,
questions per epic, and cost per merged story. All four are already instrumented. Decide the
thresholds you would walk away at *now*, while nobody is invested in the answer.

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

- [x] `mvn test` — `EpicWorkflowTest` (8) and `StoryWorkflowTest` (10) green
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

**The restart check needs running again.** It passed, and it still missed a bug in the thing
it was built to prove: question ids came from a counter on an activity instance, so the
second worker restarted the numbering, and an answer to one question could release a
different one. The check only ever asked one question. Re-run it with two gaps in the
journey — `StubPlanningActivities.SECOND_GAP_MARKER` produces them — and confirm the ids
still read `<epic>-Q1` and `<epic>-Q2` after the worker is replaced.

**Cost reconciliation is now wired.** Every activity reports what its own call consumed and
the workflow sums — dev, reviewer and QA, not QA alone, which was under-reporting a story by
most of its cost. `CostingChatModel` prices each call from the tokens actually used, against
the same `cost-model.properties` that produces the gate-1 estimate, so estimate and actual
are denominated in the same units.

It prices the scripted model too, deliberately: a free run reports what it **would** have
cost, so the arithmetic is exercised on every test run instead of first meeting reality on an
invoice.

The figure is still not a measurement of anything real, because nothing implements stories
yet and the dev agent is the largest line on the bill. What is true now is that the number
comes from tokens rather than from a constant.

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

- [x] `PmAgent` written - prompt assembly, retrieval, parsing, validation
- [x] 12 tests scripted: the prompt carries the epic, the retrieved notes dated and
      attributed, and human decisions last and marked settled; fences stripped; four ways a
      reply can be unusable
- [x] An unreadable `coverage` is rejected, never defaulted to KNOWN - defaulting would
      release an epic to the architect with an open question inside it
- [x] **Wired into the pipeline** — `AgentPlanningActivities` calls it, verified running
- [x] `raiseQuestion` searches before interrupting anyone: the agent proposes phrasings,
      they go through the retrieval attempt cap, and the queries that failed are recorded
      on the question
- [ ] **Run against real epics with a real model** (`llm.provider=litellm`)
- [ ] Its gap questions include ones a person would genuinely have asked and nobody did
- [ ] No question asks about something plainly stated in the epic
- [ ] Cost per run recorded and compared against the $0.09 the estimator assumes

**Greenfield changes this check.** The original plan said "five epics that already
shipped", so you would know the right answer in advance. There are none. The substitute:
write three epics from real work you have done before, deliberately leaving out something
you remember being bitten by, and see whether the agent asks about it.

**Stop if:** its questions are generic ("what are the requirements?"). That is a prompt or
retrieval problem, and shipping it means every downstream agent inherits invented
requirements.

**Do not substitute live epics for shipped ones.** You need the right answer in advance, or
you are grading an exam you have not marked.

---

## Phase 7 — The architect

**Delivers:** decomposition, tier assignment, the dependency graph.

**Exit checks**

- [x] `ArchitectAgent` written, with graph validation at the source
- [x] 13 tests scripted, including four ways a graph would deadlock the build: dangling
      references, self-dependency, cycles, duplicate ids. None of those fail loudly
      downstream - the fan-out simply never schedules them and the epic looks busy
- [x] One test runs the architect's real output through `EpicWorkflowImpl.dependencyLayers`
      to prove what it produces is schedulable
- [x] An out-of-range or missing tier is rejected, never defaulted to 1
- [x] **Wired into the pipeline** — decomposition runs through it on every epic
- [ ] Decompose real epics with a real model; compare against what you would have built
- [ ] Tier assignments match a person's judgement on at least 70% of stories
- [ ] The graph is derived from a code index, not from the model's reading of the epic

**Known shortcut, deliberately visible.** No code index exists, so the agent *guesses* the
dependency graph and validation catches only structural mistakes, not semantic ones. Track
how often an agent discovers mid-story that it needs something from a later story - that
number is the cost of this shortcut.

**Stop if:** dependencies are invented or missed. The compiler knows what imports what with
certainty — paying a model to guess at it is slower and wrong more often.

---

## Phase 8 — The first dev agent, one tier 1 story

**Delivers:** the first real pull request.

**Exit checks**

- [x] `DevAgent` seam written — the one agent that is not a single model call, so it sits
      behind a harness rather than behind `ChatModel`
- [x] `OpenHandsDevAgent` dispatches `openhands --headless --json -f task.txt`, with the
      process working directory set to the checkout (the CLI documents no workspace flag)
- [x] `ScriptedDevAgent` is the default and writes no code, so a pipeline nobody configured
      cannot quietly start editing a repository
- [x] The task names the specific previous failure, so attempt two is a correction rather
      than a retry, and warns about the two things that bounce a PR: a test that asserts
      nothing, and scope creep
- [x] `dev.provider=openhands` with no `dev.workspace` **refuses to start** — verified
- [x] 17 tests, including a missing workspace, a missing executable, and usage parsing that
      reports zero rather than inventing a number
- [ ] **Run against a real repository.** Blocked on Q1 (which repo) and a key
- [ ] A PR opens and CI passes, including the mutation gate
- [ ] A person would merge it without changes
- [ ] Fewer than three attempts
- [ ] Wall-clock per attempt recorded

**Never executed.** The invocation comes from docs.openhands.dev on 2026-09-22 and has not
been run once. The JSONL event schema is read defensively for that reason — fields that are
not found report zero *and say so*, because a token count silently becoming zero is
indistinguishable from a free run. `describe()` returns the word UNVERIFIED so a component
nobody has executed does not look like one that works.

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

- [x] `ReviewerAgent` and `QaAgent` written; 26 tests scripted between them
- [x] QA is **structurally incapable** of seeing the dev agent's tests - it judges from
      observations captured by running the software, and a test asserts the prompt never
      contains them
- [x] QA must answer **every** criterion it is given. A missing verdict is rejected: an
      unchecked criterion would merge looking exactly like a passing one
- [x] Routing order pinned by test - a wrong criterion outranks a code defect, because
      rebuilding against a requirement nobody wanted changes nothing
- [x] A reviewer reply that contradicts itself is rejected. Claiming the criteria are met
      while listing unmet ones would otherwise merge on the optimistic half
- [x] **Wired into the pipeline**, and judging against **real BA criteria** rather than
      placeholders invented from the story title
- [x] A same-family reviewer is warned about at startup and served from
      `/policy/reviewer-independence` — 409 when not independent
- [ ] Inject the four known defects with a real model; confirm the pattern above holds
- [ ] The reviewer runs on a different model family from the authoring agent

**Currently unmet, and it is the last one.** Every tier in `cost-model.properties` resolves
to an Anthropic model, so reviewer and author share a family and the self-agreement
mitigation is weakened. Either point `tier.review` at a non-Anthropic model through LiteLLM,
or accept the weaker mitigation knowingly and lean harder on the auto-approval revert
counter.

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
| 2026-09-22 | 6, 7, 9 | agents wired + end-to-end run | **132 fast + 4 integration green.** Agents now called by the pipeline, not merely beaned. Three bugs found by running the application rather than by tests: the Qdrant collection was never created, the PM script could not tell its two calls apart, and the scripted QA reported passes when nothing had run. |
| 2026-09-22 | 8 | dev agent dispatch + end-to-end run | **153 fast + 4 integration green.** OpenHands dispatch written behind a `DevAgent` seam, scripted by default. Verified that `dev.provider=openhands` with no workspace refuses to start. Story cost went from $0.0036 to $0.4236 once the dev agent contributed — the shape the estimate predicts. The dispatch itself has never run. |
| 2026-09-22 | 4 | cost reconciliation + end-to-end run | **140 fast + 4 integration green.** Spend now comes from real token usage, summed across dev, reviewer and QA. A run reported $0.0036 per story where it had reported $0.0000 — the tracking was working and `round2` was rounding sub-cent spend away, which reads exactly like cost tracking that was never wired. |
| 2026-09-22 | 9 | BA criteria threaded through | The reviewer and QA were judging against criteria invented from the story title. Now per story, carried on the child workflow. Gate 1 reports the real testability split: 12 automated, 4 needing a person. |
| 2026-09-22 | 6, 7, 9 | `./scripts/verify.sh --all` | **112 fast + 4 integration green.** PM, BA, architect, reviewer and QA agents written and tested against a scripted model. Every automated box in those phases is ticked; none of the phases are done, because judging output needs a key and a person. |
| 2026-10-08 | 4, 8 | four more bugs fixed + `mvn test` | **172 fast green.** The first was the same bug as the session before, one level up: a child story workflow that failed outright threw out of `Promise.allOf` and failed the epic. Proved with a probe before fixing it — the epic ended FAILED, its own status query went on answering BUILDING, and `storyStatuses` held **zero** records even though a sibling in the same layer had already merged. Also: no connect or read timeout on any of the three outbound HTTP clients, which Temporal cannot save because it times out the activity without interrupting the socket read; a data race on the OpenHands output list guarded only by a `join(5s)` that returns whether or not the reader finished; and a dev run killed at the timeout booking as /usr/bin/bash.00 — the most expensive outcome in the system recorded as free. |
| 2026-10-08 | 4, 5, 8, 9 | four confirmed bugs fixed + `mvn test` + `mvn verify -Pgates` | **165 fast green.** All four were in the join between the workflow and the agent activities, where every component was correct on its own: a thrown dev failure escaped the escalation ladder and failed the sibling stories, question ids came from a counter on an activity instance and reset on restart, auto-approval read an unwired CI as a pass, and the dev agent was handed no acceptance criteria. A fifth fell out of fixing the third: an unmeasured CI result is not a failure to fix, and treating it as one sent every story round the rework loop six times at full price. Project branch coverage 0.69 — 0.73, both short of the 0.90 rule — see the gaps table. |
| 2026-09-22 | 1 | `AgentCostModel` + `mvn test` | Unit costs now **derived** from Anthropic list prices x per-agent tokens, with tier→model mapping. Six-story epic: **$12.02**. 49 tests green. Token counts are still estimates — that is what remains before phase 1 closes. |

---

## What is currently green

| Suite | Tests | Covers |
| :-- | --: | :-- |
| `PmAgentTest` | 12 | Prompt assembly, fence stripping, four unusable replies, token usage |
| `BaAgentTest` | 14 | Testability classification, the unanswerable path, citations, the split |
| `ArchitectAgentTest` | 13 | Tier and graph parsing, four deadlocking graphs, schedulability |
| `ReviewerAgentTest` | 11 | Scope creep, self-contradiction, missing booleans and verdicts |
| `QaAgentTest` | 15 | Routing order, completeness, never seeing the dev agent's tests |
| `AgentActivitiesTest` | 15 | That the agents are actually reached — asserts on the model being called, not only on values returned. Plus the gate: an unmeasured or zero-mutation CI result cannot auto-approve, and the dev agent is handed the real criteria |
| `ReviewerIndependenceTest` | 12 | Family attribution, uncertainty resolving toward warning |
| `CostingChatModelTest` | 6 | Pricing per tier, scripted runs priced, unpriced agents not silent |
| `DevAgentTest` | 17 | Task assembly, the previous-failure correction, workspace and executable guards, usage parsing, and the spend reported by a run that timed out or exited non-zero |
| `EstimateCalculatorTest` | 13 | The worked example, which stages loop, tier cost, designer only with a UI, human minutes, four rejections |
| `MarkdownChunkerTest` | 6 | Section splitting, frontmatter, stable ids, folding short sections |
| `RetrievalTest` | 8 | Search accuracy, attribution, re-indexing, the attempt cap, failure modes |
| `EpicWorkflowTest` | 8 | Journey blocking and resume, abandonment, rejected gate, dependency layers, two questions getting distinct epic-scoped ids, a story workflow dying without taking the epic or its siblings with it |
| `StoryWorkflowTest` | 10 | Auto-approve, the rework loop, the PM route, an unreviewed PR, a broken dev harness blocking rather than crashing, a retryable dev failure reworking, an unmeasured CI going to a person |
| `AgentWiringWorkflowTest` | 1 | A whole epic on the **real** activities with no key and no container — the join where every bug so far has lived |
| `QdrantVectorStoreIT` | 4 | Store parity, payload round trip, upsert replacing, idempotent setup |
| `LiteLlmChatModelTest` | 2 | A proxy that accepts and never answers fails fast against a real socket, and an unmapped agent is refused before any call |
| **Fast suite** | **172** | No Docker, no API key, about 20 seconds |

Mutation score on `EstimateCalculator`: **92%** against a 70% threshold.

Phases 0, 1, 5 and 12 have no code and are the ones most likely to be skipped. They are also
the four that decide whether any of this was worth doing.

### Known gaps, none of them hidden

| Gap | Where it is recorded |
| :-- | :-- |
| No agent has ever called a real model | every phase from 6 on stays ◐ |
| The dev agent dispatch has never been executed against a real repository | phase 8; `describe()` says UNVERIFIED |
| `runCi` is still a stub — it reports `measured = false`, which cannot pass and cannot auto-approve | phase 5, and said plainly in `AgentBuildActivities` |
| Project branch coverage is **0.73** against the 0.90 rule, so `mvn verify -Pgates` is red | phase 5. The rule is meant to be a diff-coverage check against the merge base, which needs a pipeline that does not exist |
| The reviewer shares a model family with the author | phase 9, warned at startup |
| The architect guesses the dependency graph; no code index | phase 7 |
| Gate 2 never fires — the architect flags structural changes and the workflow ignores it | logged as a warning on every structural epic |
| No CI pipeline, so the gates are not enforced anywhere | phase 5 |
| No deploy or rollback story | phase 12, still the largest undesigned gap |
| The review-hours tally has not started | phase 0's stop condition, and it expires once agents run |

**Three bugs in two sessions were found by running the application, not by the suite** — a
tier name where a model id belonged, a missing Qdrant collection, and a scripted QA that
reported passes on a run that never happened. Every one had green tests. Treat a green suite
as necessary and nowhere near sufficient.

**Eight more were found by reading the code rather than by running it.** Four were in the
seam between the workflow and the agent activities; the next four were the same seam one
level up, plus the transport underneath it — a failing child workflow, missing HTTP
timeouts, an unsynchronised cross-thread read, and spend thrown away on the most expensive
path there is. In every case each component was right on its own; what they handed each
other, or what they did when something failed, was not, and no test looked there.
`AgentWiringWorkflowTest` exists for exactly that: one epic, the real activities, no stubs,
no key, no container. It is not a substitute for starting the application, but it is the
cheapest thing that watches the join.
