# Fleet policy origination and governance boundary

## Purpose

This document defines what belongs in Fleet for policy origination, governance, distribution, and maintenance, and how
that layer relates to the open-source pack model and compilation contract.

The goal is to keep OSS technically credible and locally useful while reserving the enterprise operating system for
policy at organizational scale.

---

## One-line boundary

**OSS owns the policy language, candidate model, local compilation, provenance, composition, and repo attachment. Fleet
  owns organizational discovery, governance, approval, distribution, drift management, and org-wide visibility.**

---

## Why this split is coherent

A single repo or team needs to be able to point miniforge at local source material and generate reviewable policy packs
without buying a hosted platform. That is what makes the pack system real.

A company-wide deployment needs much more than compilation:

- source discovery across many systems
- identity and entitlements
- review routing and approval
- version publication and rollout
- stale-pack detection
- exception handling
- dashboards and auditability across many repos

Those are governance and control-plane concerns. They belong in Fleet.

---

## What OSS must own

The following capabilities should remain in OSS because they are part of the portable pack contract and repo-local
workflow:

### 1. Pack and candidate data model

OSS defines:

- `.pack.edn` schema
- policy candidate schema
- provenance requirements
- origin types and enforceability classes
- promotion rules from candidate to pack rule
- composition and precedence semantics

### 2. Local compilation and derivation

OSS should support:

- compile from explicit files or directories
- derive from repository artifacts already present in the working tree
- compose packs locally
- emit candidate artifacts for review
- emit final pack artifacts for commit into the repo

### 3. Repo attachment

OSS defines how a repo declares applicable packs in `.miniforge/config.edn` or equivalent repo-local config.

### 4. Local review workflow

OSS should support at least a lightweight review/edit/promote flow for candidate rules, even if Fleet later offers a
richer workflow.

### 5. Provenance portability

A pack produced in OSS should remain inspectable and understandable without Fleet services.

---

## What Fleet should own

Fleet should own capabilities that require organization-wide visibility, central control, or continuous operation.

### 1. Enterprise source acquisition

Fleet connectors may include:

- Confluence
- Notion
- Google Docs / Drive
- SharePoint
- Jira
- Slack / Teams
- internal wikis and document stores
- source-control providers at org scope

Fleet owns connector configuration, auth, crawl scheduling, source metadata, and ingest observability.

### 2. Central indexing and source graph

Fleet should maintain:

- normalized source inventory
- source fingerprints and versions
- source-to-candidate lineage
- source-to-pack lineage
- links from policy packs to the repos and business units that consume them

### 3. Org-wide review and approval

Fleet should provide:

- reviewer assignment
- approval queues
- decision history
- change diffs between candidate generations
- approval policy by domain or owner
- pack publication gates

### 4. Registry and distribution

Fleet should maintain the canonical pack registry for the organization:

- published versions
- pack lifecycle state
- signed or attested releases
- rollout targeting by team, repo class, or environment
- dependency and compatibility tracking

### 5. Drift and staleness management

Fleet should continuously detect when:

- source documents changed
- repo conventions diverged from published policy
- applied packs are stale relative to source material
- policy coverage is incomplete for a repo or business unit

### 6. Exception and waiver workflow

Fleet should own:

- time-bounded waivers
- exception approval
- expiration and renewal
- business justification
- blast-radius and ownership tracking

### 7. Org-wide visibility and audit

Fleet should expose:

- where policy came from
- what packs are authoritative
- what repos consume which packs
- inferred vs directly stated rule ratios
- stale or unsupported rules
- approval and override history
- exception inventory

---

## Capability split table

| Capability | OSS | Fleet |
|---|---:|---:|
| Pack schema | Yes | Uses OSS contract |
| Candidate schema | Yes | Uses OSS contract |
| Provenance contract | Yes | Extends operationally |
| Local file-based compile | Yes | Optional wrapper |
| Repo artifact derivation | Yes | Optional wrapper |
| Pack composition | Yes | Optional wrapper |
| Repo-local pack attachment | Yes | Recommends / manages at scale |
| Lightweight candidate review | Yes | Rich workflow |
| Confluence/Notion/Drive/SharePoint connectors | No | Yes |
| Org-wide crawl scheduling | No | Yes |
| Central pack registry | No | Yes |
| Reviewer routing / approvals | No | Yes |
| Waivers / exceptions | No | Yes |
| Pack rollout across many repos | No | Yes |
| Drift detection across source systems | No | Yes |
| Compliance and coverage dashboards | No | Yes |

---

## Contract between OSS and Fleet

Fleet must treat the OSS pack and candidate model as the canonical substrate, not invent a parallel private rule model.

That implies:

1. Fleet producers must emit OSS-valid candidate artifacts.
2. Fleet publication must produce OSS-valid pack artifacts.
3. Fleet may add workflow metadata, approvals, entitlements, or signatures, but should not make the base pack unreadable
  outside Fleet.
4. A repo consuming a Fleet-published pack should still use the same repo-attachment semantics as OSS.

This is the key architectural protection against Fleet drift.

---

## Recommended Fleet terms

To avoid ambiguity, Fleet should speak in these terms:

- **source**: a document, artifact, or system record from which policy may originate
- **candidate**: normalized policy proposal with provenance
- **review**: human or governed machine step deciding candidate promotion
- **published pack**: approved canonical pack version
- **attachment**: association of a pack to a repo, team, service class, or environment
- **exception**: approved deviation from a published pack or rule
- **drift**: divergence between source material, published packs, and repo reality

---

## Fleet MVP recommendation

A reasonable first Fleet slice is:

1. connector-backed source acquisition for 2–3 systems
2. source fingerprinting and lineage store
3. candidate generation using the OSS compilation contract
4. reviewer queue and approve/reject workflow
5. canonical pack registry
6. repo recommendation or attachment visibility
7. stale-pack and changed-source indicators

That is enough to make the enterprise story real without trying to build every compliance surface at once.

---

## Anti-goals

Fleet should not:

- replace the OSS pack model with a private SaaS-only format
- make basic compilation impossible without a hosted service
- hide provenance behind opaque UI-only constructs
- couple repo runtime policy consumption to central connectivity when local pack artifacts are available

---

## Positioning note

The interesting story is not “Fleet uses AI to extract policy from docs.” The interesting story is that Fleet turns
organizational policy sprawl into governed, provenance-backed, reviewable, continuously maintained policy packs that
remain compatible with the open-source runtime.
