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
(ns ai.miniforge.cli.workflow-runner.display-result-facts
  "Pure readers that pull display facts out of a workflow execution result.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-summary-outcome
  [phase-result]
  (let [status (get phase-result :status :unknown)]
    (case status
      :success :completed
      :completed :completed
      :already-implemented :completed
      :done :completed
      :error :failure
      :failed :failure
      :failure :failure
      status)))

(defn- ^{:stratum 0} phase-summary-duration-ms
  [phase-result]
  (or (:duration-ms phase-result)
      (get-in phase-result [:metrics :duration-ms])
      (get-in phase-result [:phase/metrics :duration-ms])))

(defn- ^{:stratum 0} workflow-phase-result-entries
  [result]
  (when (map? result)
    (seq (:execution/phase-results result))))

(defn ^{:stratum 0} extract-failed-tasks
  "Read failed DAG task ids from the canonical workflow DAG result.

  Returns a vec of task-id strings, or nil when the workflow result does not
  carry `[:execution/dag-result :failed-task-ids]`."
  [result]
  (when-let [ids (seq (get-in result [:execution/dag-result :failed-task-ids]))]
    (not-empty (vec (map #(if (keyword? %) (name %) (str %)) ids)))))

(defn- ^{:stratum 0} pr-info-url
  [pr-info]
  (or (:pr-url pr-info)
      (:pr/url pr-info)))

(defn- ^{:stratum 0} workflow-pr-infos
  [result]
  (concat (get result :execution/dag-pr-infos [])
          (when-let [pr-info (:workflow/pr-info result)]
            [pr-info])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} phase-result-summary-entry
  [[phase-id phase-result]]
  (when (map? phase-result)
    {:phase       phase-id
     :outcome     (phase-summary-outcome phase-result)
     :duration-ms (phase-summary-duration-ms phase-result)}))

(defn ^{:stratum 1} extract-pr-urls
  "Extract PR URLs from workflow-owned PR info.

  Reads the canonical workflow context producers for PR info:
  `:execution/dag-pr-infos` for DAG runs and `:workflow/pr-info` for the
  release phase's single-PR path. Returns a vec of URL strings, or nil."
  [result]
  (when (map? result)
    (let [all (distinct (keep pr-info-url (workflow-pr-infos result)))]
      (not-empty (vec all)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} extract-phase-summaries
  "Extract phase summaries from canonical workflow execution results.

  Reads only `:execution/phase-results`, the workflow context's documented
  phase-result authority. Returns a vec of
  {:phase :outcome :duration-ms} maps, or nil when no phase breakdown is found."
  [result]
  (when-let [entries (workflow-phase-result-entries result)]
    (not-empty (vec (keep phase-result-summary-entry entries)))))
