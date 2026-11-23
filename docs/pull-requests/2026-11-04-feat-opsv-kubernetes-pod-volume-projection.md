<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Project generated service-account volume bindings

## Overview

Provide a versioned semantic PodSpec view for a recognized admission-generated
service-account volume. Normalize only its local name and matching mount names.
Retain every volume source, mount property and other PodSpec field.

## Motivation

Replacement Pods receive different generated service-account volume names.
Verification needs a predeclared semantic view that preserves configuration.

## Changes in Detail

Recognize the native generated prefix, projected token/CA/namespace sources and
read-only service-account mounts. Refuse duplicate or unresolved volume names and
multiple recognized token volumes. User volume names remain literal.

Version `:kubernetes/service-account-volume-bindings-v1` identifies the fixed
projection. The stable identifier is a keyword, so it cannot collide with a
native DNS volume name. Keep regular, init and ephemeral container bindings
consistent. Block-device and ordinary volume references remain unchanged.

Ownership: `phase-opsv` pure configuration projection. Validate volume-reference
sections once at ingress. This is not full Pod validation or a native manifest.
It grants no membership, freshness, template match, health or execution authority.
Consumers must retain raw observations and bind the projection before effects.
No configurable exclusions, wildcard paths, native calls or mutations are added.

## Testing Plan

Use native-shaped captured fixtures, with no token values. Cover generated names,
preserved source/mount/spec differences, ordinary aliases, duplicate and missing
references, init/ephemeral containers, devices and absent versus empty fields.
Run product, isolated package, mutation, standards, signed-hook, CI and full-head
review gates. Scan the committed tree so new files are included.

## Deployment Plan

Explicit pure API only. Publish after the inventory partition prerequisite merges
and exclude inherited changes from this PR. Native verification wiring is separate.

## Related Issues/PRs

N7 §6.3; inventory partition #2362.
[Native service-account admission](https://github.com/kubernetes/kubernetes/blob/v1.35.0/plugin/pkg/admission/serviceaccount/admission.go).
[Native name generation](https://github.com/kubernetes/kubernetes/blob/v1.35.0/staging/src/k8s.io/apiserver/pkg/storage/names/generate.go).
[Exact native suffix alphabet](https://github.com/kubernetes/apimachinery/blob/v0.35.0/pkg/util/rand/rand.go).

## Checklist

- [ ] Preserve configuration and reference distinctions under the fixed projection.
- [ ] Keep raw observations and semantic projection distinct.
- [ ] Complete all local, owner, CI and full-review gates.
