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
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.knowledge.pattern-detection-test
  "Tests for recurring pattern detection and cross-execution synthesis."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.knowledge.learning :as learning]
   [ai.miniforge.knowledge.store :as store]))

;------------------------------------------------------------------------------ Helpers

(defn- fresh-store
  "Create a fresh in-memory knowledge store."
  []
  (store/create-store))

(defn- seed-learnings!
  "Seed a store with n learnings sharing the given tags.
   Returns the store."
  [store n tags & {:keys [agent confidence]
                   :or {agent :implementer confidence 0.7}}]
  (doseq [i (range n)]
    (learning/capture-learning
     store
     {:type :inner-loop
      :agent agent
      :title (str "Learning " i " about " (name (first tags)))
      :content (str "Content for learning " i)
      :tags tags
      :confidence confidence}))
  store)

;------------------------------------------------------------------------------ Layer 0
;; detect-recurring-patterns

(deftest detect-recurring-patterns-empty-store
  (testing "Empty store returns no patterns"
    (let [store (fresh-store)]
      (is (empty? (knowledge/detect-recurring-patterns store))))))

(deftest detect-recurring-patterns-below-threshold
  (testing "Fewer than 3 learnings sharing a tag returns no patterns"
    (let [store (fresh-store)]
      (seed-learnings! store 2 [:clojure :protocol])
      (is (empty? (knowledge/detect-recurring-patterns store))))))

