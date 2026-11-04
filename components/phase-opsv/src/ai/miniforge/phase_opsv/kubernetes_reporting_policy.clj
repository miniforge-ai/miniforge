;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-policy)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} confidential-key
  "Producer-owned classification; its local name uses shared secret-key policy and its namespace isolates the proof."
  :kubernetes.reporting/secret)

(def ^{:stratum 0} profile
  "Fixed data-minimized reporting profile, never a native manifest or comparison projection."
  :kubernetes/data-minimized-reporting-v1)

(def ^{:stratum 0} metadata-fields
  "Scalar native identity and server provenance; composite metadata requires dedicated projections."
  #{"name" "namespace" "uid" "resourceVersion" "creationTimestamp"})

(def ^{:stratum 0} resource-fields
  "Public native headers. Payloads and unknown fields require explicit reporting treatment."
  #{"apiVersion" "kind"})

(def ^{:stratum 0} reference-fields
  "Native owner-reference provenance remains inspectable; unknown reference extensions remain confidential."
  #{"apiVersion" "kind" "name" "uid"})

(def ^{:stratum 0} controller-types
  "Only native apps/v1 scale controllers receive the typed desired-count projection."
  #{["apps/v1" "Deployment"] ["apps/v1" "ReplicaSet"]})

(def ^{:stratum 0} resource-shape-fields
  "Native field presence, including malformed TypeMeta and equivalent unqualified keyword keys."
  #{"apiVersion" "kind" "metadata" "spec" "status" "data" "binaryData" "stringData" "items" "containers"
    "initContainers" "ephemeralContainers" "template" "env" "envFrom" "command" "args"})

(def ^{:stratum 0} confidential-semantic-fields
  "Native semantic payloads retain original values for hashing/scanning, not reporting plaintext."
  #{:kubernetes/pod-spec :resource/ownership-and-lifecycle :resource/runtime})
