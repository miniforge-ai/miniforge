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
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.boundary.environment :as boundary]
   [ai.miniforge.workflow.context :as context]
   [ai.miniforge.workflow.environment-notifications :as notifications]
   [ai.miniforge.workflow.environment-records :as records]
   [ai.miniforge.workflow.governed-environment :as governed]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-defaults :as defaults]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:stratum 0} ^:private env-logger
  (log/create-logger {:min-level :debug :output :human}))

;; Executor function references
(defn ^{:stratum 0} dag-executor-fns
  "DAG executor function references — resolved at call time so with-redefs works."
  []
  {:create-registry dag/create-executor-registry
   :select-exec     dag/select-executor
   :acquire-env!    dag/acquire-environment!
   :executor-type   dag/executor-type
   :available?      dag/executor-available?
   :result-ok?      dag/ok?
   :result-unwrap   dag/unwrap})

;; Configuration
(defn ^{:stratum 0} registry-config-for-mode
  "Build executor registry config for execution mode.
   :governed — Docker/K8s only (no worktree; no-silent-downgrade per N11 §7.4).
   :local    — worktree only."
  [mode executor-config]
  (case mode
    :governed (merge (select-keys (or executor-config {}) [:docker :kubernetes])
                     (when-not executor-config
                       {:docker {:image (defaults/default-docker-image)}}))
    {:worktree {}}))

(defn ^{:stratum 0} with-runtime-config
  "Merge the caller's container-runtime selection config (the CLI sets
   :runtime-kind from MINIFORGE_RUNTIME) into the registry's :docker slot.
   Without an explicit kind the dag-executor auto-probes the host runtime
   (Podman first, then Docker)."
  [registry-config runtime-config]
  (cond-> registry-config
    (and (:docker registry-config) (seq runtime-config))
    (update :docker merge runtime-config)))

(defn ^{:stratum 0} select-capsule-executor
  "Select executor for mode; rejects worktree fallback in governed mode (N11 §7.4)."
  [registry mode {:keys [select-exec executor-type]}]
  (let [raw (case mode
              :governed (select-exec registry)
              (select-exec registry :preferred :worktree))]
    (when-not (and (= :governed mode) raw (= :worktree (executor-type raw)))
      raw)))

(defn ^{:stratum 0} runtime-selection-failure
  "The container-runtime selection error behind an empty capsule slot, or
   nil. A registry slot whose runtime selection failed holds an executor
   of type :unavailable; its availability result carries the selector's
   error ({:code :message :data}). Returns that error with :detail added:
   the message followed by each runtime's reason as `kind: reason` (the
   explicit kind's, or every probed kind's after an auto-probe)."
  [registry {:keys [executor-type available?]}]
  (when-let [error (when (and (map? registry) available?)
                     (some (fn [exec]
                             (when (= :unavailable (executor-type exec))
                               (get-in (available? exec) [:data :error])))
                           (vals registry)))]
    (let [data    (:data error)
          reasons (->> (get data :probed [data])
                       (keep (fn [{:keys [kind reason]}]
                               (when (and kind reason)
                                 (str (name kind) ": " reason))))
                       (str/join "; "))]
      (assoc error :detail (cond-> (str (:message error))
                             (seq reasons) (str " (" reasons ")"))))))

(defn ^{:stratum 0} check-executor-for-mode
  "Validate executor / mode pairing per the N11 §7.4 invariant.

   Returns nil when the pair is acceptable, or an `:unavailable`
   anomaly when `:governed` mode was requested but no capsule executor
   was supplied. The anomaly's `:anomaly/data` carries `:mode` and a
   `:hint` string for surface code that wants to suggest remediation.
   Given the `selection-failure` that left the capsule slot empty (see
   `runtime-selection-failure`), the anomaly names it, and its data
   carries it under `:runtime-selection`.

   This is the canonical, anomaly-returning entry point. Boundary
   sites that need to escalate to a thrown error (e.g.
   `acquire-execution-environment!`) inline a `response/throw-anomaly!`
   at the call site rather than calling a separate thrower."
  ([executor mode]
   (check-executor-for-mode executor mode nil))
  ([executor mode selection-failure]
   (when (and (nil? executor) (= :governed mode))
     (if selection-failure
       (anomaly/anomaly :unavailable
                        (messages/t :governed/runtime-selection-failed
                                    {:detail (:detail selection-failure)})
                        {:mode :governed
                         :hint (messages/t :governed/runtime-selection-hint)
                         :runtime-selection (dissoc selection-failure :detail)})
       (anomaly/anomaly :unavailable
                        (messages/t :governed/no-capsule)
                        {:mode :governed
                         :hint (messages/t :governed/no-capsule-hint)})))))

;; Environment record construction
(def ^{:stratum 0} build-env-record records/build)

(def ^{:stratum 0} release-execution-environment!
  "Release nil-safely; ordinary failures warn, critical causes propagate."
  boundary/release!)

