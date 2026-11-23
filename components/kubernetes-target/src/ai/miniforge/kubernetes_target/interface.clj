;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.interface
  "API surface class 1: internal Kubernetes identities and observed controller edges.
   Matching an edge does not confer execution authority."
  (:require [ai.miniforge.kubernetes-target.controller-identity :as identity]
            [ai.miniforge.kubernetes-target.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} IdentityText
  "Unnormalized identity text, preserving clojure.string/blank? admission semantics."
  schema/IdentityText)

(def ^{:stratum 0} Target
  "Closed cluster/namespace identity with independent namespace and workload UIDs."
  schema/Target)

(def ^{:stratum 0} controller
  "Return an observed child's single declared managing controller reference, or nil."
  identity/controller)

(def ^{:stratum 0} identifies?
  "Does an observed reference identify the given parent by API, kind, name and UID?"
  identity/identifies?)

(def ^{:stratum 0} owns?
  "Does the observed child have this exact managing controller in the same named namespace?"
  identity/owns?)

(comment ::shared-native-target-contract)
