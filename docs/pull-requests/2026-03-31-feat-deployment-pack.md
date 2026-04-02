# feat: Deployment Pack — Policy-gated Pulumi/Kustomize orchestration with evidence

## Overview

Introduces the **Deployment Pack**: three new Polylith components that extend Miniforge's
workflow engine to govern infrastructure provisioning and application deployment with the
same policy gates, approval flows, and evidence bundles that currently govern the Software
Factory.

This is Part 1 of the constrained GCP pilot: prove Miniforge as the control plane for
deployment orchestration — not a replacement for existing IaC/deploy tools, but the
governance layer wrapping them.

**Target environment:** GCP (engrammicai/miniforge.ai), Pulumi for IaC, Kustomize +
kubectl for K8s, GKE as target cluster, Ixi backend as the initial workload.

## Motivation

Deployment platforms lack a unified governance layer. Each tool (IaC, deploy tooling,
secrets, policy) handles its own slice. Nobody owns the cross-cutting concerns: policy
enforcement before destructive actions, approval flows, immutable evidence capture, and
rollback reconstruction.

Miniforge's workflow engine + policy-pack gate + evidence bundle framework already solve
these concerns for SDLC. This PR proves they work identically for deployment, with the
same `:policy-pack` gate mechanism (already fully implemented in `gate/policy.clj`,
dormant until a workflow activates it via `:gates`).

## Components Added

### `components/phase-deployment`

Deployment phase interceptors following the `phase-software-factory` pattern exactly:
side-effect registration via `defmethod registry/get-phase-interceptor`.

**Source files:**

- `provision.clj` — `:provision` phase: `pulumi preview --json` → policy gate → `pulumi up`
  - Enter: runs preview, stores as artifact for `:policy-pack` gate
  - Leave: applies after gates pass, captures stack outputs
- `deploy.clj` — `:deploy` phase: `kustomize build | kubectl apply` → rollout wait
  - Captures rendered manifests and image digests as evidence before apply
  - Captures pod state for `:deploy-healthy` gate
- `validate.clj` — `:validate` phase: HTTP health checks + smoke test commands
  - Retry with backoff; configurable endpoint list
- `gates.clj` — Deployment gate implementations:
  - `:provision-validated` — Pulumi outputs non-empty with expected keys
  - `:deploy-healthy` — All pods in Ready state
  - `:health-check` — HTTP endpoints return 2xx
- `evidence.clj` — 10 deployment evidence types (pulumi-preview, pulumi-outputs,
  rendered-manifests, image-digests, policy-results, approvals, environment-metadata,
  validation-results, rollback-info, resolved-config); SHA-256 content-addressed bundle
- `shell.clj` — CLI execution utilities: `pulumi!`, `kubectl!`, `kustomize-apply!`,
  `sh-with-timeout`, `classify-error` (transient/state-lock/permanent classification)
- `config_resolver.clj` — GCP Secret Manager config resolution via ADC; resolves
  `${gcp-sm:secret-name}` placeholders; Malli schema validation
- `policy.clj` — Custom detection functions for deployment policy pack:
  `check-resource-count`, `check-gke-node-limit`
- `messages.clj` — i18n message key lookup via messages component

**Resources:**

- `resources/packs/deployment-safety/pack.edn` — 5 policy rules:
  - `:deploy/no-destroy-production` (:critical) — blocks delete on protected types
  - `:deploy/resource-count-limit` (:medium) — warns on >20 creates in one operation
  - `:deploy/no-public-endpoints` (:high) — requires approval for 0.0.0.0/0 ingress
  - `:deploy/gke-node-limit` (:medium) — warns on node pool count
  - `:deploy/no-secrets-in-manifests` (:critical) — blocks plaintext secrets in YAML
- `resources/config/phase/deploy-defaults.edn` — Phase defaults for all three phases
- `resources/config/phase/deploy-messages/en-US.edn` — i18n message keys

### `components/workflow-deployment`

Resources-only component (no src). Three workflow EDN definitions:

- `standard-deploy-v1.0.0.edn` — Full pipeline: provision → deploy → validate
  - `:provision` uses `[:policy-pack :provision-validated]` gates (activates dormant gate)
  - `:deploy` uses `[:deploy-healthy]` gate
  - `:validate` uses `[:health-check]` gate
- `quick-deploy-v1.0.0.edn` — Skip provision (infra already up): deploy → validate
- `provision-only-v1.0.0.edn` — Infrastructure only, no app deployment

### `components/workflow-chain-deployment`

Resources-only component. One chain EDN:

- `sdlc-to-deploy-v1.0.0.edn` — End-to-end: SDLC Software Factory → Deployment

## Files Modified

| File | Change |
|------|--------|
| `deps.edn` | Added 3 new components to `:dev` and `:test` aliases |
| `projects/miniforge/deps.edn` | Added deployment pack to full project |
| `components/schema/src/ai/miniforge/schema/interface.clj` | Added `succeeded?` and `failed?` predicates (required by result-handling rule 003) |

## Architecture Notes

### Activation of the policy-pack gate

