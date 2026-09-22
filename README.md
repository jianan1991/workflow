# Loop — AI agent delivery pipeline (POC)

Automates the path from an incoming epic to a reviewed, merged pull request using role-based
agents, with humans acting as validators rather than executors.

**Documentation lives in Google Drive**, in the `AI Agent Delivery Pipeline` folder:

- *Architecture and Sequence Diagrams — Revised* — components, the determinism constraint,
  sequence and flow diagrams, the cost model, and why not LangGraph
- *UI Screens — Design Specification* — the design system and all 14 screens
- *Build Tracker* — status, decisions log, open questions, risk register, next actions

The phase plan is the exception: it lives in [PHASES.md](PHASES.md) here, because its exit
checks are commands you run against this repository and they have to be ticked where the
code is.

This repository holds only code and the configuration needed to run it. Keep it that way:
status in one place, knowledge in another.

---

## What is here

```
PHASES.md              The build plan and its exit checks. Tick them as you go.
docker-compose.yml     Qdrant, Temporal (+ Postgres), Temporal UI, LiteLLM
docker-compose.openhands.yml   The dev agent. Separate file - it mounts the Docker socket.
openhands/             Dev agent config. Points at the proxy, never at Anthropic directly.
litellm/config.yaml    Model tiering — strong / mid / cheap, one place to change it
cost-model.properties  Unit costs and the rework multiplier. Phase 1 replaces the guesses.
knowledge-base/        Plain markdown, dated and attributed. Indexed into Qdrant.
baseline/              Phase 0 measurements, including the hand-kept review-hours tally.
orchestrator/          Spring Boot + Temporal worker. The only stateful component.
scripts/verify.sh      Runs the suites, and says plainly which ones did not run.
scripts/baseline.py    Pulls cycle time and throughput from the GitHub API.
```

Docker state lives in **named volumes**, never bind mounts. This repository sits under
OneDrive, and a file sync client touching a live database directory corrupts it slowly, in
a way that looks like a bug in your own code.

All six agents are wired and run on every epic. Five are single model calls behind
`ChatModel`; the dev agent is a multi-turn coding loop, so it sits behind its own `DevAgent`
seam and dispatches to OpenHands headless.

`runCi` is still a stub and reports coverage and mutation as *unmeasured* rather than
returning a pass — faking the mutation gate would remove the one check an agent cannot talk
its way past.

Not here yet, in build order: the scip-java code index, gate 2 (the architect flags
structural changes and the workflow ignores it), and enforced CI gates.

**Nothing has called a real model or written a line of code yet.** `llm.provider` and
`dev.provider` both default to `scripted`, deliberately: a pipeline that starts editing a
repository because somebody ran it to see what it did is a worse failure than one that does
nothing.

## Testing

```bash
./scripts/verify.sh           # fast suite: unit + workflow. ~9s
./scripts/verify.sh --all     # adds integration tests. Needs Docker.
./scripts/verify.sh --gates   # adds coverage and mutation. Phase 5 onward.
```

The fast suite is 153 tests in about ten seconds with **no Docker and no API key**. That is
deliberate: the moment `mvn test` needs a container, people stop running it. Integration
tests are named `*IT.java` so surefire ignores them and failsafe picks them up.

A skipped suite is reported as skipped, never as passed — `verify.sh` exits non-zero if
Docker is down rather than pretending the integration tests were fine.

The offline path is why retrieval is testable at all: a hashing embedder and an in-memory
vector store stand in for the real ones. They prove the plumbing, not retrieval quality.
Set `knowledge.embedder=litellm` before drawing any conclusion about whether agents can
find what they need.

**[PHASES.md](PHASES.md) is the tracked plan** — twelve phases, each with exit checks to
tick and a condition that means stop. Do not start a phase until the previous one's checks
all pass, and append to the verification log at the bottom whenever you run them.

---

## The one rule that will bite you

**Temporal workflow code must be deterministic.** It is replayed from history on every
recovery, so anything non-deterministic lives in an *activity*, never in the workflow method.

- Workflow code: sequencing, `Workflow.await` on a human signal, retry and escalation policy,
  rework counters.
- Activities: every LLM call, every Qdrant or code-index query, OpenHands dispatch, all
  GitHub and CI API calls.

Get this wrong and replays produce garbage that is very hard to debug.

---

## Running it

Prerequisites: Docker and Docker Compose, JDK 21, Maven.

```bash
cp .env.example .env
# edit .env — at minimum set LLM_API_KEY
docker compose up -d qdrant temporal temporal-ui
```

Check the pieces are alive:

```bash
curl http://localhost:6333/collections      # Qdrant — expect an empty list
open http://localhost:8080                  # Temporal UI
```

Create the knowledge collection. Set `size` to your embedding model's dimensions —
1536 is typical for hosted models, 768–1024 for most open ones.

```bash
curl -X PUT http://localhost:6333/collections/knowledge \
  -H 'Content-Type: application/json' \
  -d '{"vectors": {"size": 1536, "distance": "Cosine"}}'
```

Then start the worker:

```bash
cd orchestrator && mvn spring-boot:run
```

### Drive one epic by hand

No GitHub wiring yet — the HTTP endpoints stand in for it, which is the point of this stage.

