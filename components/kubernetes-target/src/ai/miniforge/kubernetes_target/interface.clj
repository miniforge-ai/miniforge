;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.interface
  "API surface class 1: internal EDN Kubernetes identity declarations, not authority or observed membership."
  (:require [ai.miniforge.kubernetes-target.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} IdentityText
  "Unnormalized identity text, preserving clojure.string/blank? admission semantics."
  schema/IdentityText)

(def ^{:stratum 0} Target
  "Closed cluster/namespace identity with independent namespace and workload UIDs."
  schema/Target)

(comment ::shared-native-target-contract)
