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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.degradation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.reliability.interface :as rel]
   [ai.miniforge.event-stream.interface.stream :as stream]))

(defn- make-stream []
  (stream/create-event-stream {:sinks []}))

;; ---------------------------------------------------------------------------- Basic lifecycle

(deftest initial-mode-is-nominal-test
  (let [mgr (rel/create-degradation-manager (make-stream))]
    (is (= :nominal (rel/degradation-mode mgr)))))

;; ---------------------------------------------------------------------------- Budget-driven transitions

(deftest nominal-to-degraded-on-critical-low-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        budgets {[:SLI-1 :critical :7d]
                 {:error-budget/tier :critical
                  :error-budget/remaining 0.20
                  :error-budget/burn-rate 1.3}}]
    (rel/evaluate-degradation! mgr budgets)
    (is (= :degraded (rel/degradation-mode mgr)))))

(deftest nominal-to-safe-mode-on-exhausted-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        budgets {[:SLI-1 :critical :7d]
                 {:error-budget/tier :critical
                  :error-budget/remaining 0.0
                  :error-budget/burn-rate 2.0}}]
    (rel/evaluate-degradation! mgr budgets)
    (is (= :safe-mode (rel/degradation-mode mgr)))))

(deftest degraded-to-nominal-on-recovery-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        ;; First go to degraded
        low-budgets {[:SLI-1 :critical :7d]
                     {:error-budget/tier :critical
                      :error-budget/remaining 0.20
                      :error-budget/burn-rate 1.3}}
        ;; Then recover
        good-budgets {[:SLI-1 :critical :7d]
                      {:error-budget/tier :critical
                       :error-budget/remaining 0.50
                       :error-budget/burn-rate 0.5}}]
    (rel/evaluate-degradation! mgr low-budgets)
    (is (= :degraded (rel/degradation-mode mgr)))
    (rel/evaluate-degradation! mgr good-budgets)
    (is (= :nominal (rel/degradation-mode mgr)))))

(deftest degraded-to-safe-mode-on-exhaustion-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        low-budgets {[:SLI-1 :critical :7d]
                     {:error-budget/tier :critical
                      :error-budget/remaining 0.20
                      :error-budget/burn-rate 1.3}}
        exhausted {[:SLI-1 :critical :7d]
                   {:error-budget/tier :critical
                    :error-budget/remaining 0.0
                    :error-budget/burn-rate 2.0}}]
    (rel/evaluate-degradation! mgr low-budgets)
    (is (= :degraded (rel/degradation-mode mgr)))
    (rel/evaluate-degradation! mgr exhausted)
    (is (= :safe-mode (rel/degradation-mode mgr)))))

;; ---------------------------------------------------------------------------- Emergency stop

(deftest emergency-stop-from-nominal-test
  (let [mgr (rel/create-degradation-manager (make-stream))]
    (rel/enter-safe-mode! mgr :emergency-stop "Production incident")
    (is (= :safe-mode (rel/degradation-mode mgr)))))

(deftest emergency-stop-from-degraded-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        low-budgets {[:SLI-1 :critical :7d]
                     {:error-budget/tier :critical
                      :error-budget/remaining 0.20
                      :error-budget/burn-rate 1.3}}]
    (rel/evaluate-degradation! mgr low-budgets)
    (rel/enter-safe-mode! mgr :emergency-stop "Manual halt")
    (is (= :safe-mode (rel/degradation-mode mgr)))))

;; ---------------------------------------------------------------------------- Safe-mode exit

(deftest safe-mode-exit-requires-justification-test
  (let [mgr (rel/create-degradation-manager (make-stream))]
    (rel/enter-safe-mode! mgr :emergency-stop "Test")
    (is (= :safe-mode (rel/degradation-mode mgr)))
    (rel/exit-safe-mode! mgr "Incident resolved" "chris@miniforge.ai")
    (is (= :nominal (rel/degradation-mode mgr)))))

(deftest exit-from-non-safe-mode-is-noop-test
  (let [mgr (rel/create-degradation-manager (make-stream))]
    (is (= :nominal (rel/degradation-mode mgr)))
    (rel/exit-safe-mode! mgr "No reason" "someone")
    (is (= :nominal (rel/degradation-mode mgr)))))

;; ---------------------------------------------------------------------------- Event emission

(deftest transitions-emit-events-test
  (let [stream (make-stream)
        mgr (rel/create-degradation-manager stream)
        budgets {[:SLI-1 :critical :7d]
                 {:error-budget/tier :critical
                  :error-budget/remaining 0.20
                  :error-budget/burn-rate 1.3}}]
    (rel/evaluate-degradation! mgr budgets)
    (let [events (:events @stream)
          mode-events (filter #(= :reliability/degradation-mode-changed (:event/type %)) events)]
      (is (= 1 (count mode-events)))
      (is (= :nominal (:degradation/from (first mode-events))))
      (is (= :degraded (:degradation/to (first mode-events)))))))

;; ---------------------------------------------------------------------------- Standard tier budgets don't trigger degradation

(deftest standard-tier-does-not-trigger-degradation-test
  (let [mgr (rel/create-degradation-manager (make-stream))
        budgets {[:SLI-1 :standard :7d]
                 {:error-budget/tier :standard
                  :error-budget/remaining 0.0
                  :error-budget/burn-rate 3.0}}]
    (rel/evaluate-degradation! mgr budgets)
    (is (= :nominal (rel/degradation-mode mgr)))))
