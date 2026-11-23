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
(ns ai.miniforge.workflow.anomaly.required-operator-test
  "With `:require-operator?` set, the runner refuses to build a fresh run
   that carries no valid acting context. Resumes are refused earlier, by
   the resume command."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.tenancy.interface :as tenancy]
            [ai.miniforge.workflow.checkpoint-test-support :as checkpoint-test-support]
            [ai.miniforge.workflow.runner :as runner]))

(use-fixtures :each checkpoint-test-support/with-temp-checkpoint-root)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/phases []
   :workflow/pipeline []})

(def ^{:stratum 0} input {:repo-url "https://example.test/repo"})

(def ^{:stratum 0} acting
  (tenancy/establish-acting
   (tenancy/operator-identity "operator" (java.time.Instant/parse "2026-10-29T00:00:00Z"))
   (java.time.Instant/parse "2026-10-29T00:00:00Z")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} required-operator-refuses-an-unowned-run-test
  (testing "given :require-operator? and no :acting → :invalid-input anomaly"
    (let [result (runner/build-initial-context workflow input
                                               {:require-operator? true})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))))
  (testing "given an invalid :acting → refused, not substituted"
    (is (anomaly/anomaly?
         (runner/build-initial-context workflow input
                                       {:require-operator? true
                                        :acting {:acting/tenant-id "nope"}})))))

(deftest ^{:stratum 1} required-operator-admits-an-owned-run-test
  (let [result (runner/build-initial-context workflow input
                                             {:require-operator? true
                                              :acting acting})]
    (is (not (anomaly/anomaly? result)))
    (is (= acting (:execution/acting result)))))

(deftest ^{:stratum 1} required-operator-leaves-resumes-to-the-resume-command-test
  (testing "given a resume, the runner does not refuse: a refusal here would
            be persisted over the run's checkpoint"
    (is (not (anomaly/anomaly?
              (runner/build-initial-context
               workflow input
               {:require-operator? true
                :resume-machine-snapshot {:execution/id (random-uuid)}
                :resume-phase-results {}}))))))

(deftest ^{:stratum 1} unset-flag-keeps-unowned-runs-working-test
  (is (not (anomaly/anomaly? (runner/build-initial-context workflow input {})))))
