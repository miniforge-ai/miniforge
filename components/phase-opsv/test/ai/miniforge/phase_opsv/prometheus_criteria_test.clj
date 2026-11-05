;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria-test
  (:require [ai.miniforge.phase-opsv.boundary.prometheus-client :as client]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.prometheus-criteria :as criteria]
            [ai.miniforge.phase-opsv.prometheus-criteria-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected-acquisition [calls _settings _request]
  (swap! calls inc))

(defn- ^{:stratum 0} interrupted-observation! []
  (.interrupt (Thread/currentThread))
  (phase/observe-prometheus-criteria! nil))

(deftest ^{:stratum 0} reductions-are-declared-and-original-windows-are-preserved-test
  (let [observed (fixtures/observation)]
    (doseq [[reduction expected] [[:maximum 3.0] [:minimum 1.0] [:last 2.0]]]
      (let [criterion (fixtures/criterion "sample" reduction)
            record (criteria/record criterion observed)]
        (is (= expected (:criterion/observed record)))
        (is (= (:measurement/request criterion) (:measurement/request record)))
        (is (= reduction (:measurement/reduction record)))
        (is (= "sample" (:criterion/id record)))
        (is (identical? observed (:measurement/observation record)))))))

(deftest ^{:stratum 0} observations-are-keyed-by-declared-identifiers-test
  (let [configuration (fixtures/configuration)
        records (fixtures/records configuration)
        result (criteria/result configuration records)]
    (is (= (fixtures/expected-observations) (:observations result)))
    (is (identical? records (:measurement/windows result)))
    (is (= (select-keys configuration [:measurement/target :measurement/service :measurement/label-names])
           (:measurement/declaration result)))
    (is (= #{:observations :measurement/declaration :measurement/windows} (set (keys result))))))

(deftest ^{:stratum 0} endpoint-credentials-are-not-measurement-declarations-test
  (let [configuration (fixtures/with-credentials)
        records (fixtures/records configuration)
        result (criteria/result configuration records)]
    (is (not (.contains (pr-str result) fixtures/bearer-token)))
    (is (not (contains? (:measurement/declaration result) :measurement/prometheus)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-criteria-start-no-reads-or-deferred-realization-test
  (let [calls (atom 0)
        deferred (lazy-seq (throw (ex-info "must not realize criteria" {})))]
    (with-redefs [client/observe-window! (partial unexpected-acquisition calls)]
      (doseq [configuration [nil (Object.) deferred]]
        (let [result (phase/observe-prometheus-criteria! configuration)]
          (is (= :invalid-input (:anomaly/type result)))
          (is (= {} (:anomaly/data result)))))
      (is (zero? @calls))
      (is (not (realized? deferred))))))

(deftest ^{:stratum 1} already-interrupted-observation-starts-no-reads-test
  (let [[thrown flagged?] (failures/interrupted-result interrupted-observation!)]
    (is (instance? InterruptedException thrown))
    (is flagged?)
    (is (false? (.isInterrupted (Thread/currentThread))))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.prometheus-criteria-test))
