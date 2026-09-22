---
date: 2026-09-02
author: jianan
---

# Testing conventions

## Mutation score is the gate, coverage is the by-product

Branch coverage on changed lines sits at 90% and the PIT mutation score at 70%. The
mutation score is the one that matters.

Coverage is the most gameable number you can hand an agent: told to reach 90% it will,
with tests that call every method and assert almost nothing. A test with no assertions
kills no mutants, so the mutation threshold is very hard to fake.

## Diff coverage, not project coverage

The existing codebase sits well below 90%. A flat project gate would mean the first person
to touch a legacy module can never merge. Require the threshold on changed lines only, plus
a ratchet forbidding the overall figure from falling.

## Exclusions are mandatory

DTOs, generated code, Lombok-generated methods, configuration classes and mappers are
excluded. Without that you accumulate hundreds of tests asserting that a getter returns
what the setter set — noise that inflates the number and teaches the wrong lesson.

## Integration tests use Testcontainers, not mocks

Real Postgres and Redis per run. A mocked repository proves the mock works.

Split the loop: unit tests on every iteration, integration tests once before the pull
request opens. Integration runs are minutes, and burning them on every intermediate attempt
is where a Java feedback loop goes from slow to unusable.
