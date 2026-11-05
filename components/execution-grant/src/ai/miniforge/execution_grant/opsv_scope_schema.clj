;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-scope-schema
  (:require [ai.miniforge.kubernetes-target.interface :as target]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} experiment-effect? [class scope]
  (or (= :effect/load-test class)
      (and (map? scope) (contains? scope :opsv/experiment))))

(def ^{:stratum 0} operation-classes
  {:trial-apply :effect/deploy
   :restore-baseline :effect/deploy
   :start-load :effect/load-test
   :stop-load :effect/load-test
   :cleanup-load :effect/load-test})

(def ^{:stratum 0} Hash [:re #"\A[0-9a-f]{64}\z"])

(def ^{:stratum 0} Resource
  [:map {:closed true}
   [:api-version target/IdentityText]
   [:kind target/IdentityText]
   [:namespace target/IdentityText]
   [:name target/IdentityText]
   [:uid [:maybe target/IdentityText]]])

(def ^{:stratum 0} LoadLimits
  [:map {:closed true}
   [:load/max-requests nat-int?]
   [:load/max-concurrency nat-int?]
   [:load/max-rate-per-second nat-int?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Binding
  [:map {:closed true}
   [:verification/id :uuid]
   [:candidate/hash Hash]
   [:experiment-pack/hash Hash]
   [:comparison/artifact-ref :uuid]
   [:comparison/hash Hash]
   [:environment/class [:= :staging]]
   [:runtime/target target/Target]
   [:kubernetes/context target/IdentityText]
   [:kubernetes/deployment-name target/IdentityText]
   [:opsv/operation (into [:enum] (keys operation-classes))]
   [:opsv/payload-hash Hash]
   [:opsv/resources [:vector {:min 1} Resource]]
   [:opsv/traffic-destinations [:vector target/IdentityText]]
   [:opsv/duration-ms pos-int?]
   [:opsv/load-limits LoadLimits]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Scope
  [:map
   [:workflow-run/id :uuid]
   [:effect/id :uuid]
   [:opsv/experiment Binding]])

(comment ::exact-authority-bindings-not-runtime-admission-or-provider-proof)
