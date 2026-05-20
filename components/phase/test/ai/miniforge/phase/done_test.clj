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

(ns ai.miniforge.phase.done-test
  "Regression tests for the core `:done` terminal phase.

   Originally lived in phase-software-factory/release.clj, which meant
   any runtime that did not depend on that component (e.g. the career
   bb runtime running `:career-growth-framework-ingest`) could not
   resolve `{:phase :done}` and would crash with
   `Unknown phase type: :done`. The registration now lives in
   `ai.miniforge.phase.done` so it travels with the phase registry."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.interface :as phase]))

(deftest done-resolves-via-registry-test
  (testing "get-phase-interceptor returns an interceptor map for :done"
    (let [interceptor (phase/get-phase-interceptor {:phase :done})]
      (is (map? interceptor))
      (is (keyword? (:name interceptor)))
      (is (fn? (:enter interceptor)))
      (is (fn? (:leave interceptor)))
      (is (fn? (:error interceptor))))))

(deftest done-enter-marks-context-completed-test
  (testing ":done :enter marks both phase and execution status :completed"
    (let [interceptor (phase/get-phase-interceptor {:phase :done})
          ctx-out ((:enter interceptor) {})]
      (is (= :done (get-in ctx-out [:phase :name])))
      (is (= :completed (get-in ctx-out [:phase :status])))
      (is (= :completed (get-in ctx-out [:execution :status]))))))

(deftest done-leave-and-error-are-noops-test
  (testing ":done :leave and :error pass the context through unchanged"
    (let [interceptor (phase/get-phase-interceptor {:phase :done})
          ctx {:some :value}]
      (is (= ctx ((:leave interceptor) ctx)))
      (is (= ctx ((:error interceptor) ctx (ex-info "boom" {})))))))

(deftest done-defaults-registered-test
  (testing "phase-defaults returns the registered :done defaults"
    (let [defaults (phase/phase-defaults :done)]
      (is (some? defaults))
      (is (nil? (:agent defaults)))
      (is (= [] (:gates defaults)))
      (is (= {:tokens 0 :iterations 1 :time-seconds 1}
             (:budget defaults))))))

(deftest done-listed-in-phases-test
  (testing "list-phases includes :done"
    (is (contains? (phase/list-phases) :done))))
