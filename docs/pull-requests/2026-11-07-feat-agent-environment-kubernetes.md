<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Kubernetes provider for the agent-environment contract

## Overview

Pilot slice D3 for issue #2439: an opt-in Kubernetes provider for the agent-environment contract.
Each environment gets one namespace and one pod, and the pod runs on the RuntimeClass its isolation
level maps to. API surface class 1: in-process EDN.

## Layer

Adapters. Depends on the agent-environment contract and its conformance suite.

## Motivation

Development-agent environments need a real cluster backend. A request for a dedicated kernel must
fail when the cluster cannot provide one, never fall back to the node's default runtime.

## Client decision: kubectl, not the HTTP transport

Two Kubernetes clients exist. `dag-executor`'s Kubernetes executor shells out to kubectl;
`phase-opsv`'s `boundary/http_transport` calls the API server over HTTP. This provider uses kubectl
through one boundary namespace (`boundary/kubectl`), for these reasons:

1. The provider must exec into the pod and stream stdin. Exec needs the API's streaming
   subprotocol, which kubectl implements; the HTTP transport covers request/response calls only.
2. kubectl reads kubeconfig as operators already use it, including exec credential plugins and
   client certificates. The HTTP transport would need all of that rebuilt.
3. Only the pattern is reused, not `dag-executor`'s code: that executor is internal to its
   component, runs each call through `clojure.java.shell` with no per-call deadline, and uses
   whatever kubectl context is current.
   Here every call names `--context` (and `--kubeconfig` when set), has a deadline, and returns
   data; a process that cannot start or times out is an anomaly.

The cost is a process per call and a kubectl binary on the controller's host. Preflight fails
when the cluster cannot be reached.

## Changes in Detail

1. `settings`: the context and a digest-pinned image are required, and the context is never
   taken from kubectl's current one. Each offered isolation level maps to a RuntimeClass and a node
   selector. A dedicated kernel must name a RuntimeClass, so it cannot map to the node default.
2. `manifests`: a namespace labelled with tenant, run, task, lease generation and isolation. The
   pod names its RuntimeClass and runs non-root, with no service-account token, no host paths
   (only emptyDir) and a read-only root filesystem.
3. `cluster`: reads and writes as data through a `run` port shaped like `boundary.kubectl/run`.
4. `provider`: preflight checks API readiness, the RuntimeClass and eligible nodes. Every call on
   an existing environment first checks the namespace still has the target's UID. `observe`
   reports the generation label, so recovery adopts only the namespace this lease created. Release
   is applied only when the namespace is confirmed gone.
5. `projects/agent-environments` and the opt-in guard include the new brick; no default project
   depends on it.

## Testing Plan

1. Unit tests with a scripted kubectl cover:
   1. settings validation, including a dedicated kernel without a RuntimeClass;
   2. preflight reasons;
   3. offered isolation;
   4. a refused pod leaving a partial provision;
   5. calls on a recreated namespace refused, with no delete issued;
   6. release confirmed only when the namespace is gone;
   7. the observed generation;
   8. the manifests' labels and security settings.
2. `projects/agent-environments/test/.../kind_integration_test.clj` runs against a local kind
   cluster and refuses any context not named `kind-*`. No CI job runs it. It was run locally
   against kind (podman provider) with its own kubeconfig file, and its three tests passed:
   1. the full conformance suite;
   2. a partial provision with real cleanup;
   3. a dedicated-kernel request refused at preflight, and the API refusing a pod whose
      RuntimeClass is absent.

   No namespaces were left behind. Real Kata was not tested.

## Deployment Plan

Nothing deploys. An operator who opts in sets the context, the image and the RuntimeClass mapping.

## Related Issues/PRs

Issue #2439. Stacked on the native agent-worker PR.

## Checklist

- [x] Provider with scripted-kubectl tests and a local kind run
- [ ] CI green, Copilot threads resolved, squash-merged
