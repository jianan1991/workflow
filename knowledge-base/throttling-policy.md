---
date: 2026-09-18
author: jianan
---

# Throttling policy

## Which identifier the limiter keys on

Rate limits key on **tenant ID**, not on API key.

Several keys may belong to one tenant and they share a single bucket. A tenant must not be
able to buy more headroom by minting extra keys.

Decided 2026-09-18 · supersedes nothing.

## Algorithm

A token bucket, not a fixed window. Fixed windows let a caller burst to twice the limit
across the boundary between two windows, which shows up as a plausible-looking incident
nobody can reproduce.

## Where the limiter sits

A servlet filter rather than a `HandlerInterceptor`. The filter rejects before handler
resolution, which saves a lookup on every throttled request — and throttled requests are
exactly the ones you do not want to spend work on.

## Plan ceilings

Starter is 100 requests per minute. Anything above 250 needs internal approval, which
usually takes about a day. Ceilings apply without a redeploy: the limiter reads them from
tenant configuration on each refill rather than caching them at startup.
