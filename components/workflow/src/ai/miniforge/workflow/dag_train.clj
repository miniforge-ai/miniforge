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

(ns ai.miniforge.workflow.dag-train
  "Assembles a PR train from DAG execution results.

   After the DAG executor completes, each task has produced a PR.
   This module creates a PR train manager,
   adds all PRs, and links dependencies using the original DAG topology
   rather than linear merge order."
  (:require
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; DAG dependency extraction

(defn build-dag-deps-map
  "Build a task-id -> #{dep-task-ids} map from plan tasks.

   Arguments:
   - plan-tasks: Vector of task maps with :task/id and :task/dependencies

   Returns map of task-id to set of dependency task-ids."
  [plan-tasks]
  (->> plan-tasks
       (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]))
       (into {})))

(defn build-task-to-pr-map
  "Build a task-id -> pr-number map from PR infos.

   Arguments:
   - pr-infos: Vector of maps with :task-id and :pr-number

   Returns map of task-id to pr-number."
  [pr-infos]
  (->> pr-infos
       (map (fn [info] [(:task-id info) (:pr-number info)]))
       (into {})))

;------------------------------------------------------------------------------ Layer 1
;; Train assembly

(defn create-train-from-dag-result
  "Create a PR train from DAG execution results.

   Takes the DAG result (with :pr-infos) and the original plan tasks,
   creates a PR train manager, adds all PRs, and links dependencies
   using the original DAG topology.

   Arguments:
   - dag-result: DAG execution result map with :pr-infos
   - plan-tasks: Original plan tasks (for dependency topology)
   - opts: Options map
     - :train-name - Name for the train (default: 'DAG PR Train')
     - :event-stream - Optional event stream for merge events
     - :logger - Optional logger

   Returns:
   {:train-id UUID
    :manager  PRTrainManager instance
    :train    PRTrain map}"
  [dag-result plan-tasks & {:keys [train-name event-stream logger]
                             :or {train-name "DAG PR Train"}}]
  (let [pr-infos (:pr-infos dag-result)
        _ (when (and logger (seq pr-infos))
            (log/info logger :dag-train :train/assembling
                      {:data {:pr-count (count pr-infos)
                              :task-count (count plan-tasks)}}))

        ;; Create manager and train
        manager (pr-train/create-manager {:event-stream event-stream})
        dag-id (or (:dag-id dag-result) (random-uuid))
        train-id (pr-train/create-train manager train-name dag-id
                                         "Auto-assembled from DAG execution")

        ;; Add each PR to the train
        _ (doseq [{:keys [pr-number pr-url branch task-id]} pr-infos]
            (let [title (str "Task " (when task-id (subs (str task-id) 0 8)))]
              (pr-train/add-pr manager train-id
                                "repo" ; repo placeholder — will be set from worktree
                                pr-number pr-url branch title)))

        ;; Link dependencies using DAG topology
        dag-deps-map (build-dag-deps-map plan-tasks)
        task-to-pr-map (build-task-to-pr-map pr-infos)
        train (pr-train/link-prs-from-dag manager train-id dag-deps-map task-to-pr-map)]

    (when logger
      (log/info logger :dag-train :train/assembled
                {:data {:train-id train-id
                        :pr-count (count pr-infos)
                        :ready-to-merge (:train/ready-to-merge train)}}))

    {:train-id train-id
     :manager manager
     :train train}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: create train from mock DAG result
  (def mock-pr-infos
    [{:task-id :a :pr-number 101 :pr-url "https://github.com/repo/pull/101" :branch "task-a"}
     {:task-id :b :pr-number 102 :pr-url "https://github.com/repo/pull/102" :branch "task-b"}
     {:task-id :c :pr-number 103 :pr-url "https://github.com/repo/pull/103" :branch "task-c"}
     {:task-id :d :pr-number 104 :pr-url "https://github.com/repo/pull/104" :branch "task-d"}])

  (def mock-plan-tasks
    [{:task/id :a :task/dependencies []}
     {:task/id :b :task/dependencies [:a]}
     {:task/id :c :task/dependencies [:a]}
     {:task/id :d :task/dependencies [:b :c]}])

  (def result (create-train-from-dag-result
                {:pr-infos mock-pr-infos}
                mock-plan-tasks
                :train-name "Diamond DAG Test"))

  ;; PR 101 (task A) should have no deps, blocks [102 103]
  ;; PR 104 (task D) should depend on [102 103]
  (:train result)

  :leave-this-here)
