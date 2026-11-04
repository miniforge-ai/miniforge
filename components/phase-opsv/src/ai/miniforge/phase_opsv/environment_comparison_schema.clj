;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-schema
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} same-cluster? [plan]
  (= (get-in plan [:comparison/baseline :cluster])
     (get-in plan [:comparison/binding :runtime/target :cluster])))

(def ^{:stratum 0} Binding
  [:map {:closed true}
   [:workflow/id :uuid]
   [:verification/id :uuid]
   [:candidate/hash opsv/PolicyHash]
   [:experiment-pack/hash opsv/PolicyHash]
   [:runtime/target
    [:map {:closed true}
     [:cluster fingerprint/Text]
     [:namespace fingerprint/Text]
     [:namespace-uid fingerprint/Text]
     [:workload-uid fingerprint/Text]]]])

(def ^{:stratum 0} Changes
  [:map {:closed true}
   [:image-digests {:optional true} fingerprint/Images]
   [:config-hash {:optional true} fingerprint/Text]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Plan
  [:and
   [:map {:closed true}
    [:comparison/binding Binding]
    [:comparison/baseline fingerprint/Fingerprint]
    [:comparison/changes Changes]]
   [:fn same-cluster?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Pin
  [:map {:closed true}
   [:comparison/contract Plan]
   [:comparison/hash opsv/PolicyHash]])

(comment ::closed-predeclared-environment-values)
