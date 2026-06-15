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
  "Schema conformance tests for the Layer 3 / Layer 3.5 event schemas:

     Layer 3 — reliability metrics (N3 §3.17, N1 §5.5):
       :reliability/sli-computed, :reliability/slo-breach,
       :reliability/error-budget-update, :reliability/degradation-mode-changed

     Layer 3.5 — repository index intelligence (RN-19/20):
       :repo-index/quality-measured, :repo-index/coverage-changed

   Three assertions per schema:
     1. A conforming map passes malli/validate.
     2. A map missing a required field fails malli/validate.
     3. Optional fields can be omitted without failing validation.

   Reliability schemas are exercised through the published interface
   constructors (es/sli-computed etc.) so field-name drift between
   constructors and schemas surfaces immediately.  Repo-index schemas
   have no constructors yet; conforming maps are built inline."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants — no magic literals in test bodies

(def ^:private rolling-window :rolling-5m)
(def ^:private standard-tier  :standard)
(def ^:private critical-tier  :critical)
(def ^:private normal-mode    :normal)
(def ^:private reduced-mode   :reduced-capacity)
(def ^:private sli-name-avail :availability)
(def ^:private slo-target     0.999)
(def ^:private slo-actual     0.995)
(def ^:private budget-remaining 0.42)
(def ^:private burn-rate       2.3)
(def ^:private quality-score   0.87)
(def ^:private staleness-ms    45000)
(def ^:private coverage-new    0.95)
(def ^:private coverage-old    0.88)

(defn- stream [] (es/create-event-stream))

;------------------------------------------------------------------------------ Layer 1
;; :reliability/sli-computed

(deftest sli-computed-valid-conforms-test
  (testing "conforming sli-computed event passes SliComputed schema"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window)]
      (is (= :reliability/sli-computed (:event/type ev)))
      (is (m/validate schema/SliComputed ev)))))

(deftest sli-computed-missing-required-field-fails-test
  (testing "missing :sli/name fails"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window)]
      (is (false? (m/validate schema/SliComputed (dissoc ev :sli/name))))))
  (testing "missing :sli/value fails"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window)]
      (is (false? (m/validate schema/SliComputed (dissoc ev :sli/value))))))
  (testing "missing :sli/window fails"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window)]
      (is (false? (m/validate schema/SliComputed (dissoc ev :sli/window)))))))

(deftest sli-computed-optional-fields-absent-test
  (testing "optional :sli/tier and :sli/dimensions may be absent"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window)]
      (is (not (contains? ev :sli/tier)))
      (is (not (contains? ev :sli/dimensions)))
      (is (m/validate schema/SliComputed ev)))))

(deftest sli-computed-optional-fields-present-test
  (testing "optional :sli/tier and :sli/dimensions are accepted when present"
    (let [ev (es/sli-computed (stream) sli-name-avail 0.999 rolling-window
                              {:tier standard-tier
                               :dimensions {:region "us-east-1"}})]
      (is (= standard-tier (:sli/tier ev)))
      (is (map? (:sli/dimensions ev)))
      (is (m/validate schema/SliComputed ev)))))

;------------------------------------------------------------------------------ Layer 1
;; :reliability/slo-breach

(deftest slo-breach-valid-conforms-test
  (testing "conforming slo-breach event passes SloBreach schema"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (= :reliability/slo-breach (:event/type ev)))
      (is (m/validate schema/SloBreach ev)))))

(deftest slo-breach-missing-required-field-fails-test
  (testing "missing :slo/sli-name fails"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (false? (m/validate schema/SloBreach (dissoc ev :slo/sli-name))))))
  (testing "missing :slo/target fails"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (false? (m/validate schema/SloBreach (dissoc ev :slo/target))))))
  (testing "missing :slo/actual fails"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (false? (m/validate schema/SloBreach (dissoc ev :slo/actual))))))
  (testing "missing :slo/tier fails"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (false? (m/validate schema/SloBreach (dissoc ev :slo/tier))))))
  (testing "missing :slo/window fails"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            standard-tier rolling-window)]
      (is (false? (m/validate schema/SloBreach (dissoc ev :slo/window)))))))

(deftest slo-breach-carries-breach-fields-test
  (testing "event carries all breach fields with correct values"
    (let [ev (es/slo-breach (stream) sli-name-avail slo-target slo-actual
                            critical-tier rolling-window)]
      (is (= sli-name-avail (:slo/sli-name ev)))
      (is (= slo-target     (:slo/target ev)))
      (is (= slo-actual     (:slo/actual ev)))
      (is (= critical-tier  (:slo/tier ev)))
      (is (= rolling-window (:slo/window ev))))))

