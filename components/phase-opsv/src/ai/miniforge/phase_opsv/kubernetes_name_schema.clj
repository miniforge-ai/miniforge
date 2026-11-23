;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-name-schema
  "Native Kubernetes names, shared by target settings and API dependency admission.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NamespaceName
  "Namespaces use DNS labels, not qualified resource paths."
  [:and [:string {:max 63}] [:re #"\A[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\z"]])

(def ^{:stratum 0} ResourceName
  "DNS-subdomain identities used in derived resource read paths."
  [:and [:string {:max 253}]
   [:re #"\A[a-z0-9](?:[-a-z0-9]*[a-z0-9])?(?:\.[a-z0-9](?:[-a-z0-9]*[a-z0-9])?)*\z"]])

(comment ::native-kubernetes-name-domains)
