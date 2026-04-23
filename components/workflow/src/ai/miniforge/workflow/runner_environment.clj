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

(ns ai.miniforge.workflow.runner-environment
  "Execution environment lifecycle for workflow pipeline.

   Manages worktree and Docker capsule acquisition, release, and
   workspace persistence at phase boundaries. Extracted from runner.clj."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.context :as context]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-defaults :as defaults]))

(defonce ^:private env-logger
  (log/create-logger {:min-level :debug :output :human}))

;------------------------------------------------------------------------------ Layer 0
;; Executor function references

(defn dag-executor-fns
  "DAG executor function references — resolved at call time so with-redefs works."
  []
  {:create-registry dag/create-executor-registry
   :select-exec     dag/select-executor
   :acquire-env!    dag/acquire-environment!
   :executor-type   dag/executor-type
   :result-ok?      dag/ok?
   :result-unwrap   dag/unwrap})

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(defn registry-config-for-mode
  "Build executor registry config for execution mode.
   :governed — Docker/K8s only (no worktree; no-silent-downgrade per N11 §7.4).
   :local    — worktree only."
  [mode executor-config]
  (case mode
    :governed (merge (select-keys (or executor-config {}) [:docker :kubernetes])
                     (when-not executor-config
                       {:docker {:image (defaults/default-docker-image)}}))
    {:worktree {}}))

(defn select-capsule-executor
  "Select executor for mode; rejects worktree fallback in governed mode (N11 §7.4)."
  [registry mode {:keys [select-exec executor-type]}]
  (let [raw (case mode
              :governed (select-exec registry)
              (select-exec registry :preferred :worktree))]
    (when-not (and (= :governed mode) raw (= :worktree (executor-type raw)))
      raw)))

(defn assert-executor-for-mode!
  "Throws for :governed mode when no capsule executor is available."
  [executor mode]
  (when (and (nil? executor) (= :governed mode))
    (response/throw-anomaly! :anomalies.executor/unavailable
                             (messages/t :governed/no-capsule)
                             {:anomaly.executor/mode :governed
                              :hint (messages/t :governed/no-capsule-hint)})))

;------------------------------------------------------------------------------ Layer 1
;; Environment record construction

(defn build-env-record
  "Acquire environment from executor and build the result map."
  [executor workflow-id mode env-config {:keys [acquire-env! result-ok? result-unwrap]}]
  (let [task-id    (if (uuid? workflow-id) workflow-id (random-uuid))
        env-result (acquire-env! executor task-id env-config)]
    (when (result-ok? env-result)
      (let [env (result-unwrap env-result)]
        {:executor             executor
         :environment-id       (:environment-id env)
         :worktree-path        (:workdir env)
         :execution-mode       mode
         :environment-metadata (:metadata env)}))))

(defn- acquire-worktree-and-capsule
  "Acquire both a host worktree and a capsule for governed mode."
  [executor workflow-id mode env-config fns]
  (let [wt-registry  ((:create-registry fns) {:worktree {}})
        wt-executor  ((:select-exec fns) wt-registry :preferred :worktree)
        wt-result    (when wt-executor
                       (build-env-record wt-executor workflow-id mode env-config fns))
        capsule      (build-env-record executor workflow-id mode env-config fns)]
    (when (and wt-result capsule)
      (assoc capsule
             :worktree-path (:worktree-path wt-result)
             :worktree-executor wt-executor
             :worktree-environment-id (:environment-id wt-result)))))

;------------------------------------------------------------------------------ Layer 2
;; Full acquisition and release

(defn acquire-execution-environment!
  "Acquire an isolated execution environment before pipeline starts.
   Returns env map, or nil (local) / throws (governed) on failure."
  [workflow-id {:keys [repo-url branch execution-mode executor-config]}]
  (let [mode (get {:governed :governed} execution-mode :local)]
    (try
      (let [fns      (dag-executor-fns)
            config   (registry-config-for-mode mode executor-config)
            registry ((:create-registry fns) config)
            executor (select-capsule-executor registry mode fns)
            env-config (cond-> {}
                         repo-url (assoc :repo-url repo-url)
                         branch   (assoc :branch branch))]
        (assert-executor-for-mode! executor mode)
        (when executor
          (if (= :governed mode)
            (acquire-worktree-and-capsule executor workflow-id mode env-config fns)
            (build-env-record executor workflow-id mode env-config fns))))
      (catch Exception e
        (when (= :governed mode) (throw e))
        (log/warn env-logger :workflow :workflow/env-acquisition-failed
                  {:message (messages/t :env/acquisition-failed {:error (ex-message e)})})
        nil))))

(defn release-execution-environment!
  "Release an execution environment. Safe to call with nil values."
  [executor environment-id]
  (when (and executor environment-id)
    (try
      (dag/release-environment! executor environment-id)
      (catch Exception e
        (println (messages/t :env/release-failed {:error (ex-message e)}))))))

(defn- boundary-phase
  [phase-ctx]
  (if-some [current-phase (get phase-ctx :execution/current-phase)]
    current-phase
    (if-some [last-phase (context/active-or-last-phase phase-ctx)]
      last-phase
      :unknown)))

(defn persist-workspace-at-phase-boundary!
  "Persist workspace to task branch after phase completes (governed mode only)."
  [context phase-ctx]
  (when-let [executor (get context :execution/executor)]
    (when (= :governed (get context :execution/mode))
      (let [env-id (get context :execution/environment-id)
            branch (get context :execution/task-branch)
            phase  (boundary-phase phase-ctx)]
        (try
          (dag/persist-workspace! executor env-id
                                       {:branch  branch
                                        :message (messages/t :env/persist-message {:phase (name phase)})
                                        :workdir (get context :execution/worktree-path)})
          (catch Exception e
            (log/warn env-logger :workflow :workflow/persist-failed
                      {:message (messages/t :env/persist-failed {:error (ex-message e)})})
            nil))))))