;------------------------------------------------------------------------------ Layer 1
;; :reliability/error-budget-update

(deftest error-budget-update-valid-conforms-test
  (testing "conforming error-budget-update event passes ErrorBudgetUpdate schema"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (= :reliability/error-budget-update (:event/type ev)))
      (is (m/validate schema/ErrorBudgetUpdate ev)))))

(deftest error-budget-update-missing-required-field-fails-test
  (testing "missing :budget/tier fails"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (false? (m/validate schema/ErrorBudgetUpdate (dissoc ev :budget/tier))))))
  (testing "missing :budget/sli fails"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (false? (m/validate schema/ErrorBudgetUpdate (dissoc ev :budget/sli))))))
  (testing "missing :budget/remaining fails"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (false? (m/validate schema/ErrorBudgetUpdate (dissoc ev :budget/remaining))))))
  (testing "missing :budget/burn-rate fails"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (false? (m/validate schema/ErrorBudgetUpdate (dissoc ev :budget/burn-rate))))))
  (testing "missing :budget/window fails"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (false? (m/validate schema/ErrorBudgetUpdate (dissoc ev :budget/window)))))))

(deftest error-budget-update-carries-budget-fields-test
  (testing "event carries tier, sli, remaining, burn-rate, and window"
    (let [ev (es/error-budget-update (stream) standard-tier sli-name-avail
                                     budget-remaining burn-rate rolling-window)]
      (is (= standard-tier    (:budget/tier ev)))
      (is (= sli-name-avail   (:budget/sli ev)))
      (is (= budget-remaining (:budget/remaining ev)))
      (is (= burn-rate        (:budget/burn-rate ev)))
      (is (= rolling-window   (:budget/window ev))))))

;------------------------------------------------------------------------------ Layer 1
;; :reliability/degradation-mode-changed

(deftest degradation-mode-changed-valid-conforms-test
  (testing "conforming degradation-mode-changed event passes DegradationModeChanged schema"
    (let [ev (es/degradation-mode-changed (stream) normal-mode reduced-mode
                                          "SLO breach threshold crossed")]
      (is (= :reliability/degradation-mode-changed (:event/type ev)))
      (is (m/validate schema/DegradationModeChanged ev)))))

(deftest degradation-mode-changed-missing-required-field-fails-test
  (testing "missing :degradation/from fails"
    (let [ev (es/degradation-mode-changed (stream) normal-mode reduced-mode "t")]
      (is (false? (m/validate schema/DegradationModeChanged (dissoc ev :degradation/from))))))
  (testing "missing :degradation/to fails"
    (let [ev (es/degradation-mode-changed (stream) normal-mode reduced-mode "t")]
      (is (false? (m/validate schema/DegradationModeChanged (dissoc ev :degradation/to))))))
  (testing "missing :degradation/trigger fails"
    (let [ev (es/degradation-mode-changed (stream) normal-mode reduced-mode "t")]
      (is (false? (m/validate schema/DegradationModeChanged (dissoc ev :degradation/trigger)))))))

(deftest degradation-mode-changed-carries-transition-fields-test
  (testing "event carries from, to, and trigger with correct values"
    (let [ev (es/degradation-mode-changed (stream) normal-mode reduced-mode
                                          "SLO breach")]
      (is (= normal-mode   (:degradation/from ev)))
      (is (= reduced-mode  (:degradation/to ev)))
      (is (= "SLO breach"  (:degradation/trigger ev))))))

;------------------------------------------------------------------------------ Layer 2
;; :repo-index/quality-measured
;; No constructor yet — build conforming maps directly.

(defn- quality-measured-base []
  {:event/type            :repo-index/quality-measured
   :event/id              (random-uuid)
   :event/timestamp       (java.util.Date.)
   :event/version         "1.0.0"
   :event/sequence-number 0
   :index/id              "owner/repo"
   :index/quality-score   quality-score
   :index/staleness-ms    staleness-ms
   :index/coverage        coverage-new
   :message               "Index quality measured"})

(deftest repo-index-quality-measured-valid-conforms-test
  (testing "conforming :repo-index/quality-measured map passes RepoIndexQualityMeasured schema"
    (is (m/validate schema/RepoIndexQualityMeasured (quality-measured-base)))))

