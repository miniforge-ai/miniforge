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

(ns ai.miniforge.policy-pack.compiler-test
  "Tests for the detection-binding compiler/validator."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.compiler :as sut]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- a-resolvable-custom-fn [_artifact _context] nil)

(defn- noop-check [_artifact _context] nil)

(def ^:private pack-rel-path
  "components/phase/resources/packs/miniforge-standards.pack.edn")

(defn- find-shipped-pack
  "Locate the shipped standards pack regardless of test cwd.

   Polylith runs tests from the repo root (where the rel path resolves), but
   running the component in isolation puts cwd at the component dir. Walk up
   from cwd until the repo-root-relative path resolves."
  []
  (loop [dir (.getAbsoluteFile (io/file "."))]
    (when dir
      (let [candidate (io/file dir pack-rel-path)]
        (if (.exists candidate)
          candidate
          (recur (.getParentFile dir)))))))

;------------------------------------------------------------------------------ rule-enabled?

(deftest rule-enabled?-test
  (testing "a rule with no :rule/enabled? is enabled (the shipped pack omits it)"
    (is (sut/rule-enabled? {:rule/id :x})))
  (testing ":rule/enabled? true is enabled"
    (is (sut/rule-enabled? {:rule/id :x :rule/enabled? true})))
  (testing ":rule/enabled? false is disabled"
    (is (not (sut/rule-enabled? {:rule/id :x :rule/enabled? false})))))

;------------------------------------------------------------------------------ resolve-detector

(deftest resolve-detector-by-type-test
  (testing "by-type detectors bind to their declared type"
    (doseq [t [:content-scan :diff-analysis :plan-output :state-comparison :ast-analysis]]
      (is (= t (sut/resolve-detector {:rule/detection {:type t}}))))))

(deftest resolve-detector-custom-vs-semantic-test
  (testing "a :custom rule with a resolvable :custom-fn binds to :custom"
    (is (= :custom
           (sut/resolve-detector
            {:rule/detection
             {:type :custom
              :custom-fn 'ai.miniforge.policy-pack.compiler-test/a-resolvable-custom-fn}}))))
  (testing "a :custom rule with no :custom-fn binds to :semantic (always available)"
    (is (= :semantic (sut/resolve-detector {:rule/detection {:type :custom}}))))
  (testing "a :custom rule with an unresolvable :custom-fn binds to :semantic"
    (is (= :semantic
           (sut/resolve-detector
            {:rule/detection {:type :custom :custom-fn 'no.such.ns/missing}})))))

(deftest resolve-detector-capability-test
  (testing "a :capability rule binds to :capability only when registered"
    (capability/register-capability! ::registered-cap {:meta {} :check noop-check})
    (is (= :capability
           (sut/resolve-detector
            {:rule/detection {:type :capability :capability ::registered-cap}}))))
  (testing "an unregistered capability is :none (fail-loud)"
    (is (= :none
           (sut/resolve-detector
            {:rule/detection {:type :capability :capability ::never-registered}}))))
  (testing "a :capability rule with no :capability keyword is :none"
    (is (= :none (sut/resolve-detector {:rule/detection {:type :capability}})))))

(deftest resolve-detector-unbindable-test
  (testing "an unknown detection type is :none"
    (is (= :none (sut/resolve-detector {:rule/detection {:type :nonsense}}))))
  (testing "a missing detection is :none"
    (is (= :none (sut/resolve-detector {:rule/id :x})))))

;------------------------------------------------------------------------------ compile-pack — synthetic

(deftest compile-pack-unbindable-anomaly-test
  (testing "an enabled rule with an unbindable detector fails loud, naming the rule-id"
    (let [pack   {:pack/rules [{:rule/id :ok :rule/detection {:type :content-scan}}
                               {:rule/id :doomed :rule/detection {:type :nonsense}}]}
          result (sut/compile-pack pack)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= [:doomed] (get-in result [:anomaly/data :unbindable-rule-ids]))))))

(deftest compile-pack-skips-disabled-rules-test
  (testing "a DISABLED unbindable rule does not fail compilation"
    (let [pack   {:pack/rules [{:rule/id :ok :rule/detection {:type :content-scan}}
                               {:rule/id :off
                                :rule/enabled? false
                                :rule/detection {:type :nonsense}}]}
          result (sut/compile-pack pack)]
      (is (:ok result))
      (is (= 1 (:rule-count result))))))

;------------------------------------------------------------------------------ compile-pack — REAL shipped pack (headline acceptance criterion)

(deftest compile-real-pack-zero-unbindable-test
  (testing "every enabled rule in the shipped standards pack binds to a detector"
    (let [f (find-shipped-pack)]
      (is (some? f) "shipped standards pack must be locatable from the repo root")
      (let [pack   (edn/read-string (slurp f))
            result (sut/compile-pack pack)]
        ;; Headline: zero enabled rules resolve to :none.
        (is (not (anomaly/anomaly? result))
            (str "expected zero unbindable rules, got: " (pr-str result)))
        (is (:ok result))
        (is (pos? (:rule-count result)))
        (is (not (contains? (:detector-counts result) :none)))
        ;; Print the breakdown so the report can cite it.
        (println "REAL PACK compile-pack =>" (pr-str result))))))
