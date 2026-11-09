;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as settings]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as retention]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} after-trial [routes]
  (-> routes
      (assoc-in [inventory/deployment-path "spec" "replicas"] replica/trial-count)
      (assoc-in [inventory/deployment-path "metadata" "resourceVersion"] replica/fresh-revision)))

(defn ^{:stratum 0} prepare [{:keys [ctx] :as fixture} routes]
  (let [calls (atom [])]
    (with-redefs [transport/start! (partial wire/reply! routes calls)
                  transport/response-fields identity]
      [(phase/prepare-kubernetes-baseline-restoration-proposal! ctx (:restoration/request fixture)) @calls])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configured [f {:keys [input plan-id native] :as fixture}]
  (let [context (get-in input [:load :limits :kubernetes/context])
        provider (assoc (dissoc (settings/settings) :kubernetes/pod-selector) :kubernetes/context context)
        identity (random-uuid)
        request {:recovery/input input
                 :recovery/artifact-ref plan-id
                 :restoration/provider provider
                 :effect/id identity}
        routes (after-trial (:routes native))]
    (f (assoc fixture :restoration/request request :restoration/routes routes))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input [f]
  (retention/with-retained (partial configured f)))

(comment
  (after-trial {}))
