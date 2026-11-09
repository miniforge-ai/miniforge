<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(specs): N11-delta-2 capsule egress control

## Overview

Adds `specs/normative/N11-delta-2-egress-control.md`, a draft amendment to N11 and the N11 runtime-adapter
delta, and indexes it in `specs/SPEC_INDEX.md`. Spec only; no code changes.

## Motivation

A 2026-11-08 review of PAODO-Workspaces against miniforge found egress control the largest gap. Governed
capsules are hardened (`--cap-drop=ALL`, read-only root, no-new-privileges, non-root, memory and CPU caps), but
networking is either off or unrestricted, and the agent holds its own API key. The operator chose to adopt
PAODO's design where it goes further than miniforge's.

## Changes in Detail

1. **Profiles (§1).** `:restricted` becomes an exact-host allowlist; `:standard` adds a baseline list and is the
   governed default; `:full` is refused in governed mode.
2. **Topology (§2).** One internal network and one proxy sidecar per capsule. No outside name resolution.
   Acquisition order and fail-closed cleanup.
3. **Allowlist (§3).** Sources are derived hosts, operator config, and baseline. Agent-produced artifacts
   cannot add entries. An empty list refuses everything.
4. **Destination guard (§4).** Checks the address actually dialed, covering private, loopback, CGNAT,
   link-local and embedded-IPv4 forms.
5. **Credentials (§5).** The capsule gets opaque tokens; the proxy substitutes on exact hosts and redacts
   responses. Signed-request and self-refreshing credentials are refused unless the operator lists them.
   `CLAUDE_CODE_OAUTH_TOKEN` is treated as a static bearer token.
6. **CA trust (§6).** A per-capsule CA generated in the sidecar, plus a combined bundle and per-backend trust
   declarations. Codex trust must be shown by test before Codex runs under the delta.
7. **Refusals (§7).** A `403` carrying a reason header; the agent context lists allowed hosts and says a
   refusal is final.
8. **Registry (§8).** Two new capabilities and `:flags` vectors; no `:runtime/kind` branches, no daemon API.
9. **Kubernetes (§10).** Proxy in its own pod, NetworkPolicy with an enforcement probe; fail closed until
   implemented.
10. **Annex A.** Current state, verified against `ea407dfc3`.

## Testing Plan

Spec-only. Every factual claim in Annex A and §11 was checked against the code and N11 at `ea407dfc3`.
`bb fmt:md` and the commit budget run in the pre-commit hook. The delta's own Test Obligations bind the
implementation PRs.

## Deployment Plan

None. Implementation follows in separate PRs, one stratum each: registry capabilities and EDN config first,
then the proxy image, then executor lifecycle, then agent context.

## Related Issues/PRs

- N11 Annex A.2 already lists network policy enforcement as not implemented.

## Checklist

- [x] Spec file with header, metadata, conformance IDs, and test obligations
- [x] Indexed in `SPEC_INDEX.md`
- [x] Annex A claims checked against the code
