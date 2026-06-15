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

(ns ai.miniforge.event-stream.reliability-schema-test
  "Tests for RN-03: 4 reliability metric schemas + 2 repo-index schemas
   and the constructor functions that produce conforming event maps.

   Test strategy:
   1. Schema positive: a hand-crafted valid map passes m/validate.
   2. Schema negative: a map with a missing required field fails.
   3. Constructor output: :event/type, :event/id (uuid), :event/timestamp
      (inst), and all domain fields correct from arguments.
   4. Round-trip: constructor output validates against its Malli schema."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.event-stream.schema :as schema]
   [ai.miniforge.event-stream.interface :as es]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-stream []
  (es/create-event-stream {:sinks []}))

(def ^:private wf-id (random-uuid))

;; ---------------------------------------------------------------------------
;; Layer 5.5 — Reliability metric schemas

(deftest sli-computed-schema-test
  (testing "SliComputed positive validation"
    (let [event {:event/type            :reliability/sli-computed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :workflow/id           nil
                 :sli/name              :availability
                 :sli/value             0.999
                 :sli/window            :rolling-1h
                 :message               "SLI computed"}]
      (is (m/validate schema/SliComputed event)
          "valid SliComputed should pass")))

  (testing "SliComputed negative — missing :sli/name"
    (let [event {:event/type            :reliability/sli-computed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :sli/value             0.999
                 :sli/window            :rolling-1h
                 :message               "SLI computed"}]
      (is (not (m/validate schema/SliComputed event))
          "missing :sli/name should fail")))

  (testing "SliComputed negative — missing :sli/window"
    (let [event {:event/type            :reliability/sli-computed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :sli/name              :availability
                 :sli/value             0.999
                 :message               "SLI computed"}]
      (is (not (m/validate schema/SliComputed event))
          "missing :sli/window should fail"))))

(deftest slo-breach-schema-test
  (testing "SloBreach positive validation"
    (let [event {:event/type            :reliability/slo-breach
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :slo/sli-name          :availability
                 :slo/target            0.999
                 :slo/actual            0.980
                 :slo/tier              :standard
                 :slo/window            :rolling-1h
                 :message               "SLO breach"}]
      (is (m/validate schema/SloBreach event)
          "valid SloBreach should pass")))

  (testing "SloBreach negative — missing :slo/actual"
    (let [event {:event/type            :reliability/slo-breach
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :slo/sli-name          :availability
                 :slo/target            0.999
                 :slo/tier              :standard
                 :slo/window            :rolling-1h
                 :message               "SLO breach"}]
      (is (not (m/validate schema/SloBreach event))
          "missing :slo/actual should fail"))))

(deftest error-budget-update-schema-test
  (testing "ErrorBudgetUpdate positive validation"
    (let [event {:event/type            :reliability/error-budget-update
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :budget/tier           :standard
                 :budget/sli            :availability
                 :budget/remaining      0.72
                 :budget/burn-rate      1.4
                 :budget/window         :rolling-30d
                 :message               "Error budget updated"}]
      (is (m/validate schema/ErrorBudgetUpdate event)
          "valid ErrorBudgetUpdate should pass")))

  (testing "ErrorBudgetUpdate negative — missing :budget/burn-rate"
    (let [event {:event/type            :reliability/error-budget-update
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :budget/tier           :standard
                 :budget/sli            :availability
                 :budget/remaining      0.72
                 :budget/window         :rolling-30d
                 :message               "Error budget updated"}]
      (is (not (m/validate schema/ErrorBudgetUpdate event))
          "missing :budget/burn-rate should fail"))))

(deftest degradation-mode-changed-schema-test
  (testing "DegradationModeChanged positive validation"
    (let [event {:event/type            :reliability/degradation-mode-changed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :degradation/from      :normal
                 :degradation/to        :degraded
                 :degradation/trigger   "error-rate exceeded threshold"
                 :message               "Degradation mode changed"}]
      (is (m/validate schema/DegradationModeChanged event)
          "valid DegradationModeChanged should pass")))

  (testing "DegradationModeChanged negative — missing :degradation/trigger"
    (let [event {:event/type            :reliability/degradation-mode-changed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :degradation/from      :normal
                 :degradation/to        :degraded
                 :message               "Degradation mode changed"}]
      (is (not (m/validate schema/DegradationModeChanged event))
          "missing :degradation/trigger should fail"))))

