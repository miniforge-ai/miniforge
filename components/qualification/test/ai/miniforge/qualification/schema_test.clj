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
(ns ai.miniforge.qualification.schema-test
  "A report is a closed map: an unknown key, an unknown status or a
   non-hex hash is not a report."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.qualification.interface :as qualification]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private report
  {:report/version      "qualification-report/v1"
   :report/gate-id      :fixture/gate
   :report/gate-title   "Fixture gate"
   :report/command      ["bb" "qualify" "fixture/gate"]
   :report/build        {:build/git-sha   (apply str (repeat 40 "a"))
                         :build/clean?    true
                         :build/deps-hash (apply str (repeat 64 "b"))}
   :report/fixture-hash (apply str (repeat 64 "c"))
   :report/config-hash  (apply str (repeat 64 "d"))
   :report/started-at   "2026-11-08T12:00:00Z"
   :report/finished-at  "2026-11-08T12:00:01Z"
   :report/criteria     [{:criterion/id          :c/one
                          :criterion/description "First criterion"
                          :criterion/status      :status/pass
                          :criterion/faults      [{:fault/kind :transform/missing}]
                          :criterion/observed    {:sink-calls 0}}]
   :report/verdict      :verdict/pass})

(deftest ^{:stratum 0} test-gate-criterion-ids-are-unique
  (testing "given two criteria with one id → not a gate"
    (is (not (m/validate qualification/Gate
                         {:gate/id       :fixture/gate
                          :gate/title    "Fixture gate"
                          :gate/criteria [{:criterion/id :c/one :criterion/description "First"}
                                          {:criterion/id :c/one :criterion/description "Second"}]})))))

;------------------------------------------------------------------------------ Layer 1

;; Tests
(deftest ^{:stratum 1} test-well-formed-report
  (testing "given a complete report → valid"
    (is (qualification/valid-report? report))))

(deftest ^{:stratum 1} test-malformed-reports
  (testing "given an unknown key, an unknown status, a short hash, no criteria or a blank executable → invalid"
    (doseq [bad [(assoc report :report/extra true)
                 (assoc-in report [:report/criteria 0 :criterion/status] :status/maybe)
                 (assoc report :report/fixture-hash "abc")
                 (assoc report :report/criteria [])
                 (assoc report :report/command [" " "qualify"])]]
      (is (not (qualification/valid-report? bad)) (pr-str (keys bad))))))

(deftest ^{:stratum 1} test-verdict-must-match-rows
  (testing "given a pass verdict over a failed, unrun or unsupported row → invalid"
    (doseq [status [:status/fail :status/unrun :status/unsupported]]
      (is (not (qualification/valid-report?
                (assoc-in report [:report/criteria 0 :criterion/status] status)))
          (str status))))
  (testing "given an unrun row and the incomplete verdict it gives → valid"
    (is (qualification/valid-report?
         (-> report
             (assoc-in [:report/criteria 0 :criterion/status] :status/unrun)
             (assoc :report/verdict :verdict/incomplete))))))

(deftest ^{:stratum 1} test-command-arguments-may-be-empty
  (testing "given an empty argument after the executable → valid, so the command repeats exactly"
    (is (qualification/valid-report? (assoc report :report/command ["bb" "qualify" "--filter" ""])))))
