;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-provider-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-replica-recovery-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as request]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} native-provider-runs-inside-the-real-durable-current-commit-test
  (let [input (fixtures/input)
        [committed calls] (fixtures/commit! input (fixtures/response input) fixtures/dispatch!)
        {:keys [result stored]} committed]
    (is (= :succeeded (:effect/state result)))
    (is (= result stored))
    (is (= :granted (:effect/authority stored)))
    (is (= (:proposal input) (:effect/proposal stored)))
    (is (= request/trial-count (get-in stored [:effect/observed :replicas/count])))
    (is (= 1 (count calls)))))

(deftest ^{:stratum 0} canonical-native-patch-is-sent-once-and-confirmed-test
  (let [input (fixtures/input)
        [result calls] (fixtures/apply! (:runtime input) (:record input) (fixtures/response input) fixtures/dispatch!)
        options (first calls)
        expected (get-in input [:proposal :kubernetes/request])]
    (is (= :succeeded (:effect/outcome result)))
    (is (= 1 (count calls)))
    (is (= :patch (:method options)))
    (is (= "https://cluster.invalid/apis/apps/v1/namespaces/staging/deployments/catalog" (:uri options)))
    (is (= (:media-type expected) (get-in options [:headers "Content-Type"])))
    (is (= (:body expected) (json/parse-string (:body options))))
    (is (= request/trial-count (get-in result [:effect/observed :replicas/count])))
    (is (= request/fresh-revision (get-in result [:effect/observed :kubernetes/resource-version])))
    (is (= "deployment-uid" (get-in result [:effect/observed :kubernetes/resource :uid])))))

(deftest ^{:stratum 0} required-trusted-dispatch-refuses-or-admits-at-most-one-attempt-test
  (let [input (fixtures/input)]
    (doseq [[dispatch expected count] [[nil :invalid-input 0] [fixtures/refuse! :failed 0]
                                      [fixtures/twice! :succeeded 1] [dispatch/threaded! :failed 0]
                                      [dispatch/after-result! :succeeded 1]]]
      (let [[result calls] (fixtures/apply! (:runtime input) (:record input) (fixtures/response input) dispatch)]
        (is (= expected (or (:anomaly/type result) (:effect/outcome result))))
        (is (= count (clojure.core/count calls)))))))

(deftest ^{:stratum 0} escaped-provider-thunk-is-closed-after-dispatch-returns-test
  (let [input (fixtures/input)
        retained (atom nil)
        refusal (dispatch/refusal)
        callback (partial dispatch/retain! retained refusal)
        [result calls] (fixtures/apply! (:runtime input) (:record input) (fixtures/response input) callback)]
    (is (identical? refusal result))
    (is (nil? (@retained)))
    (is (empty? calls))))

(deftest ^{:stratum 0} exact-current-claim-runtime-scope-and-resource-bindings-are-required-test
  (let [input (fixtures/input)]
    (doseq [[path value] [[[:effect/state] :proposed] [[:effect/authority] :unenforced]
                         [[:effect/grant-id] nil] [[:effect/envelope-id] nil]
                         [[:effect/class] :effect/load-test] [[:effect/proposal :effect/id] (random-uuid)]
                         [[:effect/proposal :opsv/experiment :opsv/payload-hash] (hash/content-hash :other)]
                         [[:effect/proposal :opsv/experiment :kubernetes/context] "foreign"]
                         [[:effect/proposal :opsv/experiment :opsv/resources 0 :uid] "replacement"]
                         [[:effect/proposal :opsv/experiment :runtime/target :namespace] "foreign"]
                         [[:effect/proposal :opsv/experiment :opsv/operation] :start-load]]]
      (let [record (assoc-in (:record input) path value)
            [result calls] (fixtures/apply! (:runtime input) record (fixtures/response input) fixtures/dispatch!)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? calls))))))

(deftest ^{:stratum 0} changed-http-shapes-cannot-become-arbitrary-provider-effects-test
  (let [input (fixtures/input)
        original (get-in input [:proposal :kubernetes/request])]
    (doseq [payload [(assoc original :method :delete) (assoc original :path "/api/v1/namespaces/staging")
                     (assoc-in original [:body 0 "value"] "replacement")
                     (assoc original :headers {}) (update original :body pop)]]
      (let [record (-> (:record input)
                       (assoc-in [:effect/proposal :kubernetes/request] payload)
                       (assoc-in [:effect/proposal :opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))
            [result calls] (fixtures/apply! (:runtime input) record (fixtures/response input) fixtures/dispatch!)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? calls))))))

