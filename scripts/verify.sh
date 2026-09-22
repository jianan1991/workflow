#!/usr/bin/env bash
#
# Runs the suites PHASES.md refers to, and says plainly which ones did not run.
#
#   ./scripts/verify.sh           fast suite: unit + workflow. No Docker, no keys.
#   ./scripts/verify.sh --all     fast + integration. Needs Docker.
#   ./scripts/verify.sh --gates   everything, including coverage and mutation.
#
# A suite that is skipped is reported as skipped, never as passed. Half the value of a
# check is knowing when it did not happen.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ORCHESTRATOR="orchestrator"

MODE="fast"
case "${1:-}" in
  --all)   MODE="all" ;;
  --gates) MODE="gates" ;;
  "")      ;;
  *)       echo "unknown option: $1"; echo "use --all or --gates"; exit 2 ;;
esac

docker_is_up() {
  docker ps >/dev/null 2>&1
}

echo "==> fast suite (unit + workflow)"
if ! (cd "$ORCHESTRATOR" && mvn -q test); then
  echo
  echo "FAIL: the fast suite is red. Fix this before anything else — it needs no"
  echo "      container and no key, so a failure here is a real defect."
  exit 1
fi
echo "    passed"

if [ "$MODE" = "fast" ]; then
  echo
  echo "SKIPPED: integration tests. Re-run with --all before ticking any exit check"
  echo "         in PHASES.md, and at least weekly regardless."
  exit 0
fi

echo
echo "==> integration suite"
if ! docker_is_up; then
  echo
  echo "SKIPPED: the Docker daemon is not reachable, so no integration test ran."
  echo "         This is NOT a pass. Start Docker and run again."
  exit 3
fi

GOAL="verify"
[ "$MODE" = "gates" ] && GOAL="verify -Pgates"

if ! (cd "$ORCHESTRATOR" && mvn -q $GOAL); then
  echo
  if [ "$MODE" = "gates" ]; then
    echo "FAIL: integration or the CI gates are red."
    echo "      If it is the mutation threshold, read the report before lowering it —"
    echo "      that gate is the only one an agent cannot fake."
  else
    echo "FAIL: integration tests are red."
  fi
  exit 1
fi

echo "    passed"
echo
echo "All suites green. Record it in the verification log at the bottom of PHASES.md."
