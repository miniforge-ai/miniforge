;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.kubernetes-target.interface :as target]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-comparison-schema :as comparison]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-load-schema :as load]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-schema :as replica-request]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Resource
  (mu/get-in grant/OpsvExperimentScope [:opsv/experiment :opsv/resources 0]))

(def ^{:stratum 0} operations
  "Compensation vocabulary excludes every forward effect; one slot per required recovery operation."
  [:stop-load :cleanup-load :restore-baseline])

(def ^{:stratum 0} Duration
  [:int {:min experiment/millis-per-second :max experiment/maximum-duration-ms}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Input
  [:map {:closed true}
   [:load (mu/assoc load/Input :request replica-request/Request)]
   [:recovery/duration-ms Duration]])

(def ^{:stratum 1} Plan
  [:map {:closed true}
   [:recovery/binding comparison/Binding]
   [:comparison/artifact-ref :uuid]
   [:comparison/hash opsv/PolicyHash]
   [:kubernetes/context target/IdentityText]
   [:recovery/duration-ms Duration]
   [:recovery/operations [:= operations]]
   [:recovery/max-effects [:= (count operations)]]
   [:recovery/restore
    [:map {:closed true}
     [:resource (mu/assoc Resource :uid target/IdentityText)]
     [:replicas/count replica/Count]
     [:preconditions [:= :fresh-uid-and-resource-version]]]]
   [:recovery/load
    [:map {:closed true}
     [:creation-effect/id :uuid]
     [:creation-payload/hash opsv/PolicyHash]
     [:resource (mu/assoc Resource :uid :nil)]
     [:ownership [:= :durable-creation-receipt-and-live-uid]]
     [:termination [:= :observed-container-termination]]
     [:cleanup-preconditions [:= :fresh-uid-and-resource-version]]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple retained/Context Input])

(comment ::plan-data-is-not-an-execution-scope-or-provider-identity-proof)