(defn- ^{:stratum 0} boundary-phase
  [phase-ctx]
  (if-some [current-phase (get phase-ctx :execution/current-phase)]
    current-phase
    (if-some [last-phase (context/active-or-last-phase phase-ctx)]
      last-phase
      :unknown)))

(defn- ^{:stratum 0} persist-opts
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

(defn- ^{:stratum 0} gate-denial
  "How the gates judged phase RAN in PHASE-CTX: :secrets-detected when
   :no-secrets denied it, :gate-denied when another gate did, else nil."
  [phase-ctx ran]
  (let [result (get-in phase-ctx [:execution/phase-results ran])]
    (cond
      (some #(= :no-secrets (:gate %)) (:phase/gate-failures result)) :secrets-detected
      (seq (:phase/gate-errors result)) :gate-denied)))

(defn- ^{:stratum 0} task-base-refs
  "The implement phase's resume-aware base refs: carried in the context,
   or, after a resume, recorded on its phase result."
  [phase-ctx]
  (or (get phase-ctx :execution/task-base-refs)
      (some (fn [result] (or (get-in result [:artifact :code/base-refs])
                             (get-in result [:result :output :code/base-refs])))
            (vals (get phase-ctx :execution/phase-results)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} restore-local-workspace-checkpoint!
  "Restore a persisted local workspace branch before acquiring a worktree.

   Local resume must make the last persisted branch visible before
   `git worktree add` runs. Governed runtimes restore inside the capsule;
   local worktrees are acquired from a host ref, so the host ref has to
   exist first."
  [executor repo-path {:keys [bundle-path branch] :as checkpoint}]
  (when (and executor bundle-path branch)
    (try
      (let [result (dag/restore-workspace! executor nil
                                           {:host-repo-path (or repo-path ".")
                                            :workdir (or repo-path ".")
                                            :bundle-path bundle-path
                                            :branch branch})]
        (when-not (dag/ok? result)
          (log/warn env-logger :workflow :workflow/restore-failed
                    {:message (messages/t :env/restore-failed
                                          {:error (str result)})
                     :data checkpoint}))
        result)
      (catch Exception e
        (boundary/preserve-critical! e)
        (log/warn env-logger :workflow :workflow/restore-failed
                  {:message (messages/t :env/restore-failed
                                        {:error (ex-message e)})
                   :data checkpoint})
        nil))))

(defn ^{:stratum 1} persist-workspace-at-phase-boundary!
  "Persist workspace at phase completion.

   Both modes (:governed and :local) participate. :governed pushes to a
   remote via the docker/k8s tier's `git-persist!`; :local writes a git
   bundle via the worktree tier's `archive-bundle!` so work survives
   `release-environment!`.

   Earlier this was guarded on :governed only, which combined with the
   worktree tier's no-op `persist-workspace!` meant local-mode tasks
   lost their work the moment the scratch worktree was torn down. Per
   the fidelity goal in N11 §7.4, local should match governed in not
   destroying work on failure.

   A phase its gates denied is not published, and no phase publishes a
   worktree whose outgoing changes carry a secret (gate/outgoing-secrets
   runs before every commit or push). When :no-secrets denied it, or the
   scan finds a secret, nothing is committed at all: a commit would carry the secret into
   history, and the next successful phase's push sends history. When
   another gate denied it, or the worktree cannot be scanned, the work is
   committed but not pushed (`:push? false`); the next persist that can
   scan it and finds nothing pushes the held commits."
  [context phase-ctx]
  (let [executor (get context :execution/executor)
        env-id   (get context :execution/environment-id)
        mode     (get context :execution/mode)
        ;; The phase step enriches its own context with the worktree it
        ;; ran in; a loop context that never carried the path must not
        ;; turn an allowed implement into work that is never committed.
        workdir  (or (get context :execution/worktree-path)
                     (get phase-ctx :execution/worktree-path))
        phase    (boundary-phase phase-ctx)
        ;; The phase that just ran is the one the pre-step context was on.
        denial   (gate-denial phase-ctx (boundary-phase context))
        ;; The :no-secrets check over what a commit or push would publish,
        ;; run only when the persist would otherwise go ahead.
        scan     (delay (gate/outgoing-secrets (assoc context
                                                      :execution/worktree-path workdir
                                                      :execution/task-base-refs (task-base-refs phase-ctx))))
        found?   (fn [error-type] (some #(= error-type (:type %)) (:errors @scan)))
        ;; Governed mode pushes to a remote via the capsule, so it persists with
        ;; or without a host worktree. LOCAL mode archives a git bundle of the
        ;; worktree — with NO worktree-path there is nothing to archive and the
        ;; git tier falls back to the process CWD, committing the HOST repo.
        ;; That is what let a caller that drove run-pipeline without a sandbox
        ;; leak a real commit into the live worktree. So local persistence
        ;; requires a worktree.
        skip     (cond
                   (= :secrets-detected denial) :secrets-detected
                   (nil? executor) :no-executor
                   (nil? env-id) :no-environment-id
                   (not (or (= :governed mode) workdir)) :no-worktree
                   ;; Whatever phase is persisting, a commit or push must not
                   ;; carry a credential an earlier phase left in the worktree
                   ;; (a redirect after a denial runs other phases over it).
                   (found? :secrets-detected) :secrets-detected)]
    (if skip
      ;; A skipped persist is a silent loss of work unless it is said out
      ;; loud: trap-bench series 4/5 read an allowed implement whose fix
      ;; was never on the task branch, with nothing in the log or events.
      (do (log/warn env-logger :workflow :workflow/persist-skipped
                    {:message (messages/t :env/persist-skipped
                                          {:phase (name phase) :reason (name skip)})
                     :data {:phase phase :reason skip :mode mode
                            :environment-id? (some? env-id)
                            :worktree? (some? workdir)}})
          nil)
      (try
        (let [;; Unscanned work is committed but held back from the remote
              ;; until a later persist can inspect it and finds nothing.
              hold   (or denial (when (found? :worktree-uninspectable) :worktree-uninspectable))
              _      (when (= :worktree-uninspectable hold)
                       (log/warn env-logger :workflow :workflow/persist-unscanned
                                 {:message (messages/t :env/persist-unscanned {:phase (name phase)})
                                  :data {:phase phase :reason hold}}))
              result (dag/persist-workspace! executor env-id
                                             (cond-> (-> (persist-opts context phase env-id)
                                                         (assoc :workdir workdir))
                                               hold (assoc :push? false)))
              data   (when (dag/ok? result) (dag/unwrap result))]
          (cond
            (not (dag/ok? result))
            (log/warn env-logger :workflow :workflow/persist-rejected
                      {:message (messages/t :env/persist-rejected
                                            {:phase (name phase)
                                             :code (str (get-in result [:error :code]))})
                       :data {:phase phase :result result}})

            (and (:persisted? data) (false? (:pushed? data)))
            (log/warn env-logger :workflow :workflow/persist-held
                      {:message (messages/t :env/persist-held {:phase (name phase)})
                       :data {:phase phase :reason hold :branch (:branch data)}})

            (:persisted? data)
            (notifications/announce! env-logger context phase env-id data)

            :else
            (log/info env-logger :workflow :workflow/persist-no-changes
                      {:message (messages/t :env/persist-no-changes {:phase (name phase)})
                       :data {:phase phase :branch (:branch data)}}))
          result)
        (catch Exception e
          (boundary/preserve-critical! e)
          (log/warn env-logger :workflow :workflow/persist-failed
                    {:message (messages/t :env/persist-failed
                                          {:error (ex-message e)})
                     :data {:phase phase :error (ex-message e)}})
          nil)))))

;------------------------------------------------------------------------------ Layer 2

;; Full acquisition and release
(defn ^{:stratum 2} acquire-execution-environment!
  "Acquire an isolated execution environment before pipeline starts.
   Boundary wrapper around the anomaly-returning executor availability check.

   Returns env map, or nil (local) / throws (governed) on failure. Governed
   acquisition failure deliberately escalates for legacy runner callers that
   rely on try+ exception flow; prefer anomaly-returning helpers such as
   `check-executor-for-mode` for non-boundary branching."
  [workflow-id {:keys [repo-url branch repo-path execution-mode executor-config
                       runtime-config resume-workspace]}]
  (let [mode (get {:governed :governed} execution-mode :local)]
    (try
      (let [fns      (dag-executor-fns)
            config   (with-runtime-config (registry-config-for-mode mode executor-config)
                                          runtime-config)
            registry ((:create-registry fns) config)
            executor (select-capsule-executor registry mode fns)
            branch   (or (:branch resume-workspace) branch)
            env-config (cond-> {}
                         repo-url (assoc :repo-url repo-url)
                         branch   (assoc :branch branch)
                         repo-path (assoc :repo-path repo-path))]
        (when-let [a (check-executor-for-mode
                      executor mode
                      (when-not executor (runtime-selection-failure registry fns)))]
          ;; Boundary throw: acquire-execution-environment! is the
          ;; runner's escalation point for governed-mode unavailability.
          ;; The slingshot shape preserves the contract that legacy
          ;; try+ callers above this layer rely on.
          (response/throw-anomaly! :anomalies.executor/unavailable
                                   (:anomaly/message a)
                                   (merge {:anomaly.executor/mode (get-in a [:anomaly/data :mode])
                                           :hint (get-in a [:anomaly/data :hint])}
                                          (select-keys (:anomaly/data a) [:runtime-selection]))))
        (when (= :local mode)
          (restore-local-workspace-checkpoint! executor repo-path resume-workspace))
        (when executor
          (if (= :governed mode)
            (governed/acquire! executor workflow-id mode env-config fns)
            (build-env-record executor workflow-id mode env-config fns))))
      (catch Exception e
        (boundary/preserve-critical! e)
        (when (= :governed mode) (throw e))
        (log/warn env-logger :workflow :workflow/env-acquisition-failed
                  {:message (messages/t :env/acquisition-failed {:error (ex-message e)})})
        nil))))

(comment ::workflow-environment-lifetime)
