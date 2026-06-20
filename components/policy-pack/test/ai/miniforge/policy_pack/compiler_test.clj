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
   [ai.miniforge.policy-pack.detection :as detection]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- a-resolvable-custom-fn [_artifact _context] nil)

(def ^:private resolvable-custom-fn-sym
  'ai.miniforge.policy-pack.compiler-test/a-resolvable-custom-fn)

(use-fixtures
  :once
  (fn [run-tests]
    (detection/register-custom-fn! resolvable-custom-fn-sym a-resolvable-custom-fn)
    (try
      (run-tests)
      (finally
        (detection/unregister-custom-fn! resolvable-custom-fn-sym)))))

(defn- noop-check [_artifact _context] nil)

(defn- compile-lint-check
  [artifact _context]
  (when (= "bad.clj" (:artifact/path artifact))
    {:type :capability
     :capability ::compile-lint
     :message "lint failed"}))

(defn- always-violating-capability-check
  [_artifact _context]
  {:type :capability
   :capability ::empty-files
   :message "capability should not run without artifacts"})

(defn- semantic-violation-analyze
  [_llm-client _complete-fn _repo-path rule]
  {:rule/id (:rule/id rule)
   :status :failed
   :violations [{:path "src/core.clj"
                 :message "semantic issue"}]})

(defn- noop-complete [_request] nil)

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
              :custom-fn resolvable-custom-fn-sym}}))))
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

(deftest compile-pack-malformed-rules-anomaly-test
  (testing "a non-sequential :pack/rules fails loud rather than vacuously passing"
    (doseq [bad [{} {:pack/rules nil} {:pack/rules {:rule/id :x}} {:pack/rules 42}]]
      (let [result (sut/compile-pack bad)]
        (is (anomaly/anomaly? result) (str "expected anomaly for " (pr-str bad)))
        (is (= :invalid-input (:anomaly/type result)))))))

;------------------------------------------------------------------------------ executable check-fn compiler

(deftest compile-rule-check-content-violation-test
  (testing "a content-scan rule compiles to an N4 check-fn that reports violations"
    (let [rule     {:rule/id :no-duplicate-ns
                    :rule/severity :major
                    :rule/detection {:type :content-scan
                                     :pattern "duplicate namespace"}
                    :rule/enforcement {:message "Duplicate namespace"}}
          compiled (sut/compile-rule-check rule)
          check-fn (:check-fn compiled)
          result   (check-fn {:code/files [{:path "src/core.clj"
                                            :content "duplicate namespace"}]}
                             {})]
      (is (= :content-scan (:detector compiled)))
      (is (false? (:passed? result)))
      (is (= :no-duplicate-ns (get-in result [:metadata :rule/id])))
      (is (= :major (get-in result [:violations 0 :severity])))
      (is (= :no-duplicate-ns (get-in result [:violations 0 :rule-id]))))))

(deftest compile-rule-check-content-clean-test
  (testing "a clean artifact returns :passed? true and no violations"
    (let [rule     {:rule/id :no-duplicate-ns
                    :rule/severity :major
                    :rule/detection {:type :content-scan
                                     :pattern "duplicate namespace"}}
          compiled (sut/compile-rule-check rule)
          result   ((:check-fn compiled)
                    {:code/files [{:path "src/core.clj"
                                   :content "clean namespace"}]}
                    {})]
      (is (true? (:passed? result)))
      (is (= [] (:violations result))))))

(deftest compile-rule-check-capability-test
  (testing "a capability rule binds to the registered capability check"
    (capability/register-capability!
     ::compile-lint
     {:meta {:tool :stub}
      :check compile-lint-check})
    (let [rule     {:rule/id :lint
                    :rule/severity :critical
                    :rule/detection {:type :capability
                                     :capability ::compile-lint}}
          compiled (sut/compile-rule-check rule)
          result   ((:check-fn compiled)
                    {:code/files [{:path "bad.clj" :content "(def x 1)"}]}
                    {})]
      (is (= :capability (:detector compiled)))
      (is (false? (:passed? result)))
      (is (= :critical (get-in result [:violations 0 :severity]))))))

