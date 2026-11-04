;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-policy)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} profile
  "Fixed data-minimized reporting profile, never a native manifest or comparison projection."
  :kubernetes/data-minimized-reporting-v1)

(def ^{:stratum 0} metadata-fields
  "Retain provenance and lifecycle; classify annotations, labels, managed fields and unknown content as confidential."
  #{"name" "namespace" "uid" "resourceVersion" "generation" "creationTimestamp"
    "deletionTimestamp" "deletionGracePeriodSeconds" "ownerReferences" "finalizers" "generateName"})

(def ^{:stratum 0} resource-fields
  "Public native headers. Payloads and unknown fields require explicit reporting treatment."
  #{"apiVersion" "kind" "metadata" "type" "immutable"})

(def ^{:stratum 0} container-fields
  "Keep identity and scaling resources; literal environment, images, commands, probes and unknown fields may carry credentials."
  #{"name" "resources" "imagePullPolicy"})

(def ^{:stratum 0} pod-fields
  "Keep placement and typed container groups; other native Pod configuration remains in original hashes, not report plaintext."
  #{"containers" "initContainers" "ephemeralContainers" "nodeName" "restartPolicy"
    "terminationGracePeriodSeconds" "serviceAccountName"})

(def ^{:stratum 0} controller-fields
  "Keep scaling and rollout controls; selectors may repeat confidential label values."
  #{"replicas" "template" "strategy" "minReadySeconds" "revisionHistoryLimit" "paused" "progressDeadlineSeconds"})

(def ^{:stratum 0} template-fields
  "Native template structure; its metadata and PodSpec receive the same fixed treatment as captured Pods."
  #{"metadata" "spec"})
