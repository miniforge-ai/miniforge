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
(ns ai.miniforge.policy-pack.overlay-test.resolution-test
  "Tests for overlay pack rule resolution — missing-base lookup failure,
   single/multiple `:pack/extends` inheritance, own-rule append order, and
   rule-ID collision detection. Split out of `overlay_test.clj` (rule 210)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.loader :as loader]
   [ai.miniforge.policy-pack.overlay-test.fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} overlay-missing-base-fails-test
  (testing "overlay fails when base pack is not found"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "nonexistent"}])
          result  (loader/resolve-overlay overlay {})]
      (is (false? (:success? result)))
      (is (some #(re-find #"not found" %) (:errors result))))))

(deftest ^{:stratum 0} overlay-inherits-base-rules-test
  (testing "overlay inherits all rules from base pack"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "base"}])
          result  (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 2 (count rules)))
        (is (= #{:test/rule-a :test/rule-b}
               (set (map :rule/id rules))))))))

(deftest ^{:stratum 0} overlay-inherits-from-multiple-bases-test
  (testing "overlay inherits rules from multiple base packs in order"
    (let [overlay (fixtures/make-pack "overlay" []
                             :extends [{:pack-id "base"} {:pack-id "extra"}])
          result  (loader/resolve-overlay overlay {"base" fixtures/base-pack "extra" fixtures/extra-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 3 (count rules)))
        (is (= #{:test/rule-a :test/rule-b :test/rule-c}
               (set (map :rule/id rules))))))))

(deftest ^{:stratum 0} overlay-appends-own-rules-test
  (testing "overlay's own rules are appended after inherited rules"
    (let [own-rule (fixtures/make-rule :test/rule-d :critical)
          overlay  (fixtures/make-pack "overlay" [own-rule]
                              :extends [{:pack-id "base"}])
          result   (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (true? (:success? result)))
      (let [rules (:pack/rules (:pack result))]
        (is (= 3 (count rules)))
        (is (= :test/rule-d (:rule/id (last rules))))))))

(deftest ^{:stratum 0} overlay-rule-id-collision-fails-test
  (testing "overlay fails when own rule ID collides with inherited rule"
    (let [colliding-rule (fixtures/make-rule :test/rule-a :critical)
          overlay        (fixtures/make-pack "overlay" [colliding-rule]
                                    :extends [{:pack-id "base"}])
          result         (loader/resolve-overlay overlay {"base" fixtures/base-pack})]
      (is (false? (:success? result)))
      (is (some #(re-find #"collision" %) (:errors result))))))