(deftest ^{:stratum 0} unconfirmed-native-responses-never-retry-or-claim-success-test
  (let [input (fixtures/input)
        native (fixtures/native-response input)]
    (doseq [response [(assoc-in native ["metadata" "uid"] "replacement")
                      (assoc-in native ["metadata" "name"] "foreign")
                      (assoc-in native ["spec" "replicas"] 0)
                      (assoc-in native ["metadata" "resourceVersion"] nil)
                      {"kind" "Status"}]]
      (let [pending (wire/completed (wire/response (json/generate-string response)))
            [result calls] (fixtures/apply! (:runtime input) (:record input) pending fixtures/dispatch!)]
        (is (= :unknown-outcome (:effect/outcome result)))
        (is (= 1 (count calls)))))))

(deftest ^{:stratum 0} strict-json-encoding-and-status-failures-retain-uncertainty-test
  (let [input (fixtures/input)]
    (doseq [[text options] [["{} {}" {}] ["{\"kind\":1,\"kind\":2}" {}]
                            ["{}" {:status 409}] ["{}" {:status 404}]
                            ["{}" {:encodings ["gzip"]}]]]
      (let [pending (wire/completed (wire/response text options))
            [result calls] (fixtures/apply! (:runtime input) (:record input) pending fixtures/dispatch!)]
        (is (= :unknown-outcome (:effect/outcome result)))
        (is (= 1 (count calls)))))))

(deftest ^{:stratum 0} explicit-runtime-target-and-https-settings-are-required-before-dispatch-test
  (let [input (fixtures/input)]
    (doseq [[key value] [[:kubernetes/base-url "http://cluster.invalid"]
                         [:kubernetes/base-url "https://cluster.invalid/arbitrary-path"]
                         [:kubernetes/cluster-id "foreign"] [:kubernetes/context "foreign"]
                         [:kubernetes/namespace "foreign"] [:kubernetes/timeout-ms 0]]]
      (let [runtime (assoc (:runtime input) key value)
            [result calls] (fixtures/apply! runtime (:record input) (fixtures/response input) fixtures/dispatch!)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? calls))))))

(deftest ^{:stratum 0} response-byte-limit-and-scope-duration-bound-the-provider-attempt-test
  (let [input (fixtures/input)
        runtime (assoc (:runtime input) :kubernetes/max-response-bytes 1)
        record (assoc-in (:record input) [:effect/proposal :opsv/experiment :opsv/duration-ms] 500)
        [result calls] (fixtures/apply! runtime record (fixtures/response input) fixtures/dispatch!)]
    (is (= :unknown-outcome (:effect/outcome result)))
    (is (= 1 (count calls)))
    (is (<= 1 (:timeout (first calls)) 500))))

(deftest ^{:stratum 0} deadline-cancels-the-original-http-future-without-retrying-test
  (let [input (fixtures/input)
        pending (CompletableFuture.)
        operation (partial fixtures/apply! (:runtime input) (:record input) pending fixtures/dispatch!)
        [result calls] (fixtures/with-preparation-budget 1 operation)]
    (is (= :unknown-outcome (:effect/outcome result)))
    (is (.isCancelled pending))
    (is (= 1 (count calls)))))

(deftest ^{:stratum 0} already-interrupted-owner-never-submits-the-mutating-request-test
  (let [input (fixtures/input)]
    (try
      (let [[result calls] (fixtures/apply! (:runtime input) (:record input) (fixtures/response input)
                                           fixtures/interrupting-dispatch!)
            interrupted? (.isInterrupted (Thread/currentThread))]
        (is (= :failed (:effect/outcome result)))
        (is (empty? calls))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 0} request-preparation-cannot-spend-the-deadline-and-still-submit-test
  (let [input (fixtures/input)
        operation (partial fixtures/apply! (:runtime input) (:record input) (fixtures/response input) fixtures/dispatch!)
        [result calls] (fixtures/with-preparation-budget 0 operation)]
    (is (= :failed (:effect/outcome result)))
    (is (empty? calls))))

(deftest ^{:stratum 0} submission-uses-only-the-budget-remaining-after-request-preparation-test
  (let [input (fixtures/input)
        remaining 3000
        operation (partial fixtures/apply! (:runtime input) (:record input) (fixtures/response input) fixtures/dispatch!)
        [result calls] (fixtures/with-preparation-budget remaining operation)]
    (is (= :succeeded (:effect/outcome result)))
    (is (= remaining (:timeout (first calls))))
    (is (= 1 (count calls)))))

(deftest ^{:stratum 0} restoration-sends-the-original-count-with-fresh-native-preconditions-test
  (let [input (recovery/input)
        proposal (fixtures/proposal input)
        record (fixtures/record proposal)
        [result calls] (fixtures/apply! (fixtures/settings) record (fixtures/response input) fixtures/dispatch!)
        operations (json/parse-string (:body (first calls)))]
    (is (= :succeeded (:effect/outcome result)))
    (is (= request/original-count (get (last operations) "value")))
    (is (= request/fresh-revision (get (second operations) "value")))))

(comment ::native-preparation-durable-current-claim-and-bounded-http-compose-for-real)