(deftest compile-rule-check-empty-files-capability-test
  (testing "explicit empty file collections do not run capability checks on the envelope"
    (capability/register-capability!
     ::empty-files
     {:meta {:tool :stub}
      :check always-violating-capability-check})
    (let [rule     {:rule/id :empty-files
                    :rule/severity :critical
                    :rule/detection {:type :capability
                                     :capability ::empty-files}}
          compiled (sut/compile-rule-check rule)]
      (doseq [artifacts [{:code/files []}
                         {:files []}]]
        (let [result ((:check-fn compiled) artifacts {})]
          (is (true? (:passed? result)))
          (is (= [] (:violations result))))))))

(deftest compile-rule-check-semantic-test
  (testing "a custom heuristic rule with no custom fn compiles to semantic check"
    (let [rule       {:rule/id :heuristic-review
                      :rule/severity :minor
                      :rule/detection {:type :custom}
                      :rule/enforcement {:message "Heuristic review failed"}}
          context    {:semantic-analyze-fn semantic-violation-analyze
                      :llm-client ::llm
                      :complete-fn noop-complete}
          compiled   (sut/compile-rule-check rule)
          result     ((:check-fn compiled)
                      {:code/files [{:path "src/core.clj" :content "x"}]}
                      context)]
      (is (= :semantic (:detector compiled)))
      (is (= :heuristic (get-in result [:metadata :class])))
      (is (false? (:passed? result)))
      (is (= :semantic (get-in result [:violations 0 :type]))))))

(deftest compile-rule-check-semantic-missing-wiring-fails-test
  (testing "compiled semantic checks fail loud instead of silently passing"
    (let [rule     {:rule/id :heuristic-review
                    :rule/severity :minor
                    :rule/detection {:type :custom}}
          compiled (sut/compile-rule-check rule)
          result   ((:check-fn compiled)
                    {:code/files [{:path "src/core.clj" :content "x"}]}
                    {})]
      (is (false? (:passed? result)))
      (is (= :semantic-error (get-in result [:violations 0 :type])))
      (is (= :heuristic-review (get-in result [:violations 0 :rule-id]))))))

(deftest compile-rule-check-unbindable-anomaly-test
  (testing "an unbindable enabled rule fails before check execution"
    (let [result (sut/compile-rule-check
                  {:rule/id :missing
                   :rule/detection {:type :unknown}})]
      (is (anomaly/anomaly? result))
      (is (= :missing (get-in result [:anomaly/data :rule/id]))))))

(deftest compile-pack-checks-returns-executable-entries-test
  (testing "compile-pack-checks returns one executable entry per enabled rule"
    (let [pack   {:pack/rules [{:rule/id :a
                                :rule/detection {:type :content-scan :pattern "A"}}
                               {:rule/id :b
                                :rule/enabled? false
                                :rule/detection {:type :unknown}}]}
          result (sut/compile-pack-checks pack)]
      (is (:ok result))
      (is (= 1 (:rule-count result)))
      (is (= 1 (count (:compiled-rules result))))
      (is (fn? (get-in result [:compiled-rules 0 :check-fn]))))))

(deftest compiled-mechanical-check-pure-test
  (testing "a deterministic compiled check returns equal results for identical inputs"
    (let [rule     {:rule/id :no-token
                    :rule/severity :major
                    :rule/detection {:type :content-scan
                                     :pattern "TOKEN"}}
          compiled (sut/compile-rule-check rule)
          artifacts {:code/files [{:path "src/core.clj"
                                   :content "TOKEN"}]}
          first-result ((:check-fn compiled) artifacts {})
          second-result ((:check-fn compiled) artifacts {})]
      (is (= first-result second-result)))))

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
        (is (not (contains? (:detector-counts result) :none)))))))
