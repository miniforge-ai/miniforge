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

(ns ai.miniforge.knowledge.policy-lookup-test
  (:require
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.knowledge.policy-lookup :as sut]
   [ai.miniforge.knowledge.store :as store]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- make-store
  "Create an empty in-memory store with no logger noise."
  []
  (store/create-store))

(defn- rule-zettel
  "Build a minimal :rule zettel for testing. All keys required by the store."
  [uid title content & {:as overrides}]
  (merge {:zettel/id      (random-uuid)
          :zettel/uid     uid
          :zettel/title   title
          :zettel/content content
          :zettel/type    :rule
          :zettel/tags    []
          :zettel/links   []
          :zettel/author  "test"
          :zettel/created (java.util.Date.)}
         overrides))

(defn- non-rule-zettel
  "Build a :learning zettel (should be excluded from policy lookup)."
  [uid title]
  {:zettel/id      (random-uuid)
   :zettel/uid     uid
   :zettel/title   title
   :zettel/content "A learning, not a rule."
   :zettel/type    :learning
   :zettel/tags    [:clojure]
   :zettel/links   []
   :zettel/author  "test"
   :zettel/created (java.util.Date.)})

(defn- store-with-rules
  "Create a store pre-populated with several rules."
  []
  (doto (make-store)
    (store/put-zettel (rule-zettel "210-clojure-ns"
                                  "Clojure Namespace Conventions"
                                  "Follow Polylith conventions for namespace naming."
                                  :zettel/dewey "210"
                                  :zettel/tags [:clojure :namespace]))
    (store/put-zettel (rule-zettel "001-stratified-design"
                                  "Stratified Design"
                                  "Use a single-direction DAG for code dependencies."
                                  :zettel/dewey "001"
                                  :zettel/tags [:architecture :design]))
    (store/put-zettel (rule-zettel "211-exception-handling"
                                  "Clojure Exception Handling"
                                  "Prefer slingshot try+ over plain try."
                                  :zettel/dewey "211"
                                  :zettel/tags [:clojure :exceptions]))
    (store/put-zettel (non-rule-zettel "learning-42" "A captured learning"))))

(defn- titles
  "Extract rule titles from lookup results for easy assertion."
  [results]
  (mapv :rule/title results))

;------------------------------------------------------------------------------ Layer 1
;; Unit tests — policy-lookup/lookup-policy-rules (pure function)

(deftest test-nil-store-returns-empty-vector
  (testing "nil knowledge-store → safe, returns []"
    (is (= [] (sut/lookup-policy-rules nil {})))))

(deftest test-empty-store-returns-empty-vector
  (testing "empty store with any query → []"
    (is (= [] (sut/lookup-policy-rules (make-store) {})))))

