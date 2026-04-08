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

(ns ai.miniforge.policy-pack.overlay-test
  "Unit tests for overlay pack resolution — extends, overrides, and merging.

   Covers:
   - Base pack inheritance (single and multiple extends)
   - Rule ID collision detection
   - Override application (severity and enabled? only)
   - Taxonomy ref inheritance and conflict detection
   - :rule/enabled? filtering in core/filter-applicable-rules"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.loader :as loader]
   [ai.miniforge.policy-pack.core :as core]))

;; ============================================================================
;; Test fixtures
;; ============================================================================

(def now (java.time.Instant/now))

(defn make-rule [id severity]
  {:rule/id          id
   :rule/title       (name id)
   :rule/description (str "Rule " (name id))
   :rule/severity    severity
   :rule/category    "200"
   :rule/applies-to  {:phases #{:implement :review}}
   :rule/detection   {:type :content-scan :pattern "test"}
   :rule/enforcement {:action :warn :message "warning"}})

(defn make-pack [id rules & {:keys [extends overrides taxonomy-ref]}]
  (cond-> {:pack/id          id
           :pack/name        (str "Pack " id)
           :pack/version     "1.0.0"
           :pack/description "Test pack"
           :pack/author      "test"
           :pack/categories  []
           :pack/rules       (vec rules)
           :pack/created-at  now
           :pack/updated-at  now}
    extends      (assoc :pack/extends extends)
    overrides    (assoc :pack/overrides overrides)
    taxonomy-ref (assoc :pack/taxonomy-ref taxonomy-ref)))

(def base-rule-a (make-rule :test/rule-a :minor))
(def base-rule-b (make-rule :test/rule-b :major))
(def base-pack (make-pack "base" [base-rule-a base-rule-b]
                           :taxonomy-ref {:taxonomy/id :miniforge/dewey
                                          :taxonomy/min-version "1.0.0"}))

(def extra-rule-c (make-rule :test/rule-c :info))
(def extra-pack (make-pack "extra" [extra-rule-c]))

;; ============================================================================
;; Inheritance tests
;; ============================================================================

(deftest overlay-inherits-base-rules-test
  (testing "overlay inherits all rules from base pack"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "base"}])
          result  (loader/resolve-overlay overlay {"base" base-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 2 (count rules)))
        (is (= #{:test/rule-a :test/rule-b}
               (set (map :rule/id rules))))))))

(deftest overlay-inherits-from-multiple-bases-test
  (testing "overlay inherits rules from multiple base packs in order"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "base"} {:pack-id "extra"}])
          result  (loader/resolve-overlay overlay {"base" base-pack "extra" extra-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 3 (count rules)))
        (is (= #{:test/rule-a :test/rule-b :test/rule-c}
               (set (map :rule/id rules))))))))

(deftest overlay-appends-own-rules-test
  (testing "overlay's own rules are appended after inherited rules"
    (let [own-rule (make-rule :test/rule-d :critical)
          overlay  (make-pack "overlay" [own-rule]
                              :extends [{:pack-id "base"}])
          result   (loader/resolve-overlay overlay {"base" base-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 3 (count rules)))
        (is (= :test/rule-d (:rule/id (last rules))))))))

;; ============================================================================
;; Collision detection tests
;; ============================================================================

(deftest overlay-rule-id-collision-fails-test
  (testing "overlay fails when own rule ID collides with inherited rule"
    (let [colliding-rule (make-rule :test/rule-a :critical)
          overlay        (make-pack "overlay" [colliding-rule]
                                    :extends [{:pack-id "base"}])
          result         (loader/resolve-overlay overlay {"base" base-pack})]
      (is (false? (:success? result)))
      (is (some #(re-find #"collision" %) (:errors result))))))

;; ============================================================================
;; Override tests
;; ============================================================================

(deftest overlay-overrides-severity-test
  (testing "override changes rule severity"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "base"}]
                             :overrides [{:rule/id :test/rule-a :rule/severity :critical}])
          result  (loader/resolve-overlay overlay {"base" base-pack})]
      (is (true? (:success? result)))
      (let [rule-a (first (filter #(= :test/rule-a (:rule/id %))
                                  (:pack/rules (:pack result))))]
        (is (= :critical (:rule/severity rule-a)))))))

(deftest overlay-overrides-enabled-test
  (testing "override disables a rule"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "base"}]
                             :overrides [{:rule/id :test/rule-b :rule/enabled? false}])
          result  (loader/resolve-overlay overlay {"base" base-pack})]
      (is (true? (:success? result)))
      (let [rule-b (first (filter #(= :test/rule-b (:rule/id %))
                                  (:pack/rules (:pack result))))]
        (is (false? (:rule/enabled? rule-b)))))))

;; ============================================================================
;; Taxonomy ref tests
;; ============================================================================

(deftest overlay-inherits-taxonomy-ref-test
  (testing "overlay inherits taxonomy-ref from base pack"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "base"}])
          result  (loader/resolve-overlay overlay {"base" base-pack})]
      (is (true? (:success? result)))
      (is (= :miniforge/dewey
             (get-in (:pack result) [:pack/taxonomy-ref :taxonomy/id]))))))

(deftest overlay-conflicting-taxonomy-ref-fails-test
  (testing "overlay fails when taxonomy refs conflict"
    (let [other-pack (make-pack "other" []
                                :taxonomy-ref {:taxonomy/id :acme/taxonomy
                                               :taxonomy/min-version "1.0.0"})
          overlay    (make-pack "overlay" []
                                :extends [{:pack-id "base"} {:pack-id "other"}])
          result     (loader/resolve-overlay overlay {"base" base-pack "other" other-pack})]
      (is (false? (:success? result)))
      (is (some #(re-find #"[Cc]onflicting" %) (:errors result))))))

;; ============================================================================
;; Missing base pack tests
;; ============================================================================

(deftest overlay-missing-base-fails-test
  (testing "overlay fails when base pack is not found"
    (let [overlay (make-pack "overlay" []
                             :extends [{:pack-id "nonexistent"}])
          result  (loader/resolve-overlay overlay {})]
      (is (false? (:success? result)))
      (is (some #(re-find #"not found" %) (:errors result))))))

;; ============================================================================
;; :rule/enabled? filtering in core
;; ============================================================================

(deftest filter-applicable-rules-respects-enabled-test
  (testing "disabled rules are excluded by filter-applicable-rules"
    (let [enabled-rule  (make-rule :test/enabled :minor)
          disabled-rule (assoc (make-rule :test/disabled :minor) :rule/enabled? false)
          rules         [enabled-rule disabled-rule]
          context       {:artifact {:artifact/path "foo.clj"}
                         :phase    :implement}
          result        (core/filter-applicable-rules rules context)]
      (is (= 1 (count result)))
      (is (= :test/enabled (:rule/id (first result))))))

  (testing "rules without :rule/enabled? default to enabled"
    (let [rules   [(make-rule :test/implicit-enabled :minor)]
          context {:artifact {:artifact/path "foo.clj"} :phase :implement}
          result  (core/filter-applicable-rules rules context)]
      (is (= 1 (count result))))))
