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
(ns ai.miniforge.phase-software-factory.gap-wiring-test
  "Signal fragments mirror the real producers' shapes: ReviewIssue maps,
   decision-envelope Reason maps, the namespaced [:phase :phase/...] keys
   the gate runner writes, and the [:phase :error] variants review/
   implement attach."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.codex-gap.interface :as gap]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} review-signals-prefer-maps-and-fall-back-to-strings
  (testing "map-shaped issues win"
    (is (= [{:type :review-blocking
             :payload {:severity :blocking :description "d" :file "f.clj"}}]
           (gap-wiring/review-signals
             {:review/issues [{:severity :blocking :description "d" :file "f.clj"}
                              {:severity :warning :description "w"}]
              :review/blocking-issues ["d"]}))))
  (testing "string fallback when no map-shaped issues exist"
    (is (= [{:type :review-blocking :payload "gate says no"}]
           (gap-wiring/review-signals
             {:review/blocking-issues ["gate says no"]})))))

(deftest ^{:stratum 0} gate-signals-read-the-namespaced-keys-only
  (is (= [{:type :gate-failure
           :payload {:reason/code :reason/gate-check-failed :reason/detail "x"}}]
         (gap-wiring/gate-signals
           {:phase/status :failed
            :phase/gate-errors [{:reason/code :reason/gate-check-failed
                                 :reason/detail "x"}]
            ;; the bare key is leave-clobbered and must be ignored
            :status :completed})))
  (is (nil? (gap-wiring/gate-signals {:phase/status :completed
                                      :phase/gate-errors []}))))

(deftest ^{:stratum 0} terminal-signal-normalizes-onto-anomaly-category
  (testing "hand-rolled review anomaly map"
    (is (= {:type :terminal-anomaly
            :payload {:anomaly/category :anomalies.review/needs-decomposition
                      :anomaly/message "m"}}
           (gap-wiring/terminal-signal
             {:error {:message "m"
                      :anomaly/category :anomalies.review/needs-decomposition
                      :anomaly/message "m"}}))))
  (testing "implement's plain error map (no category)"
    (is (= {:type :terminal-anomaly
            :payload {:anomaly/category nil :anomaly/message "agent failed"}}
           (gap-wiring/terminal-signal {:error {:message "agent failed"}}))))
  (testing "the canonical anomaly/sub-anomaly shape (review stagnation path)"
    (let [err (anomaly/sub-anomaly :exhausted :anomalies.review/stagnation
                                   "no movement" {:review/fingerprint-history []})]
      (is (= {:type :terminal-anomaly
              :payload {:anomaly/category :anomalies.review/stagnation
                        :anomaly/message "no movement"}}
             (gap-wiring/terminal-signal {:error err}))))))

(deftest ^{:stratum 0} recording-is-a-no-op-without-a-configured-codex
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "gap-wiring-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))
        ctx {:execution/checkpoint-root dir
             :execution/id "run-x"
             :phase {:phase/status :failed
                     :phase/gate-errors [{:reason/code :reason/gate-check-failed
                                          :reason/detail "x"}]}}]
    (gap-wiring/record-phase-misses! ctx :implement nil)
    (is (= {:entries [] :skipped 0}
           (gap/read-ledger (str dir "/run-x")))
        "no codex configured -> no gap to measure -> nothing written")))

(deftest ^{:stratum 0} recording-never-throws-on-a-broken-codex-dir
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "gap-wiring-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))
        ctx {:execution/checkpoint-root dir
             :execution/id "run-y"
             :phase {:result {:output {:review/blocking-issues ["boom"]}}}}]
    ;; unreadable codex: consider/load-graph return anomalies; entries
    ;; still classify (landings-unavailable -> review queue) and record
    (gap-wiring/record-phase-misses! ctx :review "/nonexistent/codex")
    (let [{:keys [entries]} (gap/read-ledger (str dir "/run-y"))]
      (is (= 1 (count entries)))
      (is (= :review-queue (:miss/bucket (first entries))))
      (is (= "quality-signal-might-be-lying"
             (:miss/situation (first entries)))))))

(deftest ^{:stratum 0} ledger-write-failure-warns-through-the-context-logger
  ;; The workflow runner normalizes :execution/logger at context creation
  ;; (workflow.context), so this warn path is live in production runs —
  ;; before that, no writer set the key and the warning vanished.
  (let [root (str (java.nio.file.Files/createTempDirectory
                    "gap-wiring-test-"
                    (into-array java.nio.file.attribute.FileAttribute [])))
        ;; occupy the run-dir path with a FILE so the ledger append fails
        _ (spit (str root "/run-z") "occupied")
        [logger entries] (log/collecting-logger)
        ctx {:execution/checkpoint-root root
             :execution/id "run-z"
             :execution/logger logger
             :phase {:result {:output {:review/blocking-issues ["boom"]}}}}]
    (gap-wiring/record-phase-misses! ctx :review "/nonexistent/codex")
    (is (= [:codex-gap/ledger-write-failed]
           (mapv :log/event @entries))
        "the best-effort warning must reach the ctx logger, not vanish")
    (is (= [:warn] (mapv :log/level @entries)))))
