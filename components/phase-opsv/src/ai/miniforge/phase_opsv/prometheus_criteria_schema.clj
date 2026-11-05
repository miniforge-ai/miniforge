;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria-schema
  (:require [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.kubernetes-discovery-schema :as discovery]
            [ai.miniforge.phase-opsv.measurement-schema :as measurement]
            [ai.miniforge.phase-opsv.prometheus-criteria :as criteria]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Criterion
  [:map {:closed true}
   [:criterion/id schema/NonBlankString]
   [:measurement/request measurement/WindowRequest]
   [:measurement/reduction [:enum :maximum :minimum :last]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Configuration
  [:and
   [:map {:closed true}
    [:measurement/prometheus :map]
    [:measurement/target target/Target]
    [:measurement/service schema/NonBlankString]
    [:measurement/label-names discovery/LabelNames]
    [:measurement/criteria [:vector {:min 1} Criterion]]]
   [:fn criteria/unique-criteria?]
   [:fn criteria/bounded?]])

(comment ::target-label-correlation-is-not-native-uid-attestation)
