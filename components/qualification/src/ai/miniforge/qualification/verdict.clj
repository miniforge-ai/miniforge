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
(ns ai.miniforge.qualification.verdict
  "Join a gate's declared criteria with a scenario's results, and decide the
   verdict. A declared criterion with no result is unrun; only a run where
   every criterion passed is a pass."
  (:require
   [ai.miniforge.qualification.messages :as msg]
   [ai.miniforge.qualification.schema :as schema]
   [ai.miniforge.qualification.vocabulary :as vocab]
   [clojure.edn :as edn]
   [malli.core :as m]
   [malli.error :as me]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unrun-row
  "A report row for a criterion that was not run, saying why."
  [criterion reason]
  (assoc criterion :criterion/status :status/unrun :criterion/reason reason))

(defn ^{:stratum 0} verdict
  "`:verdict/fail` when any row failed, `:verdict/pass` when every row
   passed, `:verdict/incomplete` otherwise."
  [rows]
  (vocab/verdict-of-statuses (map :criterion/status rows)))

(defn ^{:stratum 0} result-problems
  "Messages for results the gate cannot accept: a result for a criterion
   the gate does not declare, or more than one result for one criterion."
  [gate results]
  (let [declared (set (map :criterion/id (:gate/criteria gate)))
        counts   (frequencies (map :criterion/id results))]
    (into (vec (for [id (distinct (map :criterion/id results))
                     :when (not (contains? declared id))]
                 (msg/t :result/undeclared {:id id})))
          (for [[id n] counts :when (> n 1)]
            (msg/t :result/duplicate {:id id})))))

(defn- ^{:stratum 0} plain-data?
  "True when `x` prints and reads back equal, so its content hash is the
   same on every run. A fn or other object does not."
  [x]
  (try+
   (= x (edn/read-string (pr-str x)))
   (catch map? _
     false)
   (catch Exception _
     false)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} output-problems
  "Why a scenario's output cannot be reported, or nil when it can: it is
   not a ScenarioOutput, its fixtures, config or results are not plain data
   (the hashes would not repeat, and the report could not be written as
   EDN), or its results do not match the gate."
  [gate output]
  (cond
    (not (m/validate schema/ScenarioOutput output))
    [(pr-str (me/humanize (m/explain schema/ScenarioOutput output)))]

    (not (plain-data? (select-keys output [:scenario/fixtures :scenario/config :scenario/results])))
    [(msg/t :scenario/not-plain-data)]

    :else
    (not-empty (result-problems gate (:scenario/results output)))))

(defn ^{:stratum 1} unrun-rows
  "Every declared criterion as unrun, for `reason`."
  [gate reason]
  (mapv #(unrun-row % reason) (:gate/criteria gate)))

(defn ^{:stratum 1} rows
  "One row per declared criterion, in declaration order: the criterion with
   its result; unrun when the scenario reported none; unrun when it reported
   more than one, since no single result stands for the criterion. Results
   for undeclared criteria have no row; `result-problems` names them."
  [gate results]
  (let [by-id (group-by :criterion/id results)]
    (mapv (fn [{:criterion/keys [id] :as criterion}]
            (let [found (get by-id id)]
              (case (count found)
                0 (unrun-row criterion (msg/t :criterion/no-result))
                1 (merge criterion (first found))
                (unrun-row criterion (msg/t :result/duplicate {:id id})))))
          (:gate/criteria gate))))

(comment
  (verdict (rows {:gate/criteria [{:criterion/id :a :criterion/description "A"}]} [])))
