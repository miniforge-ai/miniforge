;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.prometheus-criteria-fixtures :as criteria]
            [ai.miniforge.phase-opsv.prometheus-criteria-integration-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-no-reads [configuration]
  (let [[result calls] (fixtures/observe! configuration (fixtures/responses))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (= {} (:anomaly/data result)))
    (is (empty? calls))))

(deftest ^{:stratum 0} actual-client-preserves-complete-windows-and-explicit-reductions-test
  (let [configuration (criteria/with-credentials)
        [result calls] (fixtures/observe! configuration (fixtures/responses))
        windows (:measurement/windows result)]
    (is (= (criteria/expected-observations) (:observations result)))
    (is (not (.contains (pr-str result) criteria/bearer-token)))
    (is (= (mapv :measurement/request (:measurement/criteria configuration)) (mapv second calls)))
    (is (= ["peak" "floor" "tail"] (mapv :criterion/id windows)))
    (is (= [:maximum :minimum :last] (mapv :measurement/reduction windows)))
    (is (every? #(= (criteria/observation) (:measurement/observation %)) windows))
    (is (= (select-keys configuration [:measurement/target :measurement/service :measurement/label-names])
           (:measurement/declaration result)))
    (is (= #{:observations :measurement/declaration :measurement/windows} (set (keys result))))))

(deftest ^{:stratum 0} failed-or-unbound-metrics-never-return-partial-observations-test
  (let [configuration (criteria/configuration)
        responses (fixtures/responses)]
    (doseq [field ["cluster" "namespace" "service"]
            index [0 1 2]]
      (let [unbound (fixtures/unbound (nth responses index) field)
            [result calls] (fixtures/observe! configuration (assoc responses index unbound))]
        (is (= :conflict (:anomaly/type result)))
        (is (= {} (:anomaly/data result)))
        (is (= (inc index) (count calls)))))
    (doseq [index [0 1 2]]
      (let [[result calls] (fixtures/observe! configuration (assoc responses index (Exception. "opaque-metric-value")))]
        (is (anomaly/anomaly? result))
        (is (= {} (:anomaly/data result)))
        (is (= (inc index) (count calls)))))))

(deftest ^{:stratum 0} incomplete-sample-grid-never-produces-a-numeric-criterion-test
  (let [responses (assoc-in (fixtures/responses) [1 "data" "result" 0 "values"] [[100 "3"]])
        [result calls] (fixtures/observe! (criteria/configuration) responses)]
    (is (anomaly/anomaly? result))
    (is (= {} (:anomaly/data result)))
    (is (= 2 (count calls)))))

(deftest ^{:stratum 0} fatal-and-interrupted-acquisition-remain-fatal-and-interrupted-test
  (doseq [failure [(AssertionError.) (InterruptedException.)]]
    (let [responses (assoc (fixtures/responses) 0 failure)
          operation (partial fixtures/observe! (criteria/configuration) responses)
          [thrown flagged?] (failures/interrupted-result operation)]
      (is (identical? failure thrown))
      (is (= (instance? InterruptedException failure) flagged?)))))

(deftest ^{:stratum 0} effective-endpoint-cap-does-not-rewrite-declared-request-budgets-test
  (let [configuration (criteria/with-endpoint-cap 4)
        [result calls] (fixtures/observe! configuration (fixtures/responses))
        declared (mapv :measurement/request (:measurement/criteria configuration))]
    (is (= (criteria/expected-observations) (:observations result)))
    (is (= 3 (count calls)))
    (is (= declared (mapv :measurement/request (:measurement/windows result))))
    (is (= declared (mapv second calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} later-over-endpoint-cap-criterion-starts-no-reads-test
  (let [configuration (criteria/with-endpoint-cap 3)]
    (assert-no-reads configuration)))

(deftest ^{:stratum 1} all-criterion-declarations-are-admitted-before-the-first-query-test
  (let [configuration (criteria/configuration)
        deferred (lazy-seq (throw (ex-info "must not realize metadata" {})))]
    (doseq [changed [(assoc-in configuration [:measurement/criteria 1 :criterion/id] "peak")
                     (assoc-in configuration [:measurement/criteria 1 :measurement/reduction] :guessed)
                     (assoc-in configuration [:measurement/criteria 1 :measurement/request :window/start-seconds] 120)
                     (assoc-in configuration [:measurement/criteria 1 :measurement/request :measurement/max-samples] 2)
                     (update configuration :measurement/target dissoc :workload-uid)
                     (assoc-in configuration [:measurement/label-names :service] "namespace")
                     (with-meta configuration {:hidden deferred})]]
      (assert-no-reads changed))
    (is (not (realized? deferred)))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.prometheus-criteria-integration-test))
