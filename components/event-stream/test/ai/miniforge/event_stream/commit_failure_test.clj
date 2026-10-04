;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-failure-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-journal :as journal]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.commit-test-support :as f]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-port! [failure _scope _event]
  (throw+ failure))

(defn- ^{:stratum 0} uncertain-record! [receipts scope event]
  (f/record! receipts scope event)
  nil)

(defn- ^{:stratum 0} committed-state [store]
  (select-keys @(:state store) [:next-sequences :committed]))

(defn- ^{:stratum 0} observe-critical! [store scope]
  (try+
    (journal/commit! store scope (f/draft))
    nil
    (catch Object caught [caught (Thread/interrupted)])
    (finally (Thread/interrupted))))

(defn- ^{:stratum 0} call-while-interrupted! [store scope]
  (.interrupt (Thread/currentThread))
  (try+
    [(journal/commit! store scope (f/draft)) (Thread/interrupted)]
    (finally (Thread/interrupted))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} already-interrupted-call-does-not-touch-storage
  (let [receipts (atom [])
        store (journal/create (model/empty-state) (partial f/record! receipts))
        scope [:workflow (random-uuid)]
        [result interrupted?] (call-while-interrupted! store scope)]
    (is (anomaly/anomaly? result))
    (is interrupted?)
    (is (empty? @receipts))
    (is (= (model/empty-state) @(:state store)))))

(deftest ^{:stratum 1} ordinary-and-structured-throws-fence-the-journal
  (let [failure (anomaly/anomaly :unavailable "test" {})]
    (doseq [thrown [(ex-info "test" {}) failure]]
      (let [store (journal/create (model/empty-state) (partial throw-port! thrown))
            scope [:workflow (random-uuid)]
            result (journal/commit! store scope (f/draft))]
        (is (anomaly/anomaly? result))
        (when (map? thrown) (is (= failure result)))
        (is (= (model/empty-state) (committed-state store)))
        (is (= :unavailable (:anomaly/type (journal/commit! store scope (f/draft)))))))))

(deftest ^{:stratum 1} uncertain-write-must-not-be-retried-with-a-reused-sequence
  (let [receipts (atom [])
        store (journal/create (model/empty-state) (partial uncertain-record! receipts))
        scope [:workflow (random-uuid)]]
    (is (anomaly/anomaly? (journal/commit! store scope (f/draft))))
    (is (anomaly/anomaly? (journal/commit! store scope (f/draft))))
    (is (= 1 (count @receipts)))
    (is (= (model/empty-state) (committed-state store)))))

(deftest ^{:stratum 1} direct-and-wrapped-critical-failures-remain-critical
  (doseq [critical [(AssertionError. "test") (InterruptedException. "test")]]
    (doseq [thrown [critical (ex-info "wrapper" {} critical)]]
      (let [store (journal/create (model/empty-state) (partial throw-port! thrown))
            scope [:workflow (random-uuid)]
            [caught interrupted?] (observe-critical! store scope)]
        (is (identical? critical caught))
        (is (= (instance? InterruptedException critical) interrupted?))
        (is (= (model/empty-state) (committed-state store)))
        (is (not (contains? @(:state store) :writing?)))
        (is (= :unavailable (:anomaly/type (journal/commit! store scope (f/draft)))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.commit-failure-test))
