#!/usr/bin/env python3
"""
Phase 0 — measure how the team works today, before any agent touches the repository.

    export GITHUB_TOKEN=ghp_...
    python scripts/baseline.py --repo owner/name --days 14

Writes baseline/pull-requests.csv and prints the summary.

WHAT THIS CAN DERIVE
    Cycle time, throughput, review latency, review load per person. All of it comes from
    timestamps GitHub already records, so it is reproducible and nobody has to remember
    anything.

WHAT IT CANNOT DERIVE, AND WHY IT MATTERS
    Review HOURS. GitHub records when a review was submitted, never how long it took. That
    number is the one that decides whether this whole project saves anything — reviewing
    code you did not write is slower per line than writing it, and every agent PR funnels
    through the same three people.

    It has to be tallied by hand. See baseline/review-hours.csv.

    Do not substitute review latency for review hours. A PR that sat for six hours and took
    four minutes to read is not six hours of anybody's attention, and treating it as such
    will make the pipeline look like it saved time it never cost.
"""

import argparse
import csv
import json
import os
import statistics
import sys
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

API = "https://api.github.com"


def iso(ts):
    return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def get(url, token, params=None):
    if params:
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
    request = urllib.request.Request(url)
    request.add_header("Accept", "application/vnd.github+json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 401:
            sys.exit("GITHUB_TOKEN was rejected. A token with `repo` scope is needed for "
                     "private repositories.")
        if e.code == 404:
            sys.exit(f"Not found: {url}\nCheck the --repo value and the token's access.")
        if e.code == 403:
            sys.exit("Rate limited or forbidden. Set GITHUB_TOKEN, or wait.")
        raise


def collect(repo, days, token):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows, page = [], 1

    while True:
        batch = get(f"{API}/repos/{repo}/pulls", token,
                    {"state": "closed", "per_page": 100, "page": page,
                     "sort": "updated", "direction": "desc"})
        if not batch:
            break

        stop = False
        for pr in batch:
            if not pr.get("merged_at"):
                continue
            merged = iso(pr["merged_at"])
            if merged < since:
                stop = True
                continue

            reviews = get(f"{API}/repos/{repo}/pulls/{pr['number']}/reviews", token)
            reviewers = [r["user"]["login"] for r in reviews
                         if r.get("user") and r["user"]["login"] != pr["user"]["login"]]
            first_review = min((iso(r["submitted_at"]) for r in reviews
                                if r.get("submitted_at")), default=None)

            created = iso(pr["created_at"])
            rows.append({
                "number": pr["number"],
                "title": pr["title"][:80],
                "author": pr["user"]["login"],
                "created_at": pr["created_at"],
                "merged_at": pr["merged_at"],
                "hours_open_to_merge": round((merged - created).total_seconds() / 3600, 2),
                "hours_to_first_review":
                    round((first_review - created).total_seconds() / 3600, 2)
                    if first_review else "",
                "review_count": len(reviews),
                "distinct_reviewers": len(set(reviewers)),
                "reviewers": ";".join(sorted(set(reviewers))),
            })

        if stop or len(batch) < 100:
            break
        page += 1

    return rows, since


def summarise(rows, days, since):
    if not rows:
        print("\nNo merged pull requests in the window. Widen --days, or check the repo.")
        return

    cycle = sorted(r["hours_open_to_merge"] for r in rows)
    latency = sorted(r["hours_to_first_review"] for r in rows
                     if r["hours_to_first_review"] != "")

    per_author = Counter(r["author"] for r in rows)
    per_reviewer = Counter()
    for r in rows:
        for who in filter(None, r["reviewers"].split(";")):
            per_reviewer[who] += 1

    weeks = days / 7
    unreviewed = sum(1 for r in rows if r["distinct_reviewers"] == 0)

    print(f"\n{'=' * 68}")
    print(f"BASELINE — {len(rows)} merged PRs since {since:%Y-%m-%d}  ({days} days)")
    print("=" * 68)

    print("\nCYCLE TIME — pull request opened to merged")
    print(f"  median          {statistics.median(cycle):8.1f} h   ({statistics.median(cycle)/24:.1f} days)")
    print(f"  p90             {cycle[int(len(cycle) * 0.9) - 1]:8.1f} h")
    print(f"  longest         {cycle[-1]:8.1f} h")

    if latency:
        print("\nREVIEW LATENCY — opened to first review submitted")
        print(f"  median          {statistics.median(latency):8.1f} h")
        print("  NOT review hours. See the note at the top of this script.")

    print(f"\nTHROUGHPUT — over {weeks:.1f} weeks")
    print(f"  merged per week {len(rows) / weeks:8.1f}")
    for author, count in per_author.most_common():
        print(f"    {author:<24} {count:3d} merged   {count / weeks:5.1f}/week")

    print("\nREVIEW LOAD — reviews submitted on other people's PRs")
    if per_reviewer:
        for reviewer, count in per_reviewer.most_common():
            print(f"    {reviewer:<24} {count:3d} reviews  {count / weeks:5.1f}/week")
    else:
        print("    none recorded")

    if unreviewed:
        share = 100 * unreviewed / len(rows)
        print(f"\n  {unreviewed} of {len(rows)} merged with no review at all ({share:.0f}%).")
        print("  Worth knowing before the pipeline adds a reviewer agent — if this is high,")
        print("  the team's real bar is lower than the process says, and agent PRs will")
        print("  raise the review burden more than these numbers suggest.")

    print(f"\n{'=' * 68}")
    print("STILL MISSING — the number that decides the whole question")
    print("=" * 68)
    print("  Review HOURS per engineer per week cannot be derived from any API.")
    print("  Tally it by hand for two weeks in baseline/review-hours.csv.")
    print("  Without it, the POC ends in a feeling rather than an answer.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True, metavar="owner/name")
    parser.add_argument("--days", type=int, default=14,
                        help="window in days (default 14, which is the phase 0 requirement)")
    parser.add_argument("--out", default="baseline/pull-requests.csv")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("warning: GITHUB_TOKEN not set. Public repositories only, and rate limits "
              "will bite quickly.\n", file=sys.stderr)

    rows, since = collect(args.repo, args.days, token)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if rows:
        with out.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"wrote {len(rows)} rows to {out}")

    summarise(rows, args.days, since)


if __name__ == "__main__":
    main()
