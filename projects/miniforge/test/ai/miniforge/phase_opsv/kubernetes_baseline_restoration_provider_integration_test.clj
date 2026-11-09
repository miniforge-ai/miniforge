;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-confirmed [{:keys [runtime] :as fixture}]
  (let [[result calls] (fixtures/execute fixture)
        patch (first (fixtures/mutations calls))
        payload (get-in fixture [:recovery/proposal :kubernetes/request])
        observed (:effect/observed result)]
    (is (= :succeeded (:effect/state result)))
    (is (= result (effect/read-record (:effects-directory runtime) (:effect/id (:recovery/record fixture)))))
    (is (= 1 (count (fixtures/mutations calls))))
    (is (= :patch (:method (last calls))))
    (is (every? #(= :get (:method %)) (butlast calls)))
    (is (= (:body payload) (some-> patch :body json/parse-string)))
    (is (= "application/json-patch+json" (get-in patch [:headers "Content-Type"])))
    (is (= replica/original-count (:replicas/count observed)))
    (is (= "deployment-uid" (get-in observed [:kubernetes/resource :uid])))
    (is (not-any? #(contains? observed %) [:recovery/verified? :load/absent? :verification/passed?]))))

(defn- ^{:stratum 0} check-live-change [path value fixture]
  (let [changed (assoc-in fixture (into [:restoration/routes] path) value)
        [result calls] (fixtures/execute changed)]
    (is (not= :succeeded (:effect/state result)))
    (is (seq calls))
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

(defn- ^{:stratum 0} check-revoked-grant [fixture]
  (let [revoked (grant/revoke-stored! (:recovery/grants-directory fixture)
                                      (:recovery/issued-id fixture) :revocation/operator clock/now)
        [result calls] (fixtures/execute fixture)]
    (is (some? (:grant/revoked-at revoked)))
    (is (not= :succeeded (:effect/state result)))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-independent-pin [fixture]
  (when-let [rebound (fixtures/rebind! fixture (:recovery/proposal fixture))]
    (let [[result calls] (fixtures/execute (assoc rebound :recovery/issued-id (:recovery/issued-id fixture)))]
      (is (not= (:recovery/issued-id fixture) (:recovery/issued-id rebound)))
      (is (not= :succeeded (:effect/state result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-rebound-count [fixture]
  (let [proposal (:recovery/proposal fixture)
        payload (assoc-in (:kubernetes/request proposal) [:body 2 "value"] replica/trial-count)
        changed (-> proposal (assoc :kubernetes/request payload)
                    (assoc-in [:opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))]
    (when-let [rebound (fixtures/rebind! fixture changed)]
      (let [[result calls] (fixtures/execute rebound)]
        (is (not= :succeeded (:effect/state result)))
        (is (empty? (fixtures/mutations calls)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-original-count-restoration-acknowledges-only-the-patch-after-cancellation-test
  (fixtures/with-proposed check-confirmed))

(deftest ^{:stratum 1} fresh-physical-target-and-revision-are-required-before-restoration-dispatch-test
  (doseq [[path value] [[[inventory/deployment-path "metadata" "resourceVersion"] "changed-before-dispatch"]
                       [[inventory/deployment-path "metadata" "uid"] "foreign-deployment"]
                       [[native/cluster-path "metadata" "uid"] "foreign-cluster-root"]
                       [[inventory/deployment-path "spec" "replicas"] 99]]]
    (fixtures/with-proposed (partial check-live-change path value))))

(deftest ^{:stratum 1} revoked-current-plan-approval-prevents-all-restoration-provider-reads-test
  (fixtures/with-proposed check-current-approval))

(deftest ^{:stratum 1} malformed-runtime-and-caller-count-or-approval-cannot-reach-the-provider-test
  (doseq [transform [#(assoc % :approved? true)
                     #(assoc-in % [:input :replicas/count] 99)
                     #(update % :input dissoc :restoration/provider)
                     #(assoc-in % [:input :restoration/provider :kubernetes/context] "foreign-context")]]
    (fixtures/with-proposed (partial check-invalid-runtime transform))))

(deftest ^{:stratum 1} revoked-exact-recovery-authority-prevents-all-provider-calls-test
  (fixtures/with-proposed check-revoked-grant))

(deftest ^{:stratum 1} another-valid-recovery-grant-cannot-replace-the-independent-issued-pin-test
  (fixtures/with-proposed check-independent-pin))

(deftest ^{:stratum 1} rehashing-and-reissuing-candidate-count-cannot-replace-the-original-baseline-test
  (fixtures/with-proposed check-rebound-count))

(comment
  (fixtures/with-proposed check-confirmed))
