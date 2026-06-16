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

(ns ai.miniforge.workflow.observe-phase
  "N2 Observe phase: autonomous PR monitoring after release.

   Wires the PR monitor loop into the workflow execution pipeline.
   After the Release phase creates a PR, the Observe phase starts
   the monitor loop and runs until the PR is merged, abandoned,
   or budget exhausted.

   This is the N2 §7 implementation. The Observe phase is the final
   phase of the standard SDLC workflow: plan → implement → verify →
   review → release → observe."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.schema.interface :as schema]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading + defaults

(def ObservePhaseDefaults
  [:map
   [:agent {:optional true} [:maybe keyword?]]
   [:gates [:vector keyword?]]
   [:budget [:map
             [:tokens nat-int?]
             [:iterations pos-int?]
             [:time-seconds pos-int?]]]])

(def ObservePhaseConfig
  [:map
   [:observe/phase-defaults ObservePhaseDefaults]])

(def ObserveMonitorConfig
  [:map
   [:worktree-path :string]
   [:self-author [:maybe :string]]
   [:generate-fn {:optional true} any?]
   [:event-bus {:optional true} any?]
   [:logger {:optional true} any?]
   [:poll-interval-ms nat-int?]
   [:max-fix-attempts-per-comment pos-int?]
   [:max-total-fix-attempts-per-pr pos-int?]
   [:abandon-after-hours pos-int?]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-observe-phase-config
  []
  (if-let [res (io/resource "config/workflow/observe-phase.edn")]
    (->> res slurp edn/read-string (validate! ObservePhaseConfig))
    (response/throw-anomaly! :anomalies/not-found
                            "Missing classpath resource: config/workflow/observe-phase.edn"
                            {:hint "Add components/workflow/resources to your classpath"})))

(defn- load-monitor-defaults
  []
  (pr-lifecycle/monitor-defaults))

(defn- present-overrides
  [overrides]
  (into {} (remove (comp nil? val)) overrides))

(def default-config
  "Default Observe phase configuration loaded from EDN."
  (:observe/phase-defaults (load-observe-phase-config)))

;; Register defaults on load
(phase/register-phase-defaults! :observe default-config)

;------------------------------------------------------------------------------ Layer 1
;; Pure worklist helpers

(def ^:private ms-per-second
  "Milliseconds per second. Converts poll-interval-ms to the integer-seconds
   value the worklist entry stores under :pr/poll-interval."
  1000)

(defn- pr-url->repo
  "Extract `owner/repo` from a GitHub-style PR URL, or nil."
  [pr-url]
  (when (string? pr-url)
    (let [parts (str/split pr-url #"/")]
      (when-let [pull-idx (some #(when (= "pull" (nth parts % nil)) %)
                                (range (count parts)))]
        (when (>= pull-idx 2)
          (str (nth parts (- pull-idx 2))
               "/"
               (nth parts (dec pull-idx))))))))

(defn- pr-info->worklist-entry
  "Map a pr-info map to a WorklistPrEntry. Returns nil when required
   fields (url, number, repo) cannot be resolved. Accepts both the
   `:pr/url`/`:pr/number` (DAG) and `:pr-url`/`:pr-number` (release)
   key shapes. poll-interval-ms and abandon-after-hours travel from the
   resolved monitor-config — no operational literal is introduced here.

   `now` is a java.util.Date stamped by the caller; never calls the
   clock internally so tests can pin the timestamp to a fixed value."
  [pr-info poll-interval-ms abandon-after-hours now]
  (let [url    (or (:pr/url pr-info) (:pr-url pr-info))
        number (some-> (or (:pr/number pr-info) (:pr-number pr-info) (:pr/id pr-info)) int)
        repo   (or (:pr/repo pr-info) (pr-url->repo url))]
    (when (and url number repo)
      (cond-> {:pr/url      url
               :pr/number   number
               :pr/repo     repo
               :pr/added-at now}
        poll-interval-ms
        (assoc :pr/poll-interval (int (/ poll-interval-ms ms-per-second)))
        abandon-after-hours
        (assoc :pr/abandon-after-hours (int abandon-after-hours))))))

;------------------------------------------------------------------------------ Layer 2
;; Interceptor implementation

(defn- resolve-monitor-config
  [ctx logger generate-fn event-bus]
  (let [worktree-path (or (get-in ctx [:execution/worktree-path])
                          (get-in ctx [:worktree-path]))
        shared-defaults (load-monitor-defaults)]
    (validate!
     ObserveMonitorConfig
     (merge shared-defaults
            {:worktree-path worktree-path
             :self-author (or (get-in ctx [:execution/self-author])
                              (get-in ctx [:config :github/self-author])
                              (:self-author shared-defaults)
                              ;; Default to the authenticated gh login so the
                              ;; monitor loop can find PRs this instance opened
                              ;; when no self-author is configured. Without this,
                              ;; poll-open-prs filters by a nil author and finds
                              ;; nothing.
                              (pr-lifecycle/current-github-login worktree-path))
             :generate-fn generate-fn
             :event-bus event-bus
             :logger logger}
            (present-overrides
             {:poll-interval-ms (get-in ctx [:config :pr-monitor/poll-interval-ms])
              :max-fix-attempts-per-comment
              (get-in ctx [:config :pr-monitor/max-fix-attempts-per-comment])
              :max-total-fix-attempts-per-pr
              (get-in ctx [:config :pr-monitor/max-total-fix-attempts-per-pr])
              :abandon-after-hours
              (get-in ctx [:config :pr-monitor/abandon-after-hours])})))))

(defn- resolve-pr-infos
  "Resolve the PR(s) to observe from the execution context. Returns a vector
   of pr-info maps, or nil when there is nothing to observe.

   DAG runs populate `[:execution/dag-pr-infos]`. For a single-PR run, the PR
   info is read via `response/release-pr-info` from the one canonical location
   (the release phase-result's `[:result :output :workflow/pr-info]`) — the
   same accessor `dag-orchestrator/extract-pr-info-from-result` uses. (An
   earlier shallow `[:release :pr-info]` read and a `[:metrics :release
   :pr-info]` fallback are both gone — observe used to see nil and skip, so a
   single-PR dogfood opened its PR and exited without ever monitoring it.)"
  [ctx]
  (let [dag-prs (get-in ctx [:execution/dag-pr-infos])]
    (cond
      (seq dag-prs) (vec dag-prs)
      :else (when-let [pr-info (response/release-pr-info
                                (get-in ctx [:execution/phase-results :release]))]
              [pr-info]))))

(defn- remote-origin-url
  "Shell `git config --get remote.origin.url` in worktree-path.
   Returns the trimmed URL string, or nil on blank/nil path or process failure."
  [worktree-path]
  (when-not (str/blank? worktree-path)
    (try+
     (let [proc @(process/process ["git" "config" "--get" "remote.origin.url"]
                                  {:dir worktree-path :out :string :err :string :throw false})]
       (when (zero? (:exit proc))
         (str/trim (:out proc))))
     (catch Object _ nil))))

(defn- try-persist-worklist!
  "Best-effort: persist the PR monitor worklist before detaching the
   monitor future. Derives the repo key from the git remote origin URL,
   resolves the worklist path under `config/miniforge-home`, and writes
   the entry. Logs a warning on failure but never throws — the in-process
   monitor continues regardless. All operational values (poll-interval,
   abandon-after-hours) come from the already-resolved monitor-config.

   Timestamps are obtained from `clock/now-ms` so tests can pin them
   via `with-redefs`."
  [monitor-config self-author pr-infos logger]
  (let [worktree-path  (:worktree-path monitor-config)
        poll-ms        (:poll-interval-ms monitor-config)
        abandon-hours  (:abandon-after-hours monitor-config)
        now            (java.util.Date. (clock/now-ms))
        remote-url     (remote-origin-url worktree-path)]
    (when remote-url
      (try+
       (let [rkey    (pr-lifecycle/worklist-repo-key remote-url)
             path    (pr-lifecycle/worklist-path (config/miniforge-home) rkey)
             entries (keep #(pr-info->worklist-entry % poll-ms abandon-hours now) pr-infos)
             entry   {:worklist/repo-key   rkey
                      :worklist/prs        (vec entries)
                      :worklist/updated-at now}
             result  (pr-lifecycle/persist-worklist! path entry)]
         (when (and logger (schema/failed? result))
           (log/warn logger :observe :observe/worklist-persist-failed
                     {:data {:path path}})))
       (catch Object e
         (when logger
           (log/warn logger :observe :observe/worklist-persist-failed
                     {:data {:error (str e)}})))))))

(defn enter-observe
  "Execute the Observe phase.

   Reads PR info from context (set by Release phase) and starts the PR
   monitor loop on a DETACHED future, then returns immediately with
   `:observe/status :monitoring`. The monitor polls each PR until a
   terminal condition (all merged, budget exhausted, closed externally,
   or no open PRs) — but that can take up to :abandon-after-hours, so it
   must not block the workflow thread. The future is exposed on ctx at
   `:execution/pr-monitor-future` for a long-lived deployment to await or
   cancel; a one-shot run completes the SDLC and exits.

   Before detaching the monitor future, the PR worklist is persisted to
   disk (best-effort). This enables resume across process restarts without
   holding state in memory.

   Skips (status :skipped) when there are no PRs to observe or no
   self-author can be resolved.

   pr-lifecycle is a direct dependency of the workflow component."
  [ctx]
  (let [pr-infos (resolve-pr-infos ctx)
        start-time (System/currentTimeMillis)]

    (if (empty? pr-infos)
      ;; No PRs to observe — skip phase
      (-> ctx
          (assoc-in [:phase :name] :observe)
          (assoc-in [:phase :status] :completed)
          (assoc-in [:phase :result]
                    (response/success {:observe/status :skipped
                                       :observe/reason "No PRs to observe"})))

      (let [logger (get-in ctx [:execution/logger])
            generate-fn (get-in ctx [:execution/generate-fn])
            event-bus (get-in ctx [:execution/event-bus])
            monitor-config (resolve-monitor-config ctx logger generate-fn event-bus)
            self-author (:self-author monitor-config)]
       (if (str/blank? self-author)
         ;; Without a self-author, poll-open-prs would shell `gh pr list
         ;; --author <nil>` and the loop would retry forever. Skip with an
         ;; explicit reason instead of spinning — surfaces as data, not a hang.
         (-> ctx
             (assoc-in [:phase :name] :observe)
             (assoc-in [:phase :started-at] start-time)
             (assoc-in [:phase :status] :completed)
             (assoc-in [:phase :result]
                       (response/success
                        {:observe/status :skipped
                         :observe/reason "No self-author resolved (gh unauthenticated?) — cannot poll PRs"})))
         (let [monitor       (pr-lifecycle/create-pr-monitor monitor-config)
               ;; Persist the worklist before detaching the future so a
               ;; process restart can resume monitoring without re-running
               ;; the full SDLC. Best-effort — failure is logged but the
               ;; in-process monitor continues regardless. try-persist-worklist!
               ;; enforces its own never-throws contract via try+.
               _             (try-persist-worklist! monitor-config self-author pr-infos logger)
               ;; Run the monitor OFF the workflow thread. The PR-monitor loop
               ;; polls for up to :abandon-after-hours (72h by default), so a
               ;; synchronous call stalls the workflow at :observe for days and
               ;; the SDLC never reaches :done — on a dogfood/CI run that reads
               ;; as a hang (the loop sits in a 60s Thread/sleep at 0% CPU).
               ;; Detach it: the workflow completes immediately and the monitor
               ;; runs out-of-band for as long as the host process lives. The
               ;; future is exposed on ctx so a long-lived deployment can
               ;; await/cancel it; a one-shot run exits and the daemon work
               ;; stops with it. (pr-lifecycle documents this same
               ;; `(future (run-pr-monitor-loop ...))` pattern.)
               monitor-future (future
                                (try
                                  (pr-lifecycle/run-pr-monitor-loop monitor self-author)
                                  (catch Throwable t
                                    (when logger
                                      (log/error logger :observe :observe/monitor-failed
                                                 {:message (ex-message t)}))
                                    {:observe/monitor-error (ex-message t)})))
               result-data {:observe/status :monitoring
                            :observe/prs-monitored (count pr-infos)
                            :observe/monitor-detached? true}]
           (when logger
             (log/info logger :observe :observe/monitor-detached
                       {:data {:prs-monitored (count pr-infos)
                               :self-author self-author}}))
           (-> ctx
               (assoc-in [:phase :name] :observe)
               (assoc-in [:phase :started-at] start-time)
               (assoc-in [:phase :status] :completed)
               (assoc-in [:phase :result] (response/success result-data))
               (assoc :execution/pr-monitor-future monitor-future))))))))

(defn leave-observe
  "Post-processing for Observe phase. Records evidence and duration metrics."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at] (System/currentTimeMillis))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:metrics :observe :duration-ms] duration-ms)
        (update-in [:execution :phases-completed] (fnil conj []) :observe)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-observe
  "Handle Observe phase errors. Observe is single-shot (fails on first
   error) and historically never honored `:on-fail`. Pass
   `default-budget = 0` and `redirect? = false` to preserve that
   semantics."
  [ctx ex]
  (phase/handle-error ctx ex 0 false))

;------------------------------------------------------------------------------ Layer 3
;; Registry method

(defmethod phase/get-phase-interceptor-method :observe
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::observe
     :config merged
     :enter (fn [ctx]
              (enter-observe (assoc ctx :phase-config merged)))
     :leave leave-observe
     :error error-observe}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get interceptor
  (phase/get-phase-interceptor-method {:phase :observe})

  ;; Check defaults
  (phase/phase-defaults :observe)

  ;; Typical execution context keys the Observe phase reads:
  ;; :execution/dag-pr-infos — vector of PR info maps from Release phase
  ;; :execution/logger — structured logger
  ;; :execution/worktree-path — git repo path
  ;; :execution/generate-fn — LLM generation function
  ;; :execution/event-bus — PR lifecycle event bus
  ;; :execution/self-author — miniforge's GitHub login
  ;; :config :pr-monitor/* — monitor config overrides

  ;; Worklist helper examples
  (pr-url->repo "https://github.com/org/repo/pull/42")
  ;; => "org/repo"

  (pr-info->worklist-entry {:pr-url "https://github.com/org/repo/pull/42"
                            :pr-number 42}
                           60000
                           72
                           (java.util.Date.))
  ;; => {:pr/url "..." :pr/number 42 :pr/repo "org/repo"
  ;;     :pr/added-at #inst "..." :pr/poll-interval 60
  ;;     :pr/abandon-after-hours 72}

  :leave-this-here)
