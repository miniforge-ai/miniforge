;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-knowledge-test
  (:require [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.workflow.cleanup-knowledge :as cleanup]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail! [failure & _] (throw failure))

(defn- ^{:stratum 0} learning [id] {:zettel/id id})

(defn- ^{:stratum 0} promote! [calls _store id _opts]
  (swap! calls conj id)
  (when (= :first id) (throw (Exception. "fixture promotion failure"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} promotion-failure-retains-identity-and-continues
  (let [calls (atom []) records (mapv learning [:first :second])]
    (with-redefs [knowledge/list-learnings (constantly records)
                  knowledge/promote-learning (partial promote! calls)]
      (is (= [{:zettel/id :first :error "fixture promotion failure"}]
             (cleanup/promote-mature-learnings! :store))))
    (is (= [:first :second] @calls))))

(deftest ^{:stratum 1} listing-failure-is-reported-without-a-learning-id
  (with-redefs [knowledge/list-learnings (partial fail! (Exception. "fixture list failure"))]
    (is (= [{:error "fixture list failure"}] (cleanup/promote-mature-learnings! :store)))))

(deftest ^{:stratum 1} fatal-listing-does-not-become-an-ordinary-warning
  (let [failure (Error. "fixture fatal listing")]
    (with-redefs [knowledge/list-learnings (partial fail! failure)]
      (try+ (cleanup/promote-mature-learnings! :store)
        (is false "fatal failure must propagate")
        (catch Error caught (is (identical? failure caught)))))))

(comment ::knowledge-cleanup-regressions)
