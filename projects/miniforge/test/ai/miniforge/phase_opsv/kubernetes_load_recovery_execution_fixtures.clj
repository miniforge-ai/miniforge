;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-fixtures
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as stop]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures :as stop-provider]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as trial]
            [ai.miniforge.phase-opsv.prometheus-test-support :as http]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} operations
  {:stop-load phase/execute-kubernetes-load-stop!
   :cleanup-load phase/execute-kubernetes-load-cleanup!})

(def ^{:stratum 0} readiness-kind
  {:stop-load :native-load-stop-readiness
   :cleanup-load :native-load-cleanup-readiness})

(defn ^{:stratum 0} mutations [calls]
  (filterv #(contains? #{:patch :delete} (:method %)) calls))

(defn ^{:stratum 0} for-operation [operation fixture]
  (let [digest (get-in fixture [:prepared :recovery/hash])
        id (phase/recovery-effect-id digest operation)]
    (assoc-in fixture [:request :effect/id] id)))

(defn ^{:stratum 0} with-pod [fixture pod]
  (-> fixture
      (assoc :pod pod)
      (assoc-in [:host/routes (:load/path fixture)] pod)))

(defn- ^{:stratum 0} response [operation fixture]
  (case operation
    :stop-load (stop-provider/response (:pod fixture))
    :cleanup-load (:pod fixture)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configured [operation f {:keys [runtime record pod native] :as fixture}]
  (let [directory (:effects-directory runtime)
        collection (get-in record [:effect/proposal :kubernetes/request :path])
        path (str collection "/" (get-in pod ["metadata" "name"]))
        host (assoc (:host/runtime fixture) :effects-directory directory)
        routes (assoc (:routes native) path pod)]
    (swap! (:host/context fixture) update :execution/opts dissoc :opsv/experiment-execution)
    (swap! (:host/context fixture) assoc-in
           [:execution/opts :opsv/governance :policy/context :opsv/recovery-effect-operations] #{:stop-load :cleanup-load})
    (f (for-operation operation (assoc fixture :host/runtime host :host/routes routes :load/path path)))))

(defn ^{:stratum 1} execute-response [operation fixture pending on-read!]
  (let [execute! (get operations operation)
        issued (atom [])
        issue! grant/issue-for-effect]
    (with-redefs [grant/issue-for-effect (partial forward/capture-issuance! issued issue!)]
      (let [[result calls] (wire/execute-response execute! fixture pending on-read!)]
        [result calls @issued]))))

(defn ^{:stratum 1} check-exact-admission [operation fixture result]
  (let [value (trial/material-artifact (readiness-kind operation) fixture)
        admitted (get-in value [:artifact/content :recovery/effect-admission])
        decision (:opsv/decision-envelope (last (forward/dispositions fixture)))]
    (is (= [:opsv/recovery-effect-gate] (get-in decision [:envelope/pins :pins/rule-ids])))
    (is (= (:effect/envelope-id result) (:envelope/id decision)))
    (is (= (:effect/proposal result) (:effect/proposal admitted)))
    (is (= [true] (mapv :gate/passed? (:opsv/gate-results admitted))))
    (is (= (:pod fixture) (get-in admitted [:ownership/observation :load/pod])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input [operation f]
  (stop/with-created (partial forward/with-runtime (partial configured operation f))))

(defn ^{:stratum 2} execute
  ([operation fixture] (execute operation fixture (constantly nil)))
  ([operation fixture on-read!]
   (let [pending (-> (response operation fixture) json/generate-string http/response http/completed)]
     (execute-response operation fixture pending on-read!))))

(comment ::actual-retained-plan-and-creation-receipt-no-adopted-resource-or-asserted-termination)
