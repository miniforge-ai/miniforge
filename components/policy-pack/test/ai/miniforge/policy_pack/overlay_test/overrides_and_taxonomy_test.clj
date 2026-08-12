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
(ns ai.miniforge.policy-pack.overlay-test.overrides-and-taxonomy-test
  "Tests for overlay pack overrides (severity, enabled?) and taxonomy-ref
   inheritance/conflict detection. Split out of `overlay_test.clj` (rule
   210)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.loader :as loader]
   [ai.miniforge.policy-pack.overlay-test.fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} overlay-overrides-severity-test
  (testing "override changes rule severity"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "base"}]
                             :overrides [{:rule/id :test/rule-a :rule/severity :critical}])
          result  (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (true? (:success? result)))
      (let [rule-a (first (filter #(= :test/rule-a (:rule/id %))
                                  (:pack/rules (:pack result))))]
        (is (= :critical (:rule/severity rule-a)))))))

(deftest ^{:stratum 0} overlay-overrides-enabled-test
  (testing "override disables a rule"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "base"}]
                             :overrides [{:rule/id :test/rule-b :rule/enabled? false}])
          result  (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (true? (:success? result)))
      (let [rule-b (first (filter #(= :test/rule-b (:rule/id %))
                                  (:pack/rules (:pack result))))]
        (is (false? (:rule/enabled? rule-b)))))))

(deftest ^{:stratum 0} overlay-inherits-taxonomy-ref-test
  (testing "overlay inherits taxonomy-ref from base pack"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "base"}])
          result  (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (true? (:success? result)))
      (is (= :miniforge/dewey
             (get-in (:pack result) [:pack/taxonomy-ref :taxonomy/id]))))))

(deftest ^{:stratum 0} overlay-conflicting-taxonomy-ref-fails-test
  (testing "overlay fails when taxonomy refs conflict"
    (let [other-pack (fixtures/make-pack "other" []
                                :taxonomy-ref {:taxonomy/id :acme/taxonomy
                                               :taxonomy/min-version "1.0.0"})
          overlay    (fixtures/make-pack "overlay" []
                                :extends [{:pack-id "base"} {:pack-id "other"}])
          result     (loader/resolve-overlay overlay {"base" fixtures/base-pack "other" other-pack})]
      (is (false? (:success? result)))
      (is (some #(re-find #"[Cc]onflicting" %) (:errors result))))))
