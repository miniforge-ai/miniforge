;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.pack-records
  "N6 section 2.11 pack-run capability and dependency vocabulary.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Capability
  [:map [:capability/id string?] [:capability/scope keyword?]])

(def ^{:stratum 0} Dependency
  [:map [:pack/id string?] [:pack/version string?] [:pack/content-hash string?]])

(def ^{:stratum 0} ConnectorAction
  [:map [:action/capability string?] [:action/timestamp inst?]
   [:action/result [:enum :success :failure :denied]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} GrantedCapability
  (into Capability [[:capability/granted-by [:enum "user" "policy" "auto"]]
                    [:capability/granted-at inst?]]))

(def ^{:stratum 1} DeniedCapability
  (conj Capability [:capability/denied-reason string?]))

(comment
  GrantedCapability)
