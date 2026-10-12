;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.journal-recovery-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.journal-recovery :as recovery]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.journal-record :as record]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stored [scope position]
  (record/wrap-event scope (assoc (support/draft) :event/sequence-number position)))

(defn- ^{:stratum 0} invalid? [records]
  (anomaly/anomaly? (recovery/recover-state records)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} recovery-is-independent-of-enumeration-order
  (let [scope [:workflow (random-uuid)]
        other [:pr (second scope)]
        values [(stored scope 1) (stored other 0) (stored scope 0)]
        state (recovery/recover-state values)]
    (is (= {scope 2 other 1} (:next-sequences state)))
    (is (= (set (map record/event values)) (set (map :event (vals (:committed state))))))
    (is (= state (recovery/recover-state (reverse values))))))

(deftest ^{:stratum 1} recovery-rejects-gaps-and-duplicate-identities
  (let [scope [:workflow (random-uuid)]
        zero (stored scope 0)
        one (stored scope 1)]
    (is (= (model/empty-state) (recovery/recover-state [])))
    (is (invalid? [one]))
    (is (invalid? [zero (stored scope 2)]))
    (is (invalid? [zero (stored scope 0)]))
    (is (invalid? [zero zero]))
    (is (invalid? [zero (record/wrap-event [:pr (random-uuid)] (record/event zero))]))))

(deftest ^{:stratum 1} recovery-validates-storage-contract
  (let [value (stored [:workflow (random-uuid)] 0)
        event-path [:artifact/content :journal/event]
        corruptions [(assoc value :artifact/id (random-uuid))
                     (assoc value :artifact/type :manifest)
                     (assoc value :artifact/version "future")
                     (assoc value :artifact/metadata {:extra true})
                     (assoc-in value [:artifact/content :journal/scope] [:workflow nil])
                     (assoc-in value (conj event-path :event/sequence-number) -1)
                     (update-in value event-path dissoc :event/timestamp)
                     (assoc value :artifact/content nil)]]
    (doseq [corrupted corruptions] (is (invalid? [corrupted])))))

(comment
  (recovery/recover-state []))
