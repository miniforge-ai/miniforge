;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-records
  "Nested N4 gate-binding and N6 resolved-pack and waiver contracts."
  (:require [ai.miniforge.evidence-bundle.schema.governance-values :as values]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} bound-pack
  [:map [:pack/id keyword?] [:pack/version [:fn values/nonblank?]]])

(def ^{:stratum 0} resolved-pack
  [:map [:pack/id keyword?] [:pack/version values/resolved-version]
   [:pack/content-hash values/sha256]])

(def ^{:stratum 0} rule-filter
  [:map
   [:filter/categories {:optional true} (values/record-vector keyword?)]
   [:filter/phase {:optional true} keyword?]
   [:filter/applies-to {:optional true} (values/record-vector keyword?)]])

(def ^{:stratum 0} waiver
  [:map
   [:waiver/id uuid?] [:waiver/evaluation-id uuid?]
   [:waiver/violations (values/record-vector keyword?)]
   [:waiver/actor [:fn values/nonblank?]] [:waiver/reason [:fn values/nonblank?]]
   [:waiver/timestamp inst?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} binding-schema
  [:map [:gate/id keyword?]
   [:binding/packs (values/record-vector bound-pack)]
   [:binding/rule-filter {:optional true} rule-filter]])