```bash
curl -X POST http://localhost:8081/epics \
  -H 'Content-Type: application/json' \
  -d '{"id":"LOOP-14","title":"Per-tenant API rate limiting","body":"Developers on the Starter plan can exhaust the API."}'
```

```bash
curl http://localhost:8081/epics/LOOP-14
```

The PM agent maps the journey, finds a gap, and raises a question — recording the searches
it ran against the knowledge base first. The workflow then blocks. Watch it sleep in the
Temporal UI; that is the behaviour the whole design rests on. Release it:

```bash
curl -X POST http://localhost:8081/epics/LOOP-14/answer \
  -H 'Content-Type: application/json' \
  -d '{"questionId":"Q-1","answer":"Self-serve, with approval above 250 req/min."}'
```

```bash
curl -X POST http://localhost:8081/epics/LOOP-14/approve \
  -H 'Content-Type: application/json' \
  -d '{"gate":"EPIC_PLAN","approved":true}'
```

The epic then fans out into one child workflow per story, in dependency order, with
independent stories running together. Tier 1 stories auto-approve and merge without you.
Tier 2 stories go round the rework loop and then stop at gate 3:

```bash
curl -X POST http://localhost:8081/epics/LOOP-14/stories/LOOP-14-3/approve \
  -H 'Content-Type: application/json' \
  -d '{"gate":"PULL_REQUEST","approved":true}'
```

Kill the worker while anything is blocked and start it again. Every workflow resumes where
it was. If that does not work, nothing built on top of it will either.

### Starting the dev agent

The dev agent lives in its own compose file, because it mounts the host Docker socket to
spawn the sandbox where it runs builds and tests — effectively root on this machine.

```bash
cp openhands/config.toml.example openhands/config.toml
docker compose -f docker-compose.openhands.yml up -d
```

It reaches Claude through the LiteLLM proxy, never directly: the model in `config.toml` is
`litellm_proxy/cheap`, which the proxy resolves to a real model. The Anthropic key lives
only in LiteLLM, so the agent never holds it.

Nothing dispatches to it from Temporal yet — that is the next piece of work.

### What a run demonstrates

Watch the log, not just the result:

```
[pm]        maps the journey, finds a gap, blocks
[pm]        3 of 3 searches used, nothing found
[architect] decomposes, flags structural
[ba]        writes criteria per story
gate 1      12 criteria automated, 4 need a person
```

**Nothing implements the stories yet.** The dev agent is unwired, so QA has no observations
and returns UNVERIFIABLE rather than reporting green — which is the behaviour that keeps an
unfinished pipeline from looking finished.

To see the rework loop itself, run `StoryWorkflowTest`: it uses `StubBuildActivities`, whose
failure pattern is deliberate. A tier 2 story fails its mutation gate, gets rebuilt, fails QA
with an empty 429 body, gets rebuilt again, and passes — **and no person is involved in any
of it**. Those loops are the only reason this pipeline is worth having.

The three QA routes are all exercised there:

| Outcome | Goes to | Why |
| :-- | :-- | :-- |
| Code defect | the dev agent | the automated loop, where the leverage is |
| Criterion wrong | the PM | sending it to a developer produces a second wrong implementation |
| Cannot verify | a person's checklist | reclassify the criterion, do not retry the code |

To see the route back to the PM, prefix a story title with `[criteria-gap]` — the stub QA
agent then reports that the code met the criteria and the criteria were wrong.

A story with **no** acceptance criteria is blocked rather than built. Building it would pass
review against nothing and reach a person looking finished.

---

## Order of work

Do not stand everything up at once. From the Build Tracker, in order:

1. Start the review-hours tally. This is greenfield, so there is no history to measure
   against — the trend is the only evidence there will ever be, and it needs a first data
   point taken before the agents start.
2. Price one epic by hand at current model rates.
3. Qdrant, then prove retrieval quality by hand before any agent touches it:

   ```bash
   curl -X POST http://localhost:8081/knowledge/reindex
   curl "http://localhost:8081/knowledge/search?q=how+do+rate+limits+key"
   ```

   Run a dozen real questions and read the answers yourself. An agent with bad retrieval
   does not error — it invents, and everything downstream inherits that silently.

4. **Temporal with stub agents — done. You are here.**
5. CI gates. Verify by hand: write a deliberately assertion-free test, confirm coverage passes
   and mutation testing fails. If mutation does not fail, the gate protects nothing.
6. Real PM agent, journey mapping only. No story generation.
7. Architect, then the first dev agent on the simplest tier-1 story.

---

## Security note before agents get credentials

OpenHands needs `/var/run/docker.sock` to spawn its sandbox, which is effectively root on the
host. Combined with prompt injection through ticket content, a steered agent with that socket
owns the machine.

- Run the agent runner under rootless Docker or Sysbox.
- Keep the socket out of every other container.
- Scope repository credentials to the minimum, use short-lived tokens, and keep anything
  production-facing out of reach.

Settle this before any agent gets write access.

---

## Version pins

Image tags in `docker-compose.yml` are pinned deliberately. Check them against the upstream
release notes before bumping — the Temporal auto-setup image in particular changes its schema
setup between minor versions.