The `:policy-pack` gate is already fully implemented in `gate/policy.clj` (lines 233-288)
with severity cascade (critical→block, high→approval-required, medium→warn, low→audit)
and approval flow. It was previously dormant — no workflow EDN referenced it.

`standard-deploy-v1.0.0.edn` adds `:policy-pack` to `:provision`'s `:gates` vector,
activating it with **zero code changes** to the gate component.

### Gate execution flow (how enter→gate→leave works)

```text
provision/enter → pulumi preview → store preview as artifact
                ↓
execution.clj   → gate/check-gates [:policy-pack :provision-validated] against artifact
                ↓
  if gates pass → provision/leave → pulumi up → capture outputs
  if gates fail → phase :failed → workflow :on-fail
```

### Evidence bundle

Every deployment run produces an immutable evidence bundle:

```text
deployments/<run-id>/
  bundle.edn                    # Manifest
  manifest.edn                  # Evidence paths (no content)
  evidence/
    pulumi-preview-<hash>.json
    rendered-manifests-<hash>.yaml
    image-digests-<hash>.edn
    ...
```

Sufficient for full audit + rollback reconstruction (apply `rendered-manifests` to
reproduce any past deployment state exactly).

### Namespace pattern

Follows `phase-software-factory` side-effect-registration pattern:

- No `interface.clj` (pure defmethod registration, no public API to expose)
- Namespace root: `ai.miniforge.phase.deploy.*`
- Phase registration extends `ai.miniforge.phase.registry/get-phase-interceptor`
- Gate registration extends `ai.miniforge.gate.registry/get-gate`

## Standards Compliance

Applied per miniforge-standards rulesets (`.standards/`):

| Rule | Applied |
|------|---------|
| `frameworks/polylith` (300) | Test placement: unit tests < 100ms in component; I/O tests in project |
| `languages/clojure` (210) | Layer headings monotonic (0→1→2), max 3 strata per file |
| `languages/clojure` (210) | Map access: `(get m :k default)` not `(or (:k m) default)` |
| `languages/clojure` (210) | Cross-component deps via interface only (gate.registry for defmethod extension is the accepted pattern matching phase-software-factory) |
| `foundations/result-handling` (003) | `schema/success`/`schema/failure` constructors throughout; `schema/succeeded?`/`schema/failed?` predicates instead of raw `(:success? result)` access; `schema/valid`/`schema/invalid-with-errors` for validation results |
| `foundations/code-quality` (002) | Extracted `get-logger` helper (removes duplicated logger setup in enter/leave/error); extracted `failed-enter` helper (removes duplicated failure ctx-building in enter functions) |
| `project/header-copyright` (810) | Apache 2.0 + copyright header on all .clj files |
| `foundations/localization` (050) | No raw strings in user-facing output; all keys in en-US.edn |

## Testing Plan

### Unit tests (this PR: no cloud required)

The test scaffolding is in place under `components/phase-deployment/test/`.
Tests to add in a follow-up PR:

- `shell_test.clj` — timeout handling, output parsing, error classification
- `evidence_test.clj` — SHA-256 hashing, bundle assembly, type coverage
- `gates_test.clj` — provision-validated/deploy-healthy/health-check pass/fail scenarios
- `policy_test.clj` — resource-count-limit, gke-node-limit detection functions
- `config_resolver_test.clj` — placeholder extraction, schema validation failures

### Integration test (requires GCP access — future PR)

1. `pulumi preview` → policy gate evaluates preview JSON
2. Policy gate blocks destructive change (test plan with deletes on protected resources)
3. Approved preview → `pulumi up` → GKE cluster + Cloud SQL provisioned
4. Config resolution from GCP Secret Manager → validated against Malli schema
5. `kustomize build | kubectl apply` → rollout succeeds → health check passes
6. Evidence bundle on disk with all artifacts
7. Rollback test: evidence bundle → reproduce deployment

## Deployment Plan

This PR is the component implementation only — no deployment required. The deployment pack
activates automatically when included in a project and a workflow EDN references the new
phase keywords (`:provision`, `:deploy`, `:validate`).

The GKE integration test (using real GCP credentials and the Ixi backend) is scheduled
for a follow-up PR after unit tests are in place.

## Related Issues/PRs

- Constrained pilot plan: `.claude/plans/warm-questing-donut.md`
- Activates dormant `:policy-pack` gate (implemented in `gate/policy.clj`)
- Prerequisites: `phase`, `gate`, `workflow`, `evidence-bundle`, `policy-pack` (all merged)

## Checklist

- [x] Three components created with correct Polylith structure
- [x] Follows `phase-software-factory` side-effect registration pattern
- [x] Layer headings monotonic (0→1→2), max 3 per file
- [x] Map access: `(get m :k default)` throughout (not `or (:k m)`)
- [x] No cross-component non-interface imports (removed phase-config from phase-software-factory)
- [x] Copyright + Apache 2.0 header on all .clj files
- [x] All user-facing strings in `en-US.edn` message catalog
- [x] `deps.edn` and `projects/miniforge/deps.edn` updated
- [x] PR doc created per `workflows/pr-documentation` (721)
- [ ] Unit tests (follow-up PR)
- [ ] Integration test against GCP/GKE (follow-up PR after unit tests)
