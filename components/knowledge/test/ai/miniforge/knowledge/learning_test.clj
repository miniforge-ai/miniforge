;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns ai.miniforge.knowledge.learning-test
  "Tests for the learning lifecycle — capture and promotion.

   The first cluster covers `promote-learning`'s contract around the
   per-revision trust stamp added in #836:

     - A `:learning` zettel goes into the store untrusted (the
       constructor default).
     - `promote-learning` flips the type to `:rule` AND stamps
       `:zettel/trust-level :trusted` on the promoted revision.
     - Trying to promote a non-`:learning` zettel returns nil
       (existing behaviour, pinned).

   The second cluster pins the rotation interaction: after promotion,
   editing the rule's content via `update-zettel` MUST reset trust to
   `:untrusted` (closes the gap that motivated #836)."
  (:require
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.knowledge.zettel    :as zettel]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures.

(def ^:dynamic *store* nil)

(defn- with-store [f]
  (binding [*store* (knowledge/create-store)]
    (f)))

(use-fixtures :each with-store)

(defn- new-learning
  "Build + persist a fresh `:learning` zettel; return the persisted
   value so callers can drive `promote-learning` against its id."
  []
  (let [z (knowledge/create-zettel
           "auto-captured-learning"
           "Captured learning"
           "# Body\n\nA pattern observed during execution."
           :learning)]
    (knowledge/put-zettel *store* z)
    (knowledge/get-zettel *store* (:zettel/id z))))

;------------------------------------------------------------------------------ Layer 1
;; promote-learning trust stamp.

(deftest test-fresh-learning-defaults-to-untrusted
  (testing "create-zettel + put → the persisted :learning carries :zettel/trust-level :untrusted"
    (let [l (new-learning)]
      (is (= :learning  (:zettel/type l)))
      (is (= :untrusted (:zettel/trust-level l))))))

(deftest test-promote-learning-stamps-trusted-on-promoted-revision
  (testing "promote-learning flips type to :rule AND stamps :zettel/trust-level :trusted"
    (let [l        (new-learning)
          promoted (knowledge/promote-learning *store* (:zettel/id l)
                                               {:dewey "210"
                                                :reviewed-by "agent:reviewer"})]
      (is (= :rule    (:zettel/type promoted)))
      (is (= :trusted (:zettel/trust-level promoted))
          "promotion is the only path that grants :trusted"))))

(deftest test-promote-learning-on-non-learning-returns-nil
  (testing "promote-learning is a no-op on a non-:learning zettel — existing contract"
    (let [rule (knowledge/create-zettel "200-already-a-rule" "Already a Rule"
                                        "Body." :rule)
          _    (knowledge/put-zettel *store* rule)
          r    (knowledge/promote-learning *store* (:zettel/id rule))]
      (is (nil? r)))))

;------------------------------------------------------------------------------ Layer 2
;; Trust drops on a content edit AFTER promotion (the closure of #836).

(deftest test-content-edit-on-promoted-rule-drops-trust
  (testing "update-zettel on a content-bearing field of a :trusted rule resets to :untrusted"
    ;; The gap #836 closes: a promoted-and-trusted rule must NOT
    ;; carry trust onto a new revision when its content rotates.
    ;; Re-promotion is the only path back.
    (let [l        (new-learning)
          promoted (knowledge/promote-learning *store* (:zettel/id l))
          edited   (zettel/update-zettel promoted
                                         {:zettel/content "# Edited body"})]
      (is (= :trusted   (:zettel/trust-level promoted))
          "the original promotion stamped :trusted")
      (is (not= (:zettel/revision-id promoted) (:zettel/revision-id edited))
          "content edit rotated revision-id")
      (is (= :untrusted (:zettel/trust-level edited))
          "rotation reset trust on the new revision"))))

(deftest test-operational-edit-on-promoted-rule-preserves-trust
  (testing "update-zettel on operational metadata only preserves the :trusted stamp"
    (let [l        (new-learning)
          promoted (knowledge/promote-learning *store* (:zettel/id l))
          edited   (zettel/update-zettel promoted
                                         {:fleet/share-scope :team})]
      (is (= (:zettel/revision-id promoted) (:zettel/revision-id edited))
          "operational edit did not rotate revision-id")
      (is (= :trusted (:zettel/trust-level edited))
          "trust preserved across operational-only edit"))))
