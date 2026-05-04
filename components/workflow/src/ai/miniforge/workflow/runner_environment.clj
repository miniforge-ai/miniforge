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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
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

(defn check-executor-for-mode-anomaly
  "Anomaly-returning variant of [[assert-executor-for-mode!]].

   Returns nil when the `executor`/`mode` pair is acceptable, or an
   `:unavailable` anomaly when `:governed` mode was requested but no
   capsule executor was supplied. The anomaly's `:anomaly/data` carries
   `:mode` and a `:hint` string for surface code that wants to suggest
   remediation.

   Prefer this over [[assert-executor-for-mode!]] in non-boundary code."
  [executor mode]
  (when (and (nil? executor) (= :governed mode))
    (anomaly/anomaly :unavailable
                     (messages/t :governed/no-capsule)
                     {:mode :governed
                      :hint (messages/t :governed/no-capsule-hint)})))

(defn assert-executor-for-mode!
  "Throws for :governed mode when no capsule executor is available.

   DEPRECATED: prefer [[check-executor-for-mode-anomaly]], which returns
   an anomaly map instead of throwing. Retained for backward compatibility
   with callers that rely on the slingshot throw shape."
  {:deprecated "exceptions-as-data — prefer check-executor-for-mode-anomaly"}
  [executor mode]
  (when-let [a (check-executor-for-mode-anomaly executor mode)]
    (response/throw-anomaly! :anomalies.executor/unavailable
                             (:anomaly/message a)
                             {:anomaly.executor/mode (get-in a [:anomaly/data :mode])
                              :hint (get-in a [:anomaly/data :hint])})))

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

(def ^:private persist-tier-by-mode
  "Maps `:execution/mode` to the persist tier the executor is using.
   :governed routes through the docker/k8s tier's `git-persist!` (push to
   remote). :local routes through the worktree tier's `archive-bundle!`
   (git bundle on local disk). The event consumers (dashboard, evidence
   bundle) display the tier so users can tell push-to-remote checkpoints
   from local-archive checkpoints at a glance."
  {:governed :remote
   :local    :worktree})

(def ^:private default-persist-tier
  "Tier label used when `:execution/mode` is missing or unrecognized.
   Conservative default — local archive is always present in OSS."
  :worktree)

(defn- persist-tier-for
  [context]
  (get persist-tier-by-mode (get context :execution/mode) default-persist-tier))

(defn- persist-opts
  "Build the opts map passed to `dag/persist-workspace!`. Keeps map
   construction key-value with all derivations lifted into the let so
   readers see the shape of the call without a cond-> obscuring it."
  [context phase env-id]
  (let [metadata    (get context :execution/environment-metadata)
        branch      (get context :execution/task-branch)
        base-branch (:base-branch metadata)
        message     (messages/t :env/persist-message {:phase (name phase)})]
    (cond-> {:message     message
             :workdir     (get context :execution/worktree-path)
             :workflow-id (get context :execution/id)
             :task-id     env-id}
      branch      (assoc :branch branch)
      base-branch (assoc :base-branch base-branch))))

(defn- persist-event-data
  "Shared event/log payload for a persisted checkpoint. Both the log line
   and the `:workspace/persisted` event read from this — single source of
   truth for the persistence record."
  [context phase env-id data]
  {:phase        phase
   :env-id       env-id
   :branch       (:branch data)
   :commit-sha   (:commit-sha data)
   :bundle-path  (:bundle-path data)
   :persist-tier (persist-tier-for context)})

(defn- log-workspace-persisted!
  [event-data]
  (let [bundle-or-sha (or (:bundle-path event-data) (:commit-sha event-data))
        message       (str "Workspace persisted: " bundle-or-sha)]
    (log/info env-logger :workflow :workflow/workspace-persisted
              {:message message
               :data    event-data})))

(defn- publish-workspace-persisted!
  "Publish the `:workspace/persisted` event when the run has an event
   stream. Swallows publish exceptions — persistence already succeeded;
   a downstream subscriber's failure should not surface as a workflow
   error."
  [context event-data]
  (when-let [stream (get context :event-stream)]
    (try
      (es/publish! stream
                   (es/workspace-persisted stream
                                           (get context :execution/id)
                                           event-data))
      (catch Exception _e nil))))

(defn- announce-persisted!
  "Announce a successful persist on every visibility surface: log line,
   first-class event, and (transitively) the evidence-bundle collector
   that harvests events."
  [context phase env-id data]
  (let [event-data (persist-event-data context phase env-id data)]
    (log-workspace-persisted! event-data)
    (publish-workspace-persisted! context event-data)))

(defn persist-workspace-at-phase-boundary!
  "Persist workspace at phase completion.

   Both modes (:governed and :local) participate. :governed pushes to a
   remote via the docker/k8s tier's `git-persist!`; :local writes a git
   bundle via the worktree tier's `archive-bundle!` so work survives
   `release-environment!`.

   Earlier this was guarded on :governed only, which combined with the
   worktree tier's no-op `persist-workspace!` meant local-mode tasks
   lost their work the moment the scratch worktree was torn down. Per
   the fidelity goal in N11 §7.4, local should match governed in not
   destroying work on failure."
  [context phase-ctx]
  (when-let [executor (get context :execution/executor)]
    (let [env-id (get context :execution/environment-id)
          phase  (boundary-phase phase-ctx)]
      (when env-id
        (try
          (let [result (dag/persist-workspace! executor env-id
                                               (persist-opts context phase env-id))
                data   (when (dag/ok? result) (dag/unwrap result))]
            (when (:persisted? data)
              (announce-persisted! context phase env-id data))
            result)
          (catch Exception e
            (log/warn env-logger :workflow :workflow/persist-failed
                      {:message (messages/t :env/persist-failed
                                            {:error (ex-message e)})})
            nil))))))
