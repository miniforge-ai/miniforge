;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-concurrency-test
  (:require [ai.miniforge.event-stream.commit-journal :as journal]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.commit-test-support :as f]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private caller-count 32)

(def ^{:stratum 0} ^:private timeout-ms 10000)

(defn- ^{:stratum 0} publish-new! [store scope]
  (journal/commit! store scope (f/draft)))

(defn- ^{:stratum 0} reenter! [store-ref nested-result scope event]
  (reset! nested-result (journal/commit! @store-ref scope event))
  event)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} concurrent-results [action]
  (->> (repeatedly caller-count #(future-call action))
       doall
       (mapv #(deref % timeout-ms ::timeout))))

(deftest ^{:stratum 1} storage-reentry-cannot-allocate-the-in-flight-sequence
  (let [store-ref (atom nil)
        nested-result (atom nil)
        store (journal/create (model/empty-state) (partial reenter! store-ref nested-result))
        scope [:workflow (random-uuid)]]
    (reset! store-ref store)
    (is (= 0 (:event/sequence-number (journal/commit! store scope (f/draft)))))
    (is (= :conflict (:anomaly/type @nested-result)))
    (is (= 1 (count (:committed @(:state store)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} concurrent-publication-is-contiguous-in-storage-order
  (let [receipts (atom [])
        store (journal/create (model/empty-state) (partial f/record! receipts))
        scope [:workflow (random-uuid)]
        results (concurrent-results (partial publish-new! store scope))
        sequences (mapv (comp :event/sequence-number second) @receipts)]
    (is (= (vec (range caller-count)) sequences))
    (is (= (set (map second @receipts)) (set results)))
    (is (= caller-count (count (:committed @(:state store)))))))

(deftest ^{:stratum 2} concurrent-duplicates-share-one-acknowledgment
  (let [receipts (atom [])
        store (journal/create (model/empty-state) (partial f/record! receipts))
        scope [:workflow (random-uuid)]
        draft (f/draft)
        results (concurrent-results (partial journal/commit! store scope draft))]
    (is (= 1 (count @receipts)))
    (is (= 1 (count (set results))))
    (is (= (second (first @receipts)) (first results)))
    (is (= 1 (get-in @(:state store) [:next-sequences scope])))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.commit-concurrency-test))
