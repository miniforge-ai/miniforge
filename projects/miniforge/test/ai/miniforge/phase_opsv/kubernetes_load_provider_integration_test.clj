;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-provider-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-current [input]
  (let [[result calls] (fixtures/commit! input (fixtures/response input) dispatch/dispatch!)
        stored (effect/read-record (:authority-directory input) (:effect/id result))]
    (is (= :succeeded (:effect/state result)))
    (is (= result stored))
    (is (= (:proposal input) (:effect/proposal stored)))
    (is (= :granted (:effect/authority stored)))
    (is (= fixtures/created-uid (get-in result [:effect/observed :kubernetes/resource :uid])))
    (is (= 1 (count calls)))))

(defn- ^{:stratum 0} check-wire [input]
  (let [[result calls] (fixtures/apply! input (:record input) (fixtures/response input) dispatch/dispatch!)
        expected (get-in input [:proposal :kubernetes/request])
        options (first calls)]
    (is (= :succeeded (:effect/outcome result)))
    (is (= 1 (count calls)))
    (is (= :post (:method options)))
    (is (= "https://cluster.invalid/api/v1/namespaces/staging/pods" (:uri options)))
    (is (= (:body expected) (json/parse-string (:body options))))
    (is (= (:media-type expected) (get-in options [:headers "Content-Type"])))
    (is (= fixtures/revision (get-in result [:effect/observed :kubernetes/resource-version])))
    (is (= ["miniforge.ai/opsv-authorized-start"]
           (mapv #(get % "name") (get-in expected [:body "spec" "schedulingGates"]))))))

(defn- ^{:stratum 0} check-dispatch [input]
  (doseq [[callback outcome count] [[nil :invalid-input 0] [replica/refuse! :failed 0]
                                    [dispatch/twice! :succeeded 1] [dispatch/threaded! :failed 0]]]
    (let [[result calls] (fixtures/apply! input (:record input) (fixtures/response input) callback)]
      (is (= outcome (or (:anomaly/type result) (:effect/outcome result))))
      (is (= count (clojure.core/count calls))))))

(defn- ^{:stratum 0} check-deadline [input]
  (let [pending (CompletableFuture.)
        [result calls] (replica/with-preparation-budget 1
                         #(fixtures/apply! input (:record input) pending dispatch/dispatch!))]
    (is (= :unknown-outcome (:effect/outcome result)))
    (is (.isCancelled pending))
    (is (= 1 (count calls))))
  (let [[result calls] (replica/with-preparation-budget 0
                         #(fixtures/apply! input (:record input) (fixtures/response input) dispatch/dispatch!))]
    (is (= :failed (:effect/outcome result)))
    (is (empty? calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-load-provider-composes-with-real-pinned-durable-commit-test
  (fixtures/with-input check-current))

(deftest ^{:stratum 1} canonical-gated-pod-is-created-once-and-provider-identity-is-retained-test
  (fixtures/with-input check-wire))

(deftest ^{:stratum 1} required-current-dispatch-cannot-run-the-provider-twice-or-on-another-thread-test
  (fixtures/with-input check-dispatch))

(deftest ^{:stratum 1} deadline-cancels-the-original-future-and-expiry-never-submits-test
  (fixtures/with-input check-deadline))

(comment ::creation-receipt-not-traffic-gate-release-or-verified-cleanup)