;; ---------------------------------------------------------------------------
;; Layer 6 — Repo-index schemas

(deftest repo-index-quality-measured-schema-test
  (testing "RepoIndexQualityMeasured positive validation"
    (let [event {:event/type            :repo-index/quality-measured
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :index/id              "main-code-index"
                 :index/quality-score   0.87
                 :index/coverage        0.93
                 :index/staleness-ms    300000
                 :message               "Index quality measured"}]
      (is (m/validate schema/RepoIndexQualityMeasured event)
          "valid RepoIndexQualityMeasured should pass")))

  (testing "RepoIndexQualityMeasured negative — missing :index/staleness-ms"
    (let [event {:event/type            :repo-index/quality-measured
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :index/id              "main-code-index"
                 :index/quality-score   0.87
                 :index/coverage        0.93
                 :message               "Index quality measured"}]
      (is (not (m/validate schema/RepoIndexQualityMeasured event))
          "missing :index/staleness-ms should fail")))

  (testing "RepoIndexQualityMeasured negative — missing :index/id"
    (let [event {:event/type            :repo-index/quality-measured
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :index/quality-score   0.87
                 :index/coverage        0.93
                 :index/staleness-ms    300000
                 :message               "Index quality measured"}]
      (is (not (m/validate schema/RepoIndexQualityMeasured event))
          "missing :index/id should fail"))))

(deftest repo-index-coverage-changed-schema-test
  (testing "RepoIndexCoverageChanged positive validation"
    (let [event {:event/type               :repo-index/coverage-changed
                 :event/id                 (random-uuid)
                 :event/timestamp          (java.util.Date.)
                 :event/version            "1.0.0"
                 :event/sequence-number    0
                 :index/id                 "main-code-index"
                 :index/previous-coverage  0.80
                 :index/coverage           0.93
                 :message                  "Index coverage changed"}]
      (is (m/validate schema/RepoIndexCoverageChanged event)
          "valid RepoIndexCoverageChanged should pass")))

  (testing "RepoIndexCoverageChanged negative — missing :index/previous-coverage"
    (let [event {:event/type            :repo-index/coverage-changed
                 :event/id              (random-uuid)
                 :event/timestamp       (java.util.Date.)
                 :event/version         "1.0.0"
                 :event/sequence-number 0
                 :index/id              "main-code-index"
                 :index/coverage        0.93
                 :message               "Index coverage changed"}]
      (is (not (m/validate schema/RepoIndexCoverageChanged event))
          "missing :index/previous-coverage should fail"))))

;; ---------------------------------------------------------------------------
;; Constructor output and round-trip tests

