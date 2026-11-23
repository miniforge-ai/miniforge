;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-execution-fixtures
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as trial]
            [ai.miniforge.phase-opsv.prometheus-test-support :as http]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} mutations provider/mutations)

(defn ^{:stratum 0} current-grant [{:keys [host/runtime]} id]
  (grant/current (:authority-directory runtime) id))

(defn ^{:stratum 0} stop! [status fixture]
  (let [report (phase/stop-supervised-runs! (:host/supervisor fixture) clock/now)]
    (is (true? (:stopped? report)))
    (swap! (:host/context fixture) assoc :execution/status status)
    fixture))

(defn ^{:stratum 0} safe-mode! [fixture]
  (swap! (:host/context fixture) assoc-in [:execution/input :opsv/safe-mode?] true)
  fixture)

(defn ^{:stratum 0} deny-plan! [fixture]
  (swap! (:host/context fixture) assoc-in
         [:execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
  fixture)

(defn ^{:stratum 0} deny-effect! [fixture]
  (swap! (:host/context fixture) assoc-in
         [:execution/opts :opsv/governance :policy/context :opsv/recovery-effect-operations] #{})
  fixture)

(defn- ^{:stratum 0} configured [f fixture]
  (let [digest (get-in fixture [:prepared :recovery/hash])
        id (phase/recovery-effect-id digest :restore-baseline)
        request (assoc (:restoration/request fixture) :effect/id id)]
    (swap! (:host/context fixture) update :execution/opts dissoc :opsv/experiment-execution)
    (swap! (:host/context fixture) assoc-in
           [:execution/opts :opsv/governance :policy/context :opsv/recovery-effect-operations] #{:restore-baseline})
    (f (assoc fixture :request request :host/routes (:restoration/routes fixture)))))

(defn ^{:stratum 0} execute-response [fixture pending on-read!]
  (let [issued (atom [])
        issue! grant/issue-for-effect]
    (with-redefs [grant/issue-for-effect (partial forward/capture-issuance! issued issue!)]
      (let [[result calls] (wire/execute-response phase/execute-kubernetes-baseline-restoration! fixture pending on-read!)]
        [result calls @issued]))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-input [f]
  (restoration/with-input (partial forward/with-runtime (partial configured f))))

(defn- ^{:stratum 1} after-forward [f fixture]
  (let [[result _calls] (trial/execute fixture)
        id (:effect/grant-id result)]
    (is (= :succeeded (:effect/state result)))
    (configured f (assoc fixture :forward/grant-id id))))

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (constantly nil)))
  ([fixture on-read!]
   (let [pending (-> fixture provider/response json/generate-string http/response http/completed)]
     (execute-response fixture pending on-read!))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-forward-trial [f]
  (trial/with-executable (partial after-forward f)))

(comment ::actual-forward-grants-and-the-same-stopped-control-remain-in-scope-for-recovery-tests)
