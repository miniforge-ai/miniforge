;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.trains
  "PR Train and DAG state accessors."
  (:require
   [ai.miniforge.web-dashboard.state.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; PR Train state

(def get-trains
  "Get all PR trains (cached 10s)."
  (core/ttl-memoize 10000
               (fn [state]
                 (if-let [mgr (:pr-train-manager @state)]
                   (or (core/safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])
                   []))))

(defn get-train-detail
  "Get detailed view of a PR train."
  [state train-id]
  (if-let [mgr (:pr-train-manager @state)]
    (or (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr (parse-uuid train-id))
        {:error "Train not found"})
    {:error "PR train manager not available"}))

(defn train-action!
  "Execute action on a PR train."
  [state train-id action]
  (when-let [mgr (:pr-train-manager @state)]
    (let [tid (parse-uuid train-id)]
      (case action
        "pause" (core/safe-call 'ai.miniforge.pr-train.interface 'pause-train mgr tid "Manual pause")
        "resume" (core/safe-call 'ai.miniforge.pr-train.interface 'resume-train mgr tid)
        "merge-next" (core/safe-call 'ai.miniforge.pr-train.interface 'merge-next mgr tid)
        nil))))

;------------------------------------------------------------------------------ Layer 1
;; DAG state

(def get-dags
  "Get all repository DAGs (cached 10s)."
  (core/ttl-memoize 10000
               (fn [state]
                 (if-let [mgr (:repo-dag-manager @state)]
                   (or (core/safe-call 'ai.miniforge.repo-dag.interface 'get-all-dags mgr) [])
                   []))))

;------------------------------------------------------------------------------ Layer 2
;; DAG composite state

(defn get-dag-state
  "Get DAG kanban state for visualization."
  [state]
  (let [dags (get-dags state)
        trains (get-trains state)]
    {:dags dags
     :trains trains
     :repos (mapcat :dag/repos dags)
     :tasks (mapcat (fn [train]
                      (map (fn [pr]
                             {:id (:pr/number pr)
                              :repo (:pr/repo pr)
                              :title (:pr/title pr)
                              :status (case (:pr/status pr)
                                        (:draft :open) :ready
                                        :reviewing :running
                                        :merged :done
                                        :failed :blocked
                                        :blocked)
                              :train-id (:train/id train)
                              :dependencies (:pr/depends-on pr)})
                           (:train/prs train)))
                    trains)}))
