---
date: 2026-08-04
author: jianan
---

# Tenant isolation

## Every query is scoped

Every read path filters on tenant id at the repository layer, never in the controller and
never by convention. A query that cannot name its tenant is a bug, not a special case.

Admin endpoints are not an exception. An administrator is scoped to their own tenant; the
thing people usually mean by "admin sees everything" is a support tool, and we do not have
one.

## Export and reporting paths

Bulk reads share the same rule and it is easy to lose. The reporting module and the audit
export both filter through the same query path, so a change to one silently changes the
other — treat anything touching it as cross-cutting rather than mechanical.

## What happens on a missing tenant

Reject the request. Do not fall back to a default tenant and do not return an empty result
set: an empty list looks like "you have no data" to a caller and hides a real failure until
someone notices a report is wrong weeks later.