(deftest test-empty-query-returns-all-rules
  (testing "empty query map → all :rule zettels, no learnings"
    (let [results (sut/lookup-policy-rules (store-with-rules) {})]
      (is (= 3 (count results)))
      (is (every? #(contains? % :rule/title) results))
      (is (every? #(contains? % :rule/content) results)))))

(deftest test-result-shape
  (testing "each result has only :rule/title and :rule/content"
    (let [[first-result] (sut/lookup-policy-rules (store-with-rules) {})]
      (is (string? (:rule/title first-result)))
      (is (string? (:rule/content first-result)))
      ;; No zettel internals leaked
      (is (nil? (:zettel/id first-result)))
      (is (nil? (:zettel/type first-result))))))

(deftest test-non-rule-zettels-excluded
  (testing "learnings and other types are not returned"
    (let [results (sut/lookup-policy-rules (store-with-rules) {})
          result-titles (titles results)]
      (is (not (some #{"A captured learning"} result-titles))))))

(deftest test-keyword-query-matches-title
  (testing ":query matches title substring (case-insensitive)"
    (let [results (sut/lookup-policy-rules (store-with-rules) {:query "Namespace"})]
      (is (= 1 (count results)))
      (is (= "Clojure Namespace Conventions" (:rule/title (first results)))))))

(deftest test-keyword-query-matches-content
  (testing ":query matches body content (case-insensitive)"
    (let [results (sut/lookup-policy-rules (store-with-rules) {:query "slingshot"})]
      (is (= 1 (count results)))
      (is (= "Clojure Exception Handling" (:rule/title (first results)))))))

(deftest test-keyword-query-case-insensitive
  (testing ":query match is case-insensitive"
    (let [upper (sut/lookup-policy-rules (store-with-rules) {:query "POLYLITH"})
          lower (sut/lookup-policy-rules (store-with-rules) {:query "polylith"})]
      (is (= (count upper) (count lower)))
      (is (= 1 (count lower))))))

(deftest test-keyword-query-no-match-returns-empty
  (testing ":query with no matches → []"
    (is (= [] (sut/lookup-policy-rules (store-with-rules) {:query "xyzzy-nonexistent"})))))

(deftest test-tags-filter-single-tag
  (testing ":tags with one tag returns matching rules"
    (let [results (sut/lookup-policy-rules (store-with-rules) {:tags [:clojure]})]
      (is (= 2 (count results)))
      (is (= #{"Clojure Namespace Conventions" "Clojure Exception Handling"}
             (set (titles results)))))))

(deftest test-tags-filter-no-match
  (testing ":tags with no matching zettel → []"
    (is (= [] (sut/lookup-policy-rules (store-with-rules) {:tags [:python]})))))

(deftest test-dewey-prefix-exact-match
  (testing ":dewey-prefix exact match returns the matching rule"
    (let [results (sut/lookup-policy-rules (store-with-rules) {:dewey-prefix "001"})]
      (is (= 1 (count results)))
      (is (= "Stratified Design" (:rule/title (first results)))))))

(deftest test-dewey-prefix-matches-children
  (testing ":dewey-prefix \"21\" matches dewey \"210\" and \"211\""
    (let [results (sut/lookup-policy-rules (store-with-rules) {:dewey-prefix "21"})]
      (is (= 2 (count results)))
      (is (= #{"Clojure Namespace Conventions" "Clojure Exception Handling"}
             (set (titles results)))))))

(deftest test-dewey-prefix-no-match
  (testing ":dewey-prefix with no matching rules → []"
    (is (= [] (sut/lookup-policy-rules (store-with-rules) {:dewey-prefix "999"})))))

(deftest test-combined-query-and-tags
  (testing "combined :query + :tags → intersection"
    (let [results (sut/lookup-policy-rules (store-with-rules)
                                           {:query "slingshot" :tags [:clojure]})]
      (is (= 1 (count results)))
      (is (= "Clojure Exception Handling" (:rule/title (first results)))))))

(deftest test-combined-tags-and-dewey
  (testing "combined :tags + :dewey-prefix → intersection"
    (let [results (sut/lookup-policy-rules (store-with-rules)
                                           {:tags [:clojure] :dewey-prefix "210"})]
      (is (= 1 (count results)))
      (is (= "Clojure Namespace Conventions" (:rule/title (first results)))))))

(deftest test-all-criteria-combined
  (testing "all three criteria combined → most specific match"
    (let [results (sut/lookup-policy-rules (store-with-rules)
                                           {:query "slingshot"
                                            :tags [:clojure]
                                            :dewey-prefix "211"})]
      (is (= 1 (count results)))
      (is (= "Clojure Exception Handling" (:rule/title (first results)))))))

(deftest test-all-criteria-combined-no-match
  (testing "conflicting criteria → []"
    (is (= [] (sut/lookup-policy-rules (store-with-rules)
                                       {:query "slingshot"
                                        :tags [:architecture]})))))

;------------------------------------------------------------------------------ Layer 2
;; Interface-level tests — knowledge/lookup-policy-rules (boundary + validation)

(deftest test-interface-nil-store-safe
  (testing "interface: nil store → []"
    (is (= [] (knowledge/lookup-policy-rules nil {})))))

(deftest test-interface-nil-query-treated-as-empty
  (testing "interface: nil query → treated as empty, returns all rules"
    (let [results (knowledge/lookup-policy-rules (store-with-rules) nil)]
      (is (= 3 (count results))))))

(deftest test-interface-invalid-query-returns-empty
  (testing "interface: query failing PolicyQuery schema → []"
    ;; :tags must be a vector of keywords, not a string
    (is (= [] (knowledge/lookup-policy-rules (store-with-rules) {:tags "clojure"})))))

(deftest test-interface-valid-query-delegates
  (testing "interface: valid query delegates to lookup-policy-rules"
    (let [results (knowledge/lookup-policy-rules (store-with-rules) {:tags [:clojure]})]
      (is (= 2 (count results))))))

(deftest test-policy-query-schema-exported
  (testing "PolicyQuery schema is exported from the interface namespace"
    (is (some? knowledge/PolicyQuery))))

(deftest test-policy-result-schema-exported
  (testing "PolicyResult schema is exported from the interface namespace"
    (is (some? knowledge/PolicyResult))))
