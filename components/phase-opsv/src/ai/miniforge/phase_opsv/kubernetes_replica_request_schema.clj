;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-request-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Request
  [:and preparation/Request
   [:map [:experiment-pack [:map [:experiment-pack/guardrails
                                 [:map [:blast-radius [:map [:replica-delta replica/Count]]]]]]]]])

(def ^{:stratum 0} Baseline
  [:map [:environment/raw-observations
         [:map [:kubernetes/verification [:map [:kubernetes/source replica/Capture]]]]]])

(def ^{:stratum 0} Parameters
  [:multi {:dispatch :opsv/operation}
   [:trial-apply [:map {:closed true} [:opsv/operation [:= :trial-apply]] [:replicas/count replica/Count]]]
   [:restore-baseline [:map {:closed true} [:opsv/operation [:= :restore-baseline]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple preparation/Declaration Request Baseline replica/Capture Parameters])

(comment ::restoration-never-accepts-a-caller-selected-replica-count)
