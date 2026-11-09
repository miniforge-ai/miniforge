;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-provider-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures :as fixtures]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-confirmed [{:keys [runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture)
        patch (first (fixtures/mutations calls))
        payload (get-in fixture [:recovery/proposal :kubernetes/request])
        stored (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))]
    (is (= :succeeded (:effect/state result)))
    (is (= result stored))
    (is (= 2 (count calls)))
    (is (= :get (:method (first calls))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= (:body payload) (json/parse-string (:body patch))))
    (is (= "application/json-patch+json" (get-in patch [:headers "Content-Type"])))
    (is (= 1 (get-in result [:effect/observed :load/deadline-seconds])))
    (is (= fixtures/confirmed-revision (get-in result [:effect/observed :kubernetes/resource-version])))
    (is (= #{:kubernetes/resource :kubernetes/resource-version :load/deadline-seconds}
            (set (keys (:effect/observed result)))))
    (is (nil? (:load/quiescence result)))
    (is (nil? (:recovery/verified? result)))))

(defn- ^{:stratum 0} check-current-retention [fixture]
  (let [changed (assoc-in fixture [:recovery/runtime :context :execution/opts
                                  :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
        [result calls] (fixtures/execute changed)]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-invalid-runtime [transform fixture]
  (let [[result calls] (fixtures/execute (update fixture :recovery/runtime transform))]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-current-identity [path value {:keys [pod] :as fixture}]
  (let [[result calls] (fixtures/execute (assoc fixture :pod (assoc-in pod path value)))]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? (fixtures/mutations calls)))))

(defn- ^{:stratum 0} check-rebound-payload [fixture]
  (let [proposal (:recovery/proposal fixture)
        payload (assoc-in (:kubernetes/request proposal) [:body 2 "value"] 2)
        changed (-> proposal (assoc :kubernetes/request payload)
                    (assoc-in [:opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))]
    (when-let [rebound (fixtures/rebind! fixture changed)]
      (let [[result calls] (fixtures/execute rebound)]
        (is (not= :succeeded (:effect/state result)))
        (is (empty? (fixtures/mutations calls)))))))

(defn- ^{:stratum 0} check-independent-pin [fixture]
  (when-let [rebound (fixtures/rebind! fixture (:recovery/proposal fixture))]
    (let [issued-id (:recovery/issued-id fixture)
          [result calls] (fixtures/execute (assoc rebound :recovery/issued-id issued-id))]
      (is (not= issued-id (:recovery/issued-id rebound)))
      (is (not= :succeeded (:effect/state result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-uncertain-creation [{:keys [record] :as fixture}]
  (ownership/replace-record! fixture (assoc record :effect/state :unknown-outcome))
  (let [[result calls] (fixtures/execute fixture)]
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-revoked-forward [{:keys [runtime record] :as fixture}]
  (let [directory (:effects-directory runtime)
        revoked (grant/revoke-stored! directory (:effect/grant-id record) :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (not= (:effect/grant-id record) (:recovery/issued-id fixture)))
    (is (= :succeeded (:effect/state result)))
    (is (= 1 (count (fixtures/mutations calls))))))

(defn- ^{:stratum 0} check-revoked-recovery [fixture]
  (let [directory (:recovery/grants-directory fixture)
        revoked (grant/revoke-stored! directory (:recovery/issued-id fixture) :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} separate-root-grant-and-pinned-commit-execute-only-one-confirmed-deadline-patch-test
  (fixtures/with-proposed check-confirmed))

(deftest ^{:stratum 1} current-retained-approval-is-reread-before-provider-dispatch-test
  (fixtures/with-proposed check-current-retention))

(deftest ^{:stratum 1} malformed-or-caller-extended-runtime-is-rejected-before-provider-calls-test
  (doseq [transform [#(assoc % :approved? true)
                     #(assoc-in % [:input :effect/id] "not-an-effect-id")
                     #(update-in % [:input :ownership/runtime] dissoc :provider)]]
    (fixtures/with-proposed (partial check-invalid-runtime transform))))

(deftest ^{:stratum 1} foreign-live-uid-prevents-the-patch-test
  (fixtures/with-proposed (partial check-current-identity ["metadata" "uid"] "foreign-uid")))

(deftest ^{:stratum 1} changed-current-revision-prevents-the-patch-test
  (fixtures/with-proposed (partial check-current-identity ["metadata" "resourceVersion"] "changed-since-grant")))

(deftest ^{:stratum 1} recomputing-a-payload-hash-cannot-authorize-a-different-deadline-test
  (fixtures/with-proposed check-rebound-payload))

(deftest ^{:stratum 1} separately-issued-recovery-survives-forward-grant-revocation-test
  (fixtures/with-proposed check-revoked-forward))

(deftest ^{:stratum 1} current-recovery-grant-revocation-prevents-all-provider-calls-test
  (fixtures/with-proposed check-revoked-recovery))

(deftest ^{:stratum 1} another-valid-recorded-grant-cannot-replace-the-independently-retained-issued-pin-test
  (fixtures/with-proposed check-independent-pin))

(deftest ^{:stratum 1} uncertain-original-creation-cannot-be-adopted-for-a-stop-by-name-test
  (fixtures/with-proposed check-uncertain-creation))

(comment
  (fixtures/with-proposed check-confirmed))
