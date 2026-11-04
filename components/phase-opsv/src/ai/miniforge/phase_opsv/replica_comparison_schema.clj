;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.replica-comparison-schema
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]
            [ai.miniforge.phase-opsv.environment-target-schema :as target]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} ordered-count-bounds? [expectation]
  (<= (:replicas/min-count expectation) (:replicas/max-count expectation)))

(def ^{:stratum 0} Observation
  [:map {:closed true}
   [:replica/id {:description "Provider-issued instance UID; names are not instance identity."} fingerprint/Text]
   [:replica/target target/Target]
   [:replica/template-hash
    {:description "Content hash of the observed replica configuration under the predeclared projection."}
    opsv/PolicyHash]
   [:replica/node-pool fingerprint/Text]
   [:replica/image-digests fingerprint/Images]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Expectation
  [:and
   [:map {:closed true}
    [:replica/target target/Target]
    [:replica/template-hash opsv/PolicyHash]
    [:replica/node-pools [:vector {:min 1} fingerprint/Text]]
    [:replica/image-digests fingerprint/Images]
    [:replicas/min-count [:int {:min 0}]]
    [:replicas/max-count [:int {:min 0}]]]
   [:fn ordered-count-bounds?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Contract
  [:map {:closed true}
   [:candidate Expectation]
   [:rollback Expectation]])

(comment ::predeclared-population-expectations)
