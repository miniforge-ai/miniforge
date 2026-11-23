;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-provider-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as authority]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as owned]
            [ai.miniforge.phase-opsv.kubernetes-load-start-provider-fixtures :as fixtures]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-confirmed [response {:keys [runtime pod] :as fixture}]
  (let [[result calls] (fixtures/execute fixture (response pod))
        patch (first (fixtures/mutations calls))
        proposal (:recovery/proposal fixture)
        observed (:effect/observed result)]
    (is (= :succeeded (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= [:get :patch] (mapv :method calls)))
    (is (= (get-in proposal [:kubernetes/request :body]) (json/parse-string (:body patch))))
    (is (= "application/json-patch+json" (get-in patch [:headers "Content-Type"])))
    (is (true? (:load/gate-release-acknowledged? observed)))
    (is (not-any? #(contains? observed %) [:load/started? :load/quiescence :recovery/verified? :slo/passed?]))))

(defn- ^{:stratum 0} check-live-change [path value fixture]
  (let [[result calls] (fixtures/execute (assoc-in fixture (into [:pod] path) value))]
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

(defn- ^{:stratum 0} check-current-grant [fixture]
  (let [revoked (grant/revoke-stored! (:recovery/grants-directory fixture)
                                      (:recovery/issued-id fixture) :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-independent-pin [fixture]
  (when-let [rebound (owned/rebind! fixture (:recovery/proposal fixture))]
    (let [[result calls] (fixtures/execute (assoc rebound :recovery/issued-id (:recovery/issued-id fixture)))]
      (is (not= (:recovery/issued-id fixture) (:recovery/issued-id rebound)))
      (is (not= :succeeded (:effect/state result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-rebound-payload [fixture]
  (let [proposal (:recovery/proposal fixture)
        payload (update (:kubernetes/request proposal) :body pop)
        changed (-> proposal (assoc :kubernetes/request payload)
                    (assoc-in [:opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))]
    (when-let [rebound (owned/rebind! fixture changed)]
      (let [[result calls] (fixtures/execute rebound)]
        (is (not= :succeeded (:effect/state result)))
        (is (empty? (fixtures/mutations calls)))))))

(defn- ^{:stratum 0} check-cancelled-issuance [fixture]
  (let [issuance (assoc (authority/issuance-request (:recovery/proposal fixture)) :workflow-run/status :cancelled)
        issued (grant/issue-for-effect (:recovery/breach-directory fixture) issuance clock/now)]
    (is (anomaly/anomaly? issued))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} exact-native-start-acknowledges-only-gate-release-with-a-durable-receipt-test
  (doseq [response [fixtures/response
                    (comp #(assoc-in % ["spec" "schedulingGates"] []) fixtures/response)]]
    (fixtures/with-proposed (partial check-confirmed response))))

(deftest ^{:stratum 1} live-worker-spec-identity-and-revision-are-reconstructed-before-dispatch-test
  (doseq [[path value] [[["metadata" "uid"] "foreign-worker"]
                       [["metadata" "resourceVersion"] "changed-before-dispatch"]
                       [["metadata" "labels" "access"] "unrequested-network-policy"]
                       [["spec" "activeDeadlineSeconds"] 999]
                       [["spec" "schedulingGates"] []]
                       [["spec" "containers" 0 "env"] [{"name" "HTTP_PROXY"
                                                         "value" "https://foreign.invalid"}]]]]
    (fixtures/with-proposed (partial check-live-change path value))))

(deftest ^{:stratum 1} revoked-current-recovery-readiness-refuses-before-all-native-reads-test
  (fixtures/with-proposed check-current-approval))

(deftest ^{:stratum 1} malformed-or-extended-start-runtime-cannot-reach-the-provider-test
  (doseq [transform [#(assoc % :approved? true)
                     #(assoc-in % [:input :effect/id] "invalid-id")
                     #(update-in % [:input :ownership/runtime] dissoc :provider)]]
    (fixtures/with-proposed (partial check-invalid-runtime transform))))

(deftest ^{:stratum 1} revoked-forward-grant-blocks-start-before-any-provider-call-test
  (fixtures/with-proposed check-current-grant))

(deftest ^{:stratum 1} another-valid-forward-grant-cannot-replace-the-independently-issued-pin-test
  (fixtures/with-proposed check-independent-pin))

(deftest ^{:stratum 1} rehashing-and-reissuing-a-different-payload-cannot-bypass-current-intent-test
  (fixtures/with-proposed check-rebound-payload))

(deftest ^{:stratum 1} recovery-readiness-does-not-authorize-new-forward-start-after-cancellation-test
  (fixtures/with-proposed check-cancelled-issuance))

(comment
  (fixtures/with-proposed (partial check-confirmed fixtures/response)))
