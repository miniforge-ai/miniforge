;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-request-schema
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-comparison-schema :as contract]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} population-present? [request]
  (= (contains? (get-in request [:comparison/pin :comparison/contract]) :comparison/replica-contract)
     (contains? request :comparison/replica-observations)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Request
  [:and
   [:map {:closed true}
   [:comparison/pin contract/Pin]
   [:comparison/expected-hash opsv/PolicyHash]
   [:comparison/binding contract/Binding]
   [:comparison/stage [:enum :candidate :rollback]]
    [:comparison/observed fingerprint/Fingerprint]
    [:comparison/replica-observations {:optional true} [:vector replicas/Observation]]]
   [:fn population-present?]])

(comment ::independently-pinned-comparison-request)
