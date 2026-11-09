;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as recovery]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-confirmed [response status {:keys [runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture (response fixture) status)
        deletion (first (fixtures/mutations calls))
        proposal (:recovery/proposal fixture)]
    (is (= :succeeded (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= [:get :delete] (mapv :method calls)))
    (is (= (get-in proposal [:kubernetes/request :body]) (json/parse-string (:body deletion))))
    (is (= "application/json" (get-in deletion [:headers "Content-Type"])))
    (is (true? (get-in result [:effect/observed :load/deletion-acknowledged?])))
    (is (not-any? #(contains? (:effect/observed result) %) [:load/absent? :load/quiescence :recovery/verified?]))))

(defn- ^{:stratum 0} check-current-quiescence [fixture]
  (let [changed (update-in fixture [:pod "spec"] dissoc "schedulingGates")
        [result calls] (fixtures/execute changed)]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? (fixtures/mutations calls)))))

(defn- ^{:stratum 0} check-current-approval [fixture]
  (let [changed (assoc-in fixture [:recovery/runtime :context :execution/opts
                                  :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
        [result calls] (fixtures/execute changed)]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-invalid-runtime [transform fixture]
  (let [[result calls] (fixtures/execute (update fixture :recovery/runtime transform))]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-rebound-cascade [fixture]
  (let [proposal (:recovery/proposal fixture)
        payload (assoc-in (:kubernetes/request proposal) [:body "propagationPolicy"] "Foreground")
        changed (-> proposal (assoc :kubernetes/request payload)
                    (assoc-in [:opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))]
    (when-let [rebound (recovery/rebind! fixture changed)]
      (let [[result calls] (fixtures/execute rebound)]
        (is (not= :succeeded (:effect/state result)))
        (is (empty? (fixtures/mutations calls)))))))

(defn- ^{:stratum 0} check-current-grant [fixture]
  (let [revoked (grant/revoke-stored! (:recovery/grants-directory fixture)
                                      (:recovery/issued-id fixture) :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-forward-revoked [{:keys [runtime record] :as fixture}]
  (let [revoked (grant/revoke-stored! (:effects-directory runtime) (:effect/grant-id record)
                                      :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (= :succeeded (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-independent-grant [fixture]
  (when-let [rebound (recovery/rebind! fixture (:recovery/proposal fixture))]
    (let [[result calls] (fixtures/execute (assoc rebound :recovery/issued-id (:recovery/issued-id fixture)))]
      (is (not= (:recovery/issued-id fixture) (:recovery/issued-id rebound)))
      (is (not= :succeeded (:effect/state result)))
      (is (empty? calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-pod-and-status-acknowledgements-do-not-prove-absence-or-baseline-recovery-test
  (doseq [response [fixtures/pod-response fixtures/status-response]
          status [transport/success-status transport/accepted-status]]
    (fixtures/with-proposed (partial check-confirmed response status))))

(deftest ^{:stratum 1} quiescence-is-reread-before-exact-cleanup-dispatch-test
  (fixtures/with-proposed check-current-quiescence))

(deftest ^{:stratum 1} current-retained-recovery-approval-is-required-before-all-provider-calls-test
  (fixtures/with-proposed check-current-approval))

(deftest ^{:stratum 1} malformed-or-caller-extended-cleanup-runtime-is-rejected-before-provider-calls-test
  (doseq [transform [#(assoc % :approved? true)
                     #(assoc-in % [:input :effect/id] "not-an-effect-id")
                     #(update-in % [:input :ownership/runtime] dissoc :provider)]]
    (fixtures/with-proposed (partial check-invalid-runtime transform))))

(deftest ^{:stratum 1} a-recomputed-cascade-payload-hash-and-matching-root-grant-do-not-bypass-canonical-intent-test
  (fixtures/with-proposed check-rebound-cascade))

(deftest ^{:stratum 1} revoked-recovery-grants-refuse-before-provider-calls-test
  (fixtures/with-proposed check-current-grant))

(deftest ^{:stratum 1} separately-issued-cleanup-survives-forward-grant-revocation-test
  (fixtures/with-proposed check-forward-revoked))

(deftest ^{:stratum 1} another-valid-root-grant-does-not-replace-the-independently-retained-issued-pin-test
  (fixtures/with-proposed check-independent-grant))

(comment
  (fixtures/with-proposed (partial check-confirmed fixtures/pod-response transport/success-status)))
