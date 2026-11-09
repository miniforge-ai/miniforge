<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: Kubernetes provider conformance run against a local kind cluster

## Overview

Pilot slice D3 for issue #2439, second part. The Kubernetes provider PR left its kind integration
test out to stay within its size limit; this PR adds it. API surface class 1: in-process EDN.

## Layer

Tests only. Depends on the Kubernetes provider and the agent-environment conformance suite.

## Motivation

The provider's unit tests answer kubectl from a script. Only a real cluster shows that the
manifests are accepted, that preflight refuses a missing RuntimeClass, and that cleanup leaves no
namespace behind.

## Changes in Detail

1. `projects/agent-environments/test/.../kind_integration_test.clj` runs three tests:
   1. the full conformance suite through the provider, cleanup check included;
   2. a partial provision (pod creation refused) with real cleanup;
   3. a dedicated-kernel request refused at preflight when its RuntimeClass is not installed, and
      the API refusing a pod that names it, so nothing runs on the node default runtime.
2. It runs only when `MINIFORGE_KIND_CONTEXT` names a `kind-*` context and
   `MINIFORGE_KIND_KUBECONFIG` names the kubeconfig file holding it. A missing or blank kubeconfig
   fails before any cluster call, so the test never reads `KUBECONFIG` or the operator's default
   file; a `kind-*` name alone does not show the cluster is local. Unconfigured, the first test
   fails rather than passing silently. No CI job runs it.

## Testing Plan

Run locally against a kind cluster (podman provider) with its own kubeconfig file, the provider
from the Kubernetes PR and the latest lifecycle and conformance code on the classpath: 16 tests and
54 assertions passed (the provider and manifest unit tests plus these three), and no namespace was
left behind. Real Kata was not tested.

## Deployment Plan

Nothing deploys.

## Related Issues/PRs

Issue #2439. Stacked on the Kubernetes provider PR.

## Checklist

- [x] Kind integration test with a required dedicated kubeconfig
- [x] Local kind run passed
- [ ] CI green, Copilot threads resolved, squash-merged