(deftest repo-index-quality-measured-missing-required-field-fails-test
  (testing "missing :index/id fails"
    (is (false? (m/validate schema/RepoIndexQualityMeasured
                            (dissoc (quality-measured-base) :index/id)))))
  (testing "missing :index/quality-score fails"
    (is (false? (m/validate schema/RepoIndexQualityMeasured
                            (dissoc (quality-measured-base) :index/quality-score)))))
  (testing "missing :index/staleness-ms fails"
    (is (false? (m/validate schema/RepoIndexQualityMeasured
                            (dissoc (quality-measured-base) :index/staleness-ms)))))
  (testing "missing :index/coverage fails"
    (is (false? (m/validate schema/RepoIndexQualityMeasured
                            (dissoc (quality-measured-base) :index/coverage))))))

(deftest repo-index-quality-measured-optional-absent-test
  (testing "optional :workflow/id and :index/tree-sha may be absent"
    (let [ev (quality-measured-base)]
      (is (not (contains? ev :workflow/id)))
      (is (not (contains? ev :index/tree-sha)))
      (is (m/validate schema/RepoIndexQualityMeasured ev)))))

(deftest repo-index-quality-measured-optional-present-test
  (testing "optional :workflow/id and :index/tree-sha are accepted when present"
    (let [ev (assoc (quality-measured-base)
                    :workflow/id (random-uuid)
                    :index/tree-sha "abc1234def5678abc1234def5678abc1234def5678abc")]
      (is (m/validate schema/RepoIndexQualityMeasured ev)))))

;------------------------------------------------------------------------------ Layer 2
;; :repo-index/coverage-changed

(defn- coverage-changed-base []
  {:event/type              :repo-index/coverage-changed
   :event/id                (random-uuid)
   :event/timestamp         (java.util.Date.)
   :event/version           "1.0.0"
   :event/sequence-number   0
   :index/id                "owner/repo"
   :index/coverage          coverage-new
   :index/previous-coverage coverage-old
   :message                 "Index coverage changed"})

(deftest repo-index-coverage-changed-valid-conforms-test
  (testing "conforming :repo-index/coverage-changed map passes RepoIndexCoverageChanged schema"
    (is (m/validate schema/RepoIndexCoverageChanged (coverage-changed-base)))))

(deftest repo-index-coverage-changed-missing-required-field-fails-test
  (testing "missing :index/id fails"
    (is (false? (m/validate schema/RepoIndexCoverageChanged
                            (dissoc (coverage-changed-base) :index/id)))))
  (testing "missing :index/coverage fails"
    (is (false? (m/validate schema/RepoIndexCoverageChanged
                            (dissoc (coverage-changed-base) :index/coverage)))))
  (testing "missing :index/previous-coverage fails"
    (is (false? (m/validate schema/RepoIndexCoverageChanged
                            (dissoc (coverage-changed-base) :index/previous-coverage))))))

(deftest repo-index-coverage-changed-optional-absent-test
  (testing "optional :workflow/id and :index/tree-sha may be absent"
    (let [ev (coverage-changed-base)]
      (is (not (contains? ev :workflow/id)))
      (is (not (contains? ev :index/tree-sha)))
      (is (m/validate schema/RepoIndexCoverageChanged ev)))))

(deftest repo-index-coverage-changed-optional-present-test
  (testing "optional :workflow/id and :index/tree-sha are accepted when present"
    (let [ev (assoc (coverage-changed-base)
                    :workflow/id (random-uuid)
                    :index/tree-sha "abc1234def5678abc1234def5678abc1234def5678abc")]
      (is (m/validate schema/RepoIndexCoverageChanged ev)))))

;------------------------------------------------------------------------------ Layer 3
;; Privacy defaults

(deftest reliability-privacy-defaults-test
  (testing "SLO breach and degradation mode change are :public (operator-visible)"
    (is (= :public (schema/event-privacy :reliability/slo-breach)))
    (is (= :public (schema/event-privacy :reliability/degradation-mode-changed))))
  (testing "SLI computed and error budget update are :internal"
    (is (= :internal (schema/event-privacy :reliability/sli-computed)))
    (is (= :internal (schema/event-privacy :reliability/error-budget-update)))))

(deftest repo-index-privacy-defaults-test
  (testing "both repo-index event types default to :internal"
    (is (= :internal (schema/event-privacy :repo-index/quality-measured)))
    (is (= :internal (schema/event-privacy :repo-index/coverage-changed)))))
