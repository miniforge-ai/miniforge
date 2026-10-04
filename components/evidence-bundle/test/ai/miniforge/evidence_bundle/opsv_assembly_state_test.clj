;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-assembly-state-test
  (:require [ai.miniforge.evidence-bundle.opsv-assembly :as assembly]
            [ai.miniforge.evidence-bundle.opsv-assembly-state :as state]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} next-id! [ids]
  (let [[before _after] (swap-vals! ids next)]
    (first before)))

(deftest ^{:stratum 0} insertion-preserves-every-occupied-key
  (let [record (state/initial-record :bundle :workflow)]
    (is (= {:bundle record} (state/insert-if-absent {} :bundle record)))
    (doseq [retained [nil record]]
      (let [before {:bundle retained}]
        (is (identical? before (state/insert-if-absent before :bundle record)))))))

(deftest ^{:stratum 0} inactive-records-do-not-read-material-or-change-state
  (let [record (assoc (state/initial-record :bundle :workflow) :opsv.assembly/status :finalized)
        unread (lazy-seq (throw (AssertionError. "Inactive assembly must not read material")))
        material {:opsv/event-refs unread}]
    (doseq [before [{} {:bundle nil} {:bundle record}]]
      (is (identical? before (state/accumulate before :bundle material))))))

(deftest ^{:stratum 0} references-preserve-scalar-and-collection-normalization
  (let [id (random-uuid)
        record (state/initial-record :bundle :workflow)
        keys [:opsv/event-refs :opsv/artifact-refs :opsv/grant-refs]]
    (doseq [value [nil id [id id] (list id) #{id}]]
      (let [material (zipmap keys (repeat value))
            after (state/accumulate {:bundle record} :bundle material)
            expected (if (nil? value) #{} #{id})]
        (doseq [key keys]
          (is (= expected (get-in after [:bundle key]))))
        (is (= after (state/accumulate after :bundle material)))))))

(deftest ^{:stratum 0} effect-maps-remain-single-references
  (let [effect {:evidence/effect-id (random-uuid)}
        record (state/initial-record :bundle :workflow)
        material {:opsv/actuation {:governed-effects effect}}
        after (state/accumulate {:bundle record} :bundle material)]
    (is (= #{effect} (get-in after [:bundle :opsv/governed-effects])))
    (is (= after (state/accumulate after :bundle material)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} allocation-retries-collisions-without-overwriting
  (let [occupied (random-uuid)
        fresh (random-uuid)
        ids (atom [occupied fresh])
        record (state/initial-record occupied :original-workflow)
        store (atom {occupied record})
        allocated (with-redefs [random-uuid (partial next-id! ids)]
                    (assembly/allocate! store :new-workflow))]
    (is (= fresh (:evidence-bundle/id allocated)))
    (is (= :new-workflow (:evidence-bundle/workflow-id allocated)))
    (is (identical? record (get @store occupied)))
    (is (= {occupied record fresh allocated} @store))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.opsv-assembly-state-test))