(deftest sli-computed-constructor-test
  (testing "sli-computed constructor produces correct :event/type"
    (let [stream (make-stream)
          event  (es/sli-computed stream :availability 0.999 :rolling-1h)]
      (is (= :reliability/sli-computed (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= :availability (:sli/name event)))
      (is (= 0.999 (:sli/value event)))
      (is (= :rolling-1h (:sli/window event)))))

  (testing "sli-computed round-trip validates against SliComputed schema"
    (let [stream (make-stream)
          event  (es/sli-computed stream :availability 0.999 :rolling-1h)]
      (is (m/validate schema/SliComputed event)
          "constructor output must satisfy SliComputed schema")))

  (testing "sli-computed optional :sli/tier threads through"
    (let [stream (make-stream)
          event  (es/sli-computed stream :latency 0.95 :rolling-5m {:tier :critical})]
      (is (= :critical (:sli/tier event))))))

(deftest slo-breach-constructor-test
  (testing "slo-breach constructor produces correct :event/type"
    (let [stream (make-stream)
          event  (es/slo-breach stream :availability 0.999 0.980 :standard :rolling-1h)]
      (is (= :reliability/slo-breach (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= :availability (:slo/sli-name event)))
      (is (= 0.999 (:slo/target event)))
      (is (= 0.980 (:slo/actual event)))
      (is (= :standard (:slo/tier event)))
      (is (= :rolling-1h (:slo/window event)))))

  (testing "slo-breach round-trip validates against SloBreach schema"
    (let [stream (make-stream)
          event  (es/slo-breach stream :availability 0.999 0.980 :standard :rolling-1h)]
      (is (m/validate schema/SloBreach event)
          "constructor output must satisfy SloBreach schema"))))

(deftest error-budget-update-constructor-test
  (testing "error-budget-update constructor produces correct :event/type"
    (let [stream (make-stream)
          event  (es/error-budget-update stream :standard :availability 0.72 1.4 :rolling-30d)]
      (is (= :reliability/error-budget-update (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= :standard (:budget/tier event)))
      (is (= :availability (:budget/sli event)))
      (is (= 0.72 (:budget/remaining event)))
      (is (= 1.4 (:budget/burn-rate event)))
      (is (= :rolling-30d (:budget/window event)))))

  (testing "error-budget-update round-trip validates against ErrorBudgetUpdate schema"
    (let [stream (make-stream)
          event  (es/error-budget-update stream :standard :availability 0.72 1.4 :rolling-30d)]
      (is (m/validate schema/ErrorBudgetUpdate event)
          "constructor output must satisfy ErrorBudgetUpdate schema"))))

(deftest degradation-mode-changed-constructor-test
  (testing "degradation-mode-changed constructor produces correct :event/type"
    (let [stream (make-stream)
          event  (es/degradation-mode-changed stream :normal :degraded "error-rate exceeded")]
      (is (= :reliability/degradation-mode-changed (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= :normal (:degradation/from event)))
      (is (= :degraded (:degradation/to event)))
      (is (= "error-rate exceeded" (:degradation/trigger event)))))

  (testing "degradation-mode-changed round-trip validates against DegradationModeChanged schema"
    (let [stream (make-stream)
          event  (es/degradation-mode-changed stream :normal :degraded "error-rate exceeded")]
      (is (m/validate schema/DegradationModeChanged event)
          "constructor output must satisfy DegradationModeChanged schema"))))

(deftest repo-index-quality-measured-constructor-test
  (testing "repo-index-quality-measured produces correct :event/type"
    (let [stream (make-stream)
          event  (es/repo-index-quality-measured stream "main-code-index" 0.87 0.93 300000)]
      (is (= :repo-index/quality-measured (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= "main-code-index" (:index/id event)))
      (is (= 0.87 (:index/quality-score event)))
      (is (= 0.93 (:index/coverage event)))
      (is (= 300000 (:index/staleness-ms event)))))

  (testing "repo-index-quality-measured round-trip validates against RepoIndexQualityMeasured"
    (let [stream (make-stream)
          event  (es/repo-index-quality-measured stream "main-code-index" 0.87 0.93 300000)]
      (is (m/validate schema/RepoIndexQualityMeasured event)
          "constructor output must satisfy RepoIndexQualityMeasured schema")))

  (testing "repo-index-quality-measured optional :index/measured-at threads through"
    (let [stream      (make-stream)
          measured-at (java.util.Date.)
          event       (es/repo-index-quality-measured
                       stream "main-code-index" 0.87 0.93 300000
                       {:measured-at measured-at})]
      (is (= measured-at (:index/measured-at event))))))

(deftest repo-index-coverage-changed-constructor-test
  (testing "repo-index-coverage-changed produces correct :event/type"
    (let [stream (make-stream)
          event  (es/repo-index-coverage-changed stream "main-code-index" 0.80 0.93)]
      (is (= :repo-index/coverage-changed (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= "main-code-index" (:index/id event)))
      (is (= 0.80 (:index/previous-coverage event)))
      (is (= 0.93 (:index/coverage event)))))

  (testing "repo-index-coverage-changed round-trip validates against RepoIndexCoverageChanged"
    (let [stream (make-stream)
          event  (es/repo-index-coverage-changed stream "main-code-index" 0.80 0.93)]
      (is (m/validate schema/RepoIndexCoverageChanged event)
          "constructor output must satisfy RepoIndexCoverageChanged schema")))

  (testing "repo-index-coverage-changed optional :index/changed-files threads through"
    (let [stream (make-stream)
          event  (es/repo-index-coverage-changed
                  stream "main-code-index" 0.80 0.93
                  {:changed-files 42})]
      (is (= 42 (:index/changed-files event))))))
