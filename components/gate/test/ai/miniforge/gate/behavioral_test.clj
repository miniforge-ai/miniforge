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

(ns ai.miniforge.gate.behavioral-test
  "Tests for the :behavioral gate."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.behavioral :as behavioral]
            [ai.miniforge.gate.registry   :as registry]
            [ai.miniforge.response.interface :as response]))

;;------------------------------------------------------------------------------ Fixtures

(def sample-events
  [{:event/type :tool-call  :tool "Write" :path "src/foo.clj"}
   {:event/type :tool-call  :tool "Read"  :path "src/bar.clj"}
   {:event/type :tool-result :tool "Write" :status :ok}])

(def sample-artifact
  {:event-stream/events sample-events})

;;------------------------------------------------------------------------------ check-behavioral

(deftest check-behavioral-no-packs-test
  (testing "Passes with warning when no policy packs are loaded"
    (let [result (behavioral/check-behavioral sample-artifact
                                              {:policy-packs [] :phase :observe})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (= 1 (count (:warnings result))))
      (is (= :no-policy-packs (-> result :warnings first :type))))))

(deftest check-behavioral-no-packs-key-test
  (testing "Passes with warning when :policy-packs key is absent"
    (let [result (behavioral/check-behavioral sample-artifact {:phase :observe})]
      (is (true? (:passed? result)))
      (is (= :no-policy-packs (-> result :warnings first :type))))))

;; Tests inject a `:check-fn` via the ctx map rather than redefining
;; `clojure.core/requiring-resolve`.  The previous `with-redefs` approach
;; mutated a global var root and races with any other parallel test that
;; transitively calls `requiring-resolve` (e.g. dag-executor.state-test
;; before the soft-dep was removed there).  Local DI keeps the stub scoped
;; to the call site and removes the cross-brick hazard.

(defn- stub-check-fn
  "Build a stub policy-pack check fn that returns the given violations."
  [violations]
  (fn [_packs _artifact _opts] {:violations violations}))

(defn- throwing-check-fn
  "Build a stub policy-pack check fn that throws with the given message."
  [message]
  (fn [_packs _artifact _opts] (throw (ex-info message {}))))

(deftest check-behavioral-clean-result-test
  (testing "Passes with no errors when policy-pack reports no violations"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (stub-check-fn [])})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result))))))

(deftest check-behavioral-blocking-violations-test
  (testing "Fails with errors when :critical violations are returned"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (stub-check-fn
                                  [{:violation/severity    :critical
                                    :violation/rule-id     :no-file-deletion
                                    :violation/message     "Deleted a protected file"
                                    :violation/remediation "Restore the file"}])})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:errors result))))
      (is (= :behavioral-violation (-> result :errors first :type)))
      (is (= :critical (-> result :errors first :severity)))
      (is (empty? (:warnings result))))))

(deftest check-behavioral-high-severity-test
  (testing "Fails when :high violations are returned (approval-required bucket)"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (stub-check-fn
                                  [{:violation/severity :high
                                    :violation/rule-id  :sensitive-path
                                    :violation/message  "Wrote to sensitive path"}])})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:errors result))))
      (is (= :behavioral-violation (-> result :errors first :type))))))

(deftest check-behavioral-warning-violations-test
  (testing "Passes with warnings when only :medium violations are returned"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (stub-check-fn
                                  [{:violation/severity :medium
                                    :violation/message  "Consider using a helper fn"}])})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (= 1 (count (:warnings result))))
      (is (= :behavioral-warning (-> result :warnings first :type))))))

(deftest check-behavioral-audit-violations-test
  (testing "Passes silently (warnings) when only :low/:info violations are returned"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (stub-check-fn
                                  [{:violation/severity :low
                                    :violation/message  "Audit: read an extra file"}
                                   {:violation/severity :info
                                    :violation/message  "Info: large diff"}])})]
      (is (true? (:passed? result)))
      (is (empty? (:errors result)))
      (is (= 2 (count (:warnings result)))))))

(deftest check-behavioral-exception-test
  (testing "Returns passing result with warning when policy-pack throws"
    (let [result (behavioral/check-behavioral
                  sample-artifact
                  {:policy-packs [{:id :test-pack}]
                   :phase        :observe
                   :check-fn     (throwing-check-fn "policy-pack unavailable")})]
      (is (true? (:passed? result)))
      (is (= :behavioral-check-error (-> result :warnings first :type))))))

;;------------------------------------------------------------------------------ repair-behavioral

(deftest repair-behavioral-always-fails-test
  (testing "repair-behavioral always returns {:success? false}"
    (let [result (behavioral/repair-behavioral
                  sample-artifact
                  [{:type :behavioral-violation :message "bad"}]
                  {:phase :observe})]
      (is (response/error? result))
      (is (false? (:success result)))
      (is (= sample-artifact (:artifact result)))
      (is (string? (:message result)))
      (is (seq (:message result))))))

(deftest repair-behavioral-empty-errors-test
  (testing "repair-behavioral with empty errors still returns failure"
    (let [result (behavioral/repair-behavioral {} [] {})]
      (is (response/error? result))
      (is (false? (:success result))))))

;;------------------------------------------------------------------------------ Registry

(deftest behavioral-gate-registered-test
  (testing ":behavioral gate is registered in the registry"
    (is (contains? (registry/list-gates) :behavioral))))

(deftest behavioral-gate-has-check-and-repair-test
  (testing ":behavioral gate exposes :check and :repair functions"
    (let [gate (registry/get-gate :behavioral)]
      (is (= :behavioral (:name gate)))
      (is (fn? (:check gate)))
      (is (fn? (:repair gate))))))