(deftest detect-recurring-patterns-at-threshold
  (testing "Exactly 3 learnings sharing a tag returns a pattern"
    (let [store (fresh-store)]
      (seed-learnings! store 3 [:clojure :protocol])
      (let [patterns (knowledge/detect-recurring-patterns store)]
        (is (= 2 (count patterns))
            "Both :clojure and :protocol should appear as patterns")
        (is (every? #(= 3 (:count %)) patterns))
        (is (every? #(= 3 (count (:learnings %))) patterns))))))

(deftest detect-recurring-patterns-above-threshold
  (testing "5 learnings sharing a tag returns pattern with count 5"
    (let [store (fresh-store)]
      (seed-learnings! store 5 [:namespace])
      (let [patterns (knowledge/detect-recurring-patterns store)]
        (is (= 1 (count patterns)))
        (is (= :namespace (:tag (first patterns))))
        (is (= 5 (:count (first patterns))))))))

(deftest detect-recurring-patterns-excludes-noise-tags
  (testing "Default exclude-tags filters :inner-loop and :repair"
    (let [store (fresh-store)]
      ;; These learnings only have excluded tags
      (seed-learnings! store 5 [:inner-loop :repair])
      (is (empty? (knowledge/detect-recurring-patterns store))))))

(deftest detect-recurring-patterns-custom-threshold
  (testing "Custom min-occurrences is respected"
    (let [store (fresh-store)]
      (seed-learnings! store 2 [:rare-pattern])
      (is (empty? (knowledge/detect-recurring-patterns store)))
      (let [patterns (knowledge/detect-recurring-patterns
                      store {:min-occurrences 2})]
        (is (= 1 (count patterns)))
        (is (= :rare-pattern (:tag (first patterns))))))))

(deftest detect-recurring-patterns-custom-exclude-tags
  (testing "Custom exclude-tags overrides defaults"
    (let [store (fresh-store)]
      (seed-learnings! store 4 [:inner-loop :real-tag])
      ;; Default excludes :inner-loop, so only :real-tag shows
      (let [default-patterns (knowledge/detect-recurring-patterns store)]
        (is (= 1 (count default-patterns)))
        (is (= :real-tag (:tag (first default-patterns)))))
      ;; Custom excludes nothing, so both show
      (let [all-patterns (knowledge/detect-recurring-patterns
                          store {:exclude-tags #{}})]
        (is (= 2 (count all-patterns)))))))

(deftest detect-recurring-patterns-sorted-by-frequency
  (testing "Patterns are sorted by count descending"
    (let [store (fresh-store)]
      (seed-learnings! store 5 [:frequent])
      (seed-learnings! store 3 [:moderate])
      (let [patterns (knowledge/detect-recurring-patterns store)]
        (is (= [:frequent :moderate]
               (mapv :tag patterns)))))))

(deftest detect-recurring-patterns-learning-summaries
  (testing "Each pattern includes learning summaries with expected keys"
    (let [store (fresh-store)]
      (seed-learnings! store 3 [:protocol])
      (let [patterns (knowledge/detect-recurring-patterns store)
            learning-summaries (:learnings (first patterns))]
        (is (= 3 (count learning-summaries)))
        (doseq [summary learning-summaries]
          (is (contains? summary :id))
          (is (contains? summary :uid))
          (is (contains? summary :title))
          (is (contains? summary :confidence)))))))

;------------------------------------------------------------------------------ Layer 1
;; synthesize-recurring-patterns!

(deftest synthesize-patterns-no-patterns
  (testing "No patterns means 0 synthesized"
    (let [store (fresh-store)]
      (seed-learnings! store 2 [:not-enough])
      (is (= 0 (knowledge/synthesize-recurring-patterns! store))))))

(deftest synthesize-patterns-creates-meta-loop-learnings
  (testing "Patterns above threshold produce meta-loop learnings"
    (let [store (fresh-store)]
      (seed-learnings! store 4 [:protocol])
      (let [count-before (count (learning/list-learnings store))
            synthesized (knowledge/synthesize-recurring-patterns! store)
            count-after (count (learning/list-learnings store))]
        (is (= 1 synthesized))
        (is (= (+ count-before 1) count-after))
        ;; The new learning should be tagged :meta-loop :pattern
        (let [meta-learnings (learning/list-learnings store {:agent nil})
              pattern-learning (first (filter #(some #{:pattern} (:zettel/tags %))
                                             meta-learnings))]
          (is (some? pattern-learning))
          (is (some #{:meta-loop} (:zettel/tags pattern-learning)))
          (is (some #{:protocol} (:zettel/tags pattern-learning))))))))

(deftest synthesize-patterns-idempotent
  (testing "Second call produces 0 new patterns"
    (let [store (fresh-store)]
      (seed-learnings! store 4 [:protocol])
      (is (= 1 (knowledge/synthesize-recurring-patterns! store)))
      (is (= 0 (knowledge/synthesize-recurring-patterns! store))))))

(deftest synthesize-patterns-multiple-tags
  (testing "Multiple distinct patterns each produce a learning"
    (let [store (fresh-store)]
      (seed-learnings! store 3 [:alpha])
      (seed-learnings! store 3 [:beta])
      (let [synthesized (knowledge/synthesize-recurring-patterns! store)]
        (is (= 2 synthesized))))))

(deftest synthesize-patterns-content-includes-learning-titles
  (testing "Synthesized learning content includes titles of related learnings"
    (let [store (fresh-store)]
      (seed-learnings! store 3 [:namespace])
      (knowledge/synthesize-recurring-patterns! store)
      (let [all (learning/list-learnings store)
            pattern-learning (first (filter #(some #{:pattern} (:zettel/tags %)) all))]
        (is (some? pattern-learning))
        (is (re-find #"Learning 0" (:zettel/content pattern-learning)))
        (is (re-find #"Learning 1" (:zettel/content pattern-learning)))
        (is (re-find #"Learning 2" (:zettel/content pattern-learning)))))))

(deftest synthesize-patterns-high-confidence
  (testing "Synthesized meta-loop learnings have 0.85 confidence"
    (let [store (fresh-store)]
      (seed-learnings! store 3 [:quality])
      (knowledge/synthesize-recurring-patterns! store)
      (let [all (learning/list-learnings store)
            pattern-learning (first (filter #(some #{:pattern} (:zettel/tags %)) all))]
        (is (= 0.85
               (get-in pattern-learning [:zettel/source :source/confidence])))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests)
  :leave-this-here)
