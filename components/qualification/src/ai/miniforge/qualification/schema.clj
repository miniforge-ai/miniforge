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
(ns ai.miniforge.qualification.schema
  "Values of a qualification run: the gate a scenario qualifies, the result
   it reports per criterion, the build it ran on, and the report the harness
   writes. Every map is closed: an unknown key is a format change."
  (:require
   [ai.miniforge.qualification.vocabulary :as vocab]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Criterion
  "One acceptance criterion a gate declares."
  [:map {:closed true}
   [:criterion/id :keyword]
   [:criterion/description vocab/NonBlank]])

(def ^{:stratum 0} criterion-result-entries
  "The keys a scenario reports for one criterion: its status, why when it
   did not pass, the faults it injected, and the state it observed
   (records, effects, external request counts)."
  [[:criterion/id :keyword]
   [:criterion/status (into [:enum] vocab/statuses)]
   [:criterion/reason {:optional true} vocab/NonBlank]
   [:criterion/faults {:optional true} [:vector :any]]
   [:criterion/observed {:optional true} [:map-of :keyword :any]]])

(def ^{:stratum 0} Build
  "The build a run used. `:build/clean? false` covers both a dirty tree and
   one whose state could not be read; neither is an immutable build."
  [:map {:closed true}
   [:build/git-sha [:maybe vocab/GitSha]]
   [:build/clean? :boolean]
   [:build/deps-hash vocab/Sha256Hex]])

(def ^{:stratum 0} Failure
  "Why a scenario produced no usable results."
  [:map {:closed true}
   [:error/kind (into [:enum] vocab/failure-kinds)]
   [:error/message vocab/NonBlank]])

(defn- ^{:stratum 0} unique-criterion-ids?
  "True unless `criteria` is a non-empty sequence that repeats a criterion
   id. Another shape is left for the map schema to report."
  [criteria]
  (or (not (and (sequential? criteria) (seq criteria)))
      (apply distinct? (map :criterion/id criteria))))

(defn- ^{:stratum 0} failure-leaves-rows-unrun?
  "True unless `report` names a failure beside a row that is not unrun."
  [report]
  (let [criteria (:report/criteria report)]
    (or (nil? (:report/failure report))
        (not (sequential? criteria))
        (every? #(= :status/unrun (:criterion/status %)) criteria))))

(defn- ^{:stratum 0} report-pinned?
  [report]
  (vocab/pinned? (:report/build report)
                 (:report/fixture-hash report)
                 (:report/config-hash report)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} gate-criterion-ids-unique?
  [gate]
  (unique-criterion-ids? (:gate/criteria gate)))

(defn- ^{:stratum 1} report-criterion-ids-unique?
  [report]
  (unique-criterion-ids? (:report/criteria report)))

(defn- ^{:stratum 1} stated-verdict-matches-rows?
  "True when `report` states the verdict its statuses and build give."
  [report]
  (let [criteria (:report/criteria report)]
    (or (not (sequential? criteria))
        (= (:report/verdict report)
           (vocab/report-verdict (map :criterion/status criteria)
                                 (report-pinned? report))))))

(def ^{:stratum 1} CriterionResult
  "What a scenario reports for one criterion."
  (into [:map {:closed true}] criterion-result-entries))

(def ^{:stratum 1} Row
  "One criterion in a report: the gate's declaration and its result."
  (into [:map {:closed true} [:criterion/description vocab/NonBlank]]
        criterion-result-entries))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Gate
  "A gate: the criteria a qualification run must report on. Criterion ids
   are unique, so each result counts for one declared criterion only."
  [:and
   [:map {:closed true}
    [:gate/id :qualified-keyword]
    [:gate/title vocab/NonBlank]
    [:gate/issue {:optional true} pos-int?]
    [:gate/criteria [:vector {:min 1} Criterion]]]
   [:fn {:error/message "criterion ids must be unique"} gate-criterion-ids-unique?]])

(def ^{:stratum 2} ScenarioOutput
  "What a scenario fn returns. Fixtures and config are data, so the report
   can hash them."
  [:map {:closed true}
   [:scenario/fixtures :any]
   [:scenario/config :any]
   [:scenario/results [:vector CriterionResult]]])

(def ^{:stratum 2} Report
  "A machine-readable qualification report, tied to the build, the fixtures
   and the config it ran with. The command's executable is nonblank; its
   arguments may be any string. Criterion ids are unique. A report with a
   failure has only unrun rows. The verdict is the one its rows give, and a
   pass requires pinned evidence: a clean, identified commit and hashed
   fixtures and config."
  [:and
   [:map {:closed true}
    [:report/version [:= vocab/report-version]]
    [:report/gate-id :qualified-keyword]
    [:report/gate-title vocab/NonBlank]
    [:report/issue {:optional true} pos-int?]
    [:report/command [:and [:vector :string] [:cat vocab/NonBlank [:* :string]]]]
    [:report/build Build]
    [:report/fixture-hash [:maybe vocab/Sha256Hex]]
    [:report/config-hash [:maybe vocab/Sha256Hex]]
    [:report/started-at vocab/NonBlank]
    [:report/finished-at vocab/NonBlank]
    [:report/criteria [:vector {:min 1} Row]]
    [:report/verdict (into [:enum] vocab/verdicts)]
    [:report/failure {:optional true} Failure]]
   [:fn {:error/message "criterion ids must be unique"} report-criterion-ids-unique?]
   [:fn {:error/message "a report with a failure has only unrun rows"} failure-leaves-rows-unrun?]
   [:fn {:error/message "verdict must match the criteria statuses and the build"}
    stated-verdict-matches-rows?]])

(comment
  (require '[malli.core :as m])
  (m/validate Gate {:gate/id :pipeline/preflight :gate/title "Preflight"
                    :gate/criteria [{:criterion/id :c1 :criterion/description "One"}]}))
