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
(ns ai.miniforge.phase-software-factory.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs using the release-executor component.
   All git/gh operations route through the DAG executor so governed-mode capsules
   never shell out to the host. The GitHub token is resolved and injected into the
   capsule as GH_TOKEN for gh CLI authentication.
   Agent: :releaser
   Default gates: [:release-ready]"
  (:require [clojure.java.io :as io]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workspace.interface :as workspace]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.interface.gate :as phase-gate]
            [ai.miniforge.phase.interface.task-base :as task-base]
            [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]
            [ai.miniforge.phase-software-factory.gap-wiring :as gap-wiring]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [ai.miniforge.phase-software-factory.release-publish :as release-publish]
            [ai.miniforge.phase-software-factory.release-unpushed :as release-unpushed]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} attach-consultation
  "Record `codex-outcome`'s summary onto `result`, on both success and
   failure -- :output starts nil on response/failure, so a failed
   release that consulted must not be ledgered as one that never did.
   §7.7.2: the result carries the releaser session's recorded peg
   answers and the lost-vs-unanswered answer-log marker (threaded up
   from the release executor) -- hand both to the summary."
  [result codex-outcome]
  (cond-> result
    (map? result)
    (assoc-in [:output :codex/consultation]
              (codex-pin/consultation-summary codex-outcome nil
                                              (:codex-answers result)
                                              (:codex-answer-log result)))))

;; Defaults
(def ^{:stratum 0} default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :release))

;; Interceptor implementation
(defn- ^{:stratum 0} log-already-implemented!
  "Log when implement phase reports work already done."
  [ctx impl-status]
  (when (= :already-implemented impl-status)
    (let [logger (or (get-in ctx [:execution/logger])
                     (log/create-logger {:min-level :info :output :human}))]
      (log/info logger :release :release/skipped-already-implemented
                {:data {:summary (get-in ctx [:execution/phase-results :implement :result :summary])}}))))

;; assert-implement-artifact! used to throw when the implement phase
;; reported anything other than :status :success. That gate disagreed with
;; verify and review, which read the environment directly and run regardless
;; of what the implement result map said. The disagreement surfaced under
;; the curator's :curator/no-files-written path: the agent narrates code in
;; chat, curator marks implement :failed, but persist still captures whatever
;; landed on disk and verify+review work fine against it. Release would
;; then explode with `:release/no-implement-artifact` even though there was
;; real work to release. Removed — the env-based `:release/zero-files`
;; check below is the authoritative gate, matching what verify and review
;; already do.
(defn- ^{:stratum 0} ctx-worktree-path
  "Resolve the working directory from phase context."
  [ctx]
  (workspace/resolve-execution-workdir ctx "release"))

(defn- ^{:stratum 0} command-failure
  "Failure detail for one scan command, or nil when it exited 0. A capsule
   executor that could not run the command returns `{:ok? false :error ...}`
   with no exit code; a missing exit code counts as failure, as in verify."
  [command exit stderr executor-error]
  (when-not (= 0 exit)
    {:detail (or (not-empty (str/trim (str stderr)))
                 (:message executor-error)
                 (str command " exited " exit))
     :data   (cond-> {:command command :exit exit :stderr stderr}
               executor-error (assoc :executor/error executor-error))}))

(defn- ^{:stratum 0} porcelain-z-entries
  "Parse `git status --porcelain -z` output into [status path] pairs.
   With -z each entry is NUL-terminated and its path is never quoted or
   escaped (without -z git wraps a path with spaces or non-ASCII bytes in
   C-style quotes). A rename or copy entry is followed by its source
   path, which is skipped."
  [out]
  (loop [records (str/split (or out "") #"\u0000")
         entries []]
    (if-let [record (first records)]
      (if (< (count record) 4)
        (recur (next records) entries)
        (let [status (subs record 0 2)]
          (recur (if (some #{\R \C} status) (nnext records) (next records))
                 (conj entries [status (subs record 3)]))))
      entries)))

(defn- ^{:stratum 0} git-status-failed
  "Anomaly for a working-tree scan that could not run (git exited non-zero,
   the capsule executor failed, or a listed file could not be read).
   Distinct from the terminal `:release/zero-files`: a failed scan says
   nothing about whether there is work to release. `error-release` retries
   it in place like any non-terminal release error (what a held index.lock
   needs). `failure` is `{:detail string :data map}`."
  [root-path {:keys [detail data]}]
  (anomaly/sub-anomaly :unavailable
                       :anomalies.phase/enter-failed
                       (messages/t :release/git-status-failed
                                   {:worktree-path root-path :detail detail})
                       (merge {:type          :release/git-status-failed
                               :phase         :release
                               :worktree-path root-path}
                              data)))

(defn- ^{:stratum 0} rehydrate-committed-files
  "Read content for the `:code/file-paths` recorded by the implement
   phase. Used when implement's boundary commit has cleaned the
   worktree — `git-dirty-files` then returns empty even though the
   files exist as committed content on the task branch.

   Modeled after review.clj/rehydrate-from-paths but with two
   differences:
   - Returns a `:code/files` vector directly (review wraps it back
     into the outer artifact); release wants the vector to feed
     into `code-artifacts` below.
   - Applies the same `agent/substantive-file?` filter that
     `git-dirty-files` uses, so a record consisting only of
     `.miniforge/session-id` does not count as releasable work
     (matches the iter-23 empty-diff PR guard).

   Host-mode only: `agent/rehydrate-files` reads the host
   filesystem. In governed (capsule) mode the worktree lives
   inside a Docker/K8s container and the host cannot reach it
   directly. A capsule-aware variant is a follow-up; today the
   capsule path falls through to `git-dirty-files-capsule`, which
   matches the pre-fix behavior in that mode."
  [implement-artifact worktree-path]
  (when-let [paths (and implement-artifact
                        worktree-path
                        (seq (:code/file-paths implement-artifact)))]
    (let [files (try
                  (agent/rehydrate-files worktree-path paths
                                         (:code/file-actions implement-artifact))
                  (catch Exception _ nil))]
      (->> (or files [])
           (filter agent/substantive-file?)
           vec))))

(defn- ^{:stratum 0} unresolved-known-issues
  "The review's unresolved non-blocking issues to record on the PR. Prefer the
   structured `:review/issues` (severity :warning/:nit — these carry file/line);
   fall back to the legacy `:review/warnings` description strings when no
   structured issues are present (gate-produced warnings arrive as strings)."
  [review-output]
  (let [structured (filterv (comp #{:warning :nit} :severity)
                            (get review-output :review/issues []))]
    (if (seq structured)
      structured
      (vec (get review-output :review/warnings [])))))

(defn- ^{:stratum 0} zero-files-data
  [impl-result worktree-path]
  {:type           :release/zero-files
   :phase          :release
   :environment-id (:environment-id impl-result)
   :worktree-path  worktree-path})

(defn- ^{:stratum 0} release-description
  "The release's task description: the run input's, else its title, else
   the default. The release branch name is derived from it."
  [ctx]
  (let [input (get-in ctx [:execution/input])]
    (or (:description input)
        (:title input)
        (messages/t :default/task-description))))

(defn- ^{:stratum 0} compute-verdict
  "Pick the verdict that should flow on the phase result.

   `:release/zero-files` pre-empts `:repair-requested` — when the curator
   reports an empty diff, retrying the implementer is wasted work: the
   next pass would just produce another empty diff. Make this FSM-visible
   so the verdict-driven guarded array terminates the loop."
  [{:keys [phase-failed? on-fail-configured? zero-files?]}]
  (cond
    zero-files?
    :release/zero-files

    (and phase-failed? on-fail-configured?)
    :repair-requested

    phase-failed?
    :exhausted

    :else
    :approved))

(defn- ^{:stratum 0} readiness-check
  "One phase's readiness evidence: verify completed, or review completed
   with an approving decision. A phase with no recorded result fails."
  [ctx phase-name]
  (let [phase-result (get-in ctx [:execution/phase-results phase-name])
        completed?   (and (some? phase-result) (phase/succeeded? phase-result))
        decision     (when (= :review phase-name)
                       (response/review-decision (response/phase-output phase-result)))]
    (cond-> {:phase   phase-name
             :passed? (and completed?
                           (or (not= :review phase-name)
                               (contains? #{:approved :conditionally-approved} decision)))}
      (nil? phase-result)     (assoc :reason :no-result)
      (= :review phase-name)  (assoc :decision decision))))

(defn- ^{:stratum 0} attach-error-verdict
  "Phase 3b: when error-release fails terminally, attach the verdict to
   the result so the FSM's guarded :phase/fail array reads it. The
   `:release/zero-files` case is terminal; everything else is either
   :repair-requested (on-fail set, FSM will redirect) or :exhausted
   (no on-fail target)."
  [ctx zero-files? on-fail-configured?]
  (let [verdict (cond
                  zero-files?            :release/zero-files
                  on-fail-configured?    :repair-requested
                  :else                  :exhausted)]
    (-> ctx
        (assoc-in [:phase :verdict] verdict)
        (assoc-in [:phase :result :output :phase/verdict] verdict))))

(defn ^{:stratum 0} build-executor-context
  "Build context for the release executor from phase context: the
   publish context (release-publish/build-publish-context) plus what the
   releaser agent needs.

   Also computes the phase-filtered policy-pack behavior addendum via
   `phase/load-and-filter-behaviors :release` and threads it as
   `:task/behavior-addendum` so `release-executor/invoke-releaser` can
   forward it into the agent input. Mirrors the wiring review (#945)
   and plan use; closes the gap where the releaser ran without the
   compiled standards pack."
  [ctx config logger]
  (let [on-chunk (phase/create-streaming-callback ctx :release)
        input (get-in ctx [:execution/input])
        ;; Codex consultation for the release situation, delivered through
        ;; the same addendum channel as the compiled standards pack — the
        ;; releaser has no existing-files channel (SPEC T1 s7.4). The
        ;; outcome rides on the exec-context so enter-release can attach
        ;; provenance without consulting twice.
        codex-outcome (codex-pin/landings-outcome :release logger)
        pack-addendum (phase/load-and-filter-behaviors
                        :release {:task {:task/intent (:intent input)}})
        behavior-addendum (let [t (:text codex-outcome)]
                            (cond
                              (and pack-addendum t) (str pack-addendum "\n\n" t)
                              t t
                              :else pack-addendum))]
    (cond-> (assoc (release-publish/build-publish-context ctx config logger)
                   :llm-backend (get-in ctx [:execution/llm-backend]))
      on-chunk (assoc :on-chunk on-chunk)
      behavior-addendum (assoc :task/behavior-addendum behavior-addendum)
      ;; :text already rode into the behavior addendum; the summary only
      ;; needs the status fields — don't ship the rendered prose twice.
      codex-outcome (assoc :codex/outcome (dissoc codex-outcome :text)))))

(defn- ^{:stratum 0} capsule-git-runner
  "A git runner for release-unpushed that runs each argv in the capsule at
   `root-path` through `execute-fn`. The command runs under `sh -c`, so an
   argument with characters beyond a plain path or ref is quoted."
  [execute-fn executor env-id root-path]
  (fn [argv]
    (let [arg #(if (re-matches #"[A-Za-z0-9._/:@=-]+" %) % (agent/shell-quote %))
          r   (execute-fn executor env-id (str/join " " (map arg argv)) {:workdir root-path})]
      {:exit (get-in r [:data :exit-code])
       :out  (get-in r [:data :stdout] "")
       :err  (get-in r [:data :stderr] (:message (:error r)))})))

(defn- ^{:stratum 0} host-git-runner
  "A git runner for release-unpushed that runs each argv on the host in
   `root-path`."
  [root-path]
  (fn [argv] (apply shell/sh (concat argv [:dir root-path]))))

(defn- ^{:stratum 0} scan-unpushed
  "release-unpushed/unpushed-files with `run`. An exception is the scan's
   failure; an interrupt (a cancel or timeout) is not a failed scan to
   retry: the flag is set again and it propagates."
  [run run-branches base-commits]
  (try+
    (release-unpushed/unpushed-files run run-branches base-commits)
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (throw e))
    (catch Exception e
      {:failure {:detail (or (ex-message e) (.getName (class e)))
                 :data   {:exception/class (.getName (class e))}}})))

(defn- ^{:stratum 0} task-base-commits
  "Where the task's work started: the commits among its recorded task base
   refs (a resumed run carries the first attempt's), else, on a first
   attempt, the environment's base commit. A resumed environment's base is
   the checkpoint it restored, which holds the run's own work, so it is
   not used."
  [ctx]
  (let [recorded (filterv #(and (string? %) (re-matches #"[0-9a-f]{7,40}" %))
                          (task-base/recorded-refs ctx))
        base-sha (get-in ctx [:execution/environment-metadata :base-sha])]
    (cond
      (seq recorded) recorded
      (and base-sha (not (get-in ctx [:execution/opts :resume-workspace]))) [base-sha]
      :else [])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} release-readiness
  "Whether the release may publish: each of verify and review that the
   workflow pipeline configures must have passed. A pipeline configuring
   neither has no evidence and is not ready. The :release-ready gate reads
   this from the phase output's :release/readiness."
  [ctx]
  (let [configured (set (map :phase (get-in ctx [:execution/workflow :workflow/pipeline])))
        checks     (mapv #(readiness-check ctx %) (filter configured [:verify :review]))]
    {:ready? (boolean (and (seq checks) (every? :passed? checks)))
     :checks (if (seq checks) checks [{:phase :verify :passed? false :reason :not-configured}])}))

(defn- ^{:stratum 1} git-dirty-files
  "Scan git working tree for new/modified/deleted files; return as :code/files entries.
   Used as fallback when the implement phase wrote files directly to disk
   (design docs, specs, UX assets, etc.) rather than returning them in a
   :code/files artifact.

   Filters out non-substantive paths (session markers, miniforge runtime
   artifacts) via `agent/substantive-file?` — a diff consisting only of
   these must not count as releasable work. Matches the same filter the
   curator applies upstream (iter-23 regression: empty-diff PRs).

   Reads `git status -z`, whose paths are never quoted: without -z git
   wraps a path with a space or non-ASCII bytes in C-style quotes, which
   names no file on disk, and the file was left out of the release.

   Returns a `:release/git-status-failed` anomaly when the scan cannot
   run, never `[]` (which release would report as zero files)."
  [root-path]
  (try
    (let [{:keys [exit out err]} (shell/sh "git" "status" "--porcelain" "-uall" "-z" :dir root-path)]
      (if-let [failure (command-failure "git status --porcelain -uall -z" exit err nil)]
        (git-status-failed root-path failure)
        (->> (porcelain-z-entries out)
             (keep (fn [[xy path]]
                     (let [file (io/file root-path path)]
                       (cond
                         (str/includes? xy "D") {:path path :content "" :action :delete}
                         (.isFile file)         {:path path
                                                 :content (slurp file)
                                                 :action (if (str/starts-with? (str/trim xy) "?")
                                                           :create
                                                           :modify)}))))
             (filter agent/substantive-file?)
             vec)))
    (catch Exception e
      (git-status-failed root-path {:detail (or (ex-message e) (.getName (class e)))
                                    :data   {:exception/class (.getName (class e))}}))))

(defn- ^{:stratum 1} git-dirty-files-capsule
  "Scan git working tree inside a task capsule via execute-fn (N11 §9.3).
   Used for K8s executors where the filesystem is not locally accessible.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires.

   Applies the same `agent/substantive-file?` filter as the local variant so
   session markers and miniforge runtime artifacts don't slip through the
   governed path (iter-23 empty-diff regression).

   Returns a `:release/git-status-failed` anomaly when `git status` or a
   file's `cat` fails in the capsule, rather than `[]` or empty content."
  [execute-fn executor env-id root-path]
  (try
    (let [run (fn [command]
                (let [result (execute-fn executor env-id command {:workdir root-path})]
                  {:out     (or (get-in result [:data :stdout]) "")
                   :failure (command-failure command
                                             (get-in result [:data :exit-code])
                                             (get-in result [:data :stderr])
                                             (:error result))}))
          read-entry (fn [[status path]]
                       (if (str/includes? status "D")
                         {:path path :content "" :action :delete}
                         ;; The command runs under `sh -c`: quote the path so
                         ;; spaces, `$`, `;` and the like reach cat as-is.
                         (let [{:keys [out failure]}
                               (run (str "cat " (agent/shell-quote (str root-path "/" path))))]
                           (if failure
                             (git-status-failed root-path failure)
                             {:path    path
                              :content out
                              :action  (if (= "??" status) :create :modify)}))))
          {:keys [out failure]} (run "git status --porcelain -uall -z")]
      (if failure
        (git-status-failed root-path failure)
        (reduce (fn [files entry]
                  (let [entry (read-entry entry)]
                    (if (anomaly/anomaly? entry)
                      (reduced entry)
                      (conj files entry))))
                []
                (filter (fn [[_status path]] (agent/substantive-file? {:path path}))
                        (porcelain-z-entries out)))))
    (catch Exception e
      (git-status-failed root-path {:detail (or (ex-message e) (.getName (class e)))
                                    :data   {:exception/class (.getName (class e))}}))))

(defn- ^{:stratum 1} unpushed-files
  "release-unpushed/unpushed-files in the worktree at `root-path`: on the
   host, or through `execute-fn` in the capsule when it is given. A scan
   git cannot answer is a :release/git-status-failed anomaly, never [].
   `run-branches` are the run's own branches and `base-commits` where its
   work started (see release-unpushed)."
  [root-path execute-fn executor env-id run-branches base-commits]
  (let [run (if execute-fn
              (capsule-git-runner execute-fn executor env-id root-path)
              (host-git-runner root-path))
        {:keys [files failure]} (scan-unpushed run run-branches base-commits)]
    (if failure (git-status-failed root-path failure) files)))

(defn- ^{:stratum 1} release-files-result
  "Return release file data, the failed scan's own anomaly when `dirty` is
   one, or a canonical anomaly when there is no substantive work to release."
  [committed dirty impl-result worktree-path]
  (cond
    (seq committed)          committed
    (anomaly/anomaly? dirty) dirty
    (seq dirty)              dirty
    :else
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.phase/enter-failed
                         (messages/t :release/zero-files)
                         (zero-files-data impl-result worktree-path))))

(defn ^{:stratum 1} leave-release
  "Post-processing for release phase.

   Publishes what enter-release prepared (push and PR) unless a gate
   failed, then records release metrics and PR info.

   Phase 3b: attaches a `:phase/verdict` to the phase result so the
   FSM's verdict-driven guarded `:phase/fail` array picks the right
   target. `:release/zero-files` is a TERMINAL verdict — the FSM
   bypasses `:on-fail` and lands on `:failed` because the curator's
   empty-diff signal means the implementer has nothing left to do."
  [ctx]
  ;; Gap-instrument miss recording (T2 s3): best-effort, opt-in, and it
  ;; must never change the outcome it measures. Consultation provenance
  ;; was attached at enter, so it is visible here.
  (gap-wiring/record-phase-misses! ctx :release)
  (let [ctx (release-publish/publish-when-allowed ctx)
        start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        release-data (when (phase/result-succeeded? result) (:output result))
        release-metrics (get release-data :release/metrics {})
        metrics (merge {:tokens 0 :duration-ms duration-ms} release-metrics)
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        zero-files? (= :release/zero-files
                       (get-in result [:error :data :type]))
        ;; A gate denial (projected onto :phase/status by the workflow's
        ;; gate validation) fails the release whatever the agent returned.
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        phase-status (if (or zero-files? gate-failed?)
                       :failed
                       (phase/determine-phase-status
                        agent-status iterations max-iterations))
        on-fail (get-in ctx [:phase-config :on-fail])
        ;; Verdict is only meaningful for terminal phase statuses
        ;; (:completed → :approved; :failed → one of the failure
        ;; verdicts). For :retrying the runner handles the in-phase
        ;; loop and the FSM never sees a transition, so skip the
        ;; verdict attachment — leaving the prior verdict in place if
        ;; the phase has been around the loop before, else nothing.
        verdict (when (contains? #{:completed :failed} phase-status)
                  (compute-verdict {:phase-failed?       (= :failed phase-status)
                                    :on-fail-configured? (some? on-fail)
                                    :zero-files?         zero-files?}))
        updated-ctx (cond-> ctx
                      true
                      (-> (assoc-in [:phase :ended-at] end-time)
                          (assoc-in [:phase :duration-ms] duration-ms)
                          (assoc-in [:phase :status] phase-status)
                          (assoc-in [:phase :metrics] metrics))
                      verdict
                      (-> (assoc-in [:phase :verdict] verdict)
                          (assoc-in [:phase :result :output :phase/verdict] verdict)))
        updated-ctx (cond-> updated-ctx
                      true
                      (-> (assoc-in [:metrics :release :duration-ms] duration-ms)
                          (assoc-in [:metrics :release :repair-cycles] (dec iterations))
                          (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                          (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd metrics 0.0))
                          (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))

                      (= :completed phase-status)
                      (update-in [:execution :phases-completed] (fnil conj []) :release))]
    (doto (cond-> updated-ctx
            (phase/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (get-in result [:error :message] (messages/t :release/phase-failed)))))
      (phase/emit-phase-completed! :release
        (merge {:outcome     (phase/outcome result (= :completed phase-status))
                :duration-ms duration-ms
                :tokens      (:tokens metrics 0)}
               (phase-terminal/derive-termination-reason result nil))))))

(defn ^{:stratum 1} error-release
  "Handle release phase errors.

   Phase 3b: failures no longer call `phase/fail-and-request-redirect`
   — the FSM's verdict-driven guarded :phase/fail array routes via
   `:on-fail` if configured, or to `:failed` otherwise. error-release
   stays responsible for the phase-level retry budget; once that's
   exhausted it just attaches the verdict + error and lets the FSM
   dispatch."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
        on-fail (get-in ctx [:phase-config :on-fail])
        error-map (phase/exception-error ex)
        zero-files? (= :release/zero-files (get (ex-data ex) :type))]
    (cond
      zero-files?
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict true (some? on-fail)))

      ;; Within phase-level retry budget — retry in place. The FSM
      ;; doesn't see this as a transition.
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Out of retries — attach verdict and let the FSM dispatch
      ;; (:repair-requested when on-fail set, :exhausted otherwise).
      :else
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict false (some? on-fail))))))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} release-files!
  "Boundary wrapper around the anomaly-returning `release-files-result`;
   retained for release phase control flow that treats `:release/zero-files`
   as a terminal exception."
  [committed dirty impl-result worktree-path]
  (let [result (release-files-result committed dirty impl-result worktree-path)]
    (if (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result)))
      result)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} build-workflow-state
  "Build workflow state from phase context for the release executor.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results.

   File-discovery resolution order:

     1. `:code/file-paths` recorded on the implement phase artifact
        (`lightweight-curated-artifact`). After implement's boundary
        commit lands on the task branch the worktree is clean at HEAD,
        so `git-dirty-files` returns empty even though the files exist
        as committed content. Reading the recorded paths off disk is
        the same pattern review.clj/rehydrate-from-paths uses.
     2. `git-dirty-files` (or its capsule-aware twin) for files the
        agent wrote to the worktree but the boundary did not commit
        (legacy / partial-write path).
     3. Throw :release/zero-files when both paths come back empty —
        the only legitimate \"nothing to release\" condition. When the
        git scan itself fails, throw its :release/git-status-failed
        instead, carrying git's error.

   `probe` is the scan enter-release already ran to decide whether to
   skip (nil when it ran none). It stands in for step 2's rescan, so a
   failed probe raises its own error instead of a clean rescan turning
   it into zero-files.

   Stage-3 dogfood (2026-05-07) tripped on the missing #1 path:
   plan/implement/verify/review all green, files committed on the
   task branch, but release threw zero-files because git-dirty-files
   saw a clean worktree. This commit closes that gap."
  [ctx probe]
  (let [impl-result   (get-in ctx [:execution/phase-results :implement :result])
        impl-artifact (get-in ctx [:execution/phase-results :implement :artifact])
        impl-status   (:status impl-result)]
    (log-already-implemented! ctx impl-status)
    (let [worktree-path  (ctx-worktree-path ctx)
          governed?      (= :governed (get ctx :execution/mode))
          execute-fn     (get ctx :execution/execute-fn)
          executor       (get ctx :execution/executor)
          env-id         (get ctx :execution/environment-id)
          committed      (rehydrate-committed-files impl-artifact worktree-path)
          dirty          (when (empty? committed)
                           (cond
                             (some? probe) probe
                             (and governed? execute-fn executor env-id)
                             (git-dirty-files-capsule execute-fn executor env-id worktree-path)
                             :else (git-dirty-files worktree-path)))
          files          (release-files! committed dirty impl-result worktree-path)
          code-artifacts [{:artifact/type    :code
                           :artifact/content {:code/id        (random-uuid)
                                              :code/language  "mixed"
                                              :code/files     files
                                              :code/summary   (get impl-result :summary
                                                                   (messages/t :release/changes-description))
                                              :environment-id (:environment-id impl-result)}}]
          ;; Carry the review phase's unresolved non-blocking warnings into the
          ;; release as a :review artifact, so an :accept-with-warnings PR opens
          ;; with those warnings recorded as known-issues (release-executor's
          ;; PR-body renderer reads :review-typed artifacts). Pass-through only:
          ;; presentation lives in the renderer. Clean reviews carry no warnings
          ;; → no :review artifact → no change to the happy-path PR body.
          review-output  (get-in ctx [:execution/phase-results :review :result :output])
          known-issues   (unresolved-known-issues review-output)
          review-artifacts (when (seq known-issues)
                             [{:artifact/type    :review
                               :artifact/content {:review/decision (:review/decision review-output)
                                                  :review/summary  (:review/summary review-output)
                                                  :review/warnings known-issues}}])]
      {:workflow/id       (or (get-in ctx [:execution/id]) (random-uuid))
       :workflow/phase    :release
       :workflow/spec     {:spec/description (release-description ctx)}
       :workflow/artifacts (into code-artifacts review-artifacts)})))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} enter-release
  "Prepare the release.

   Uses the release executor to:
   - Generate release metadata (branch name, commit message, PR title/body)
   - Create git branch
   - Write files and stage them
   - Commit changes

   and records release readiness. Pushing and creating the PR happen in
   leave-release, after the gates."
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        impl-status (get-in ctx [:execution/phase-results :implement :result :status])

        logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :debug :output :human}))
        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :release)
        ;; Skip probe: scan only when an empty worktree means "nothing to release".
        ;; A clean worktree is checked for unpushed commits too: committed
        ;; work no push has published is released, not skipped as done.
        probe (when (contains? #{:already-implemented :already-satisfied nil} impl-status)
                (let [wt         (ctx-worktree-path ctx)
                      execute-fn (get ctx :execution/execute-fn)
                      executor   (get ctx :execution/executor)
                      env-id     (get ctx :execution/environment-id)
                      governed?  (and (= :governed (get ctx :execution/mode)) execute-fn executor env-id)
                      dirty      (if governed?
                                   (git-dirty-files-capsule execute-fn executor env-id wt)
                                   (git-dirty-files wt))]
                  (if (and (vector? dirty) (empty? dirty))
                    (let [{:keys [origin task]} (release-publish/provenance ctx)]
                      ;; The run's own branches are not publication: its
                      ;; release branch, and the checkpoint branches this
                      ;; attempt persists to and a resume started from.
                      (unpushed-files wt (when governed? execute-fn) executor env-id
                                      [(release-executor/release-branch-name
                                        origin task (release-description ctx))
                                       (get ctx :execution/task-branch)
                                       (get-in ctx [:execution/opts :resume-workspace :branch])]
                                      (task-base-commits ctx)))
                    dirty)))]
    ;; Short-circuit: skip release when there is nothing to release.
    ;; This covers:
    ;; - implement returned :already-implemented (no new code)
    ;; - plan returned :already-satisfied (DAG had 0 tasks, no implement ran)
    ;; In both cases, only skip if there are also no git changes from other phases,
    ;; and no commits ahead of origin's default branch.
    ;; A failed scan returns an anomaly, which does not skip; build-workflow-state
    ;; reuses the probe rather than rescanning, so the anomaly is what it raises.
    (if (and (vector? probe) (empty? probe))
      (-> (phase/enter-context ctx :release nil gates budget start-time
                                      (phase/skipped :already-implemented))
          (assoc-in [:phase :status] :completed))
      (let [;; Build workflow state and context
            workflow-state (build-workflow-state ctx probe)
        _ (log/debug logger :release :release/workflow-state-built
                     {:data {:artifact-count (count (:workflow/artifacts workflow-state))
                             :file-count (count (get-in (first (:workflow/artifacts workflow-state))
                                                        [:artifact/content :code/files]))}})
        exec-context (build-executor-context ctx config logger)
        releaser-agent (get config :releaser-agent)
        ;; Execute the release phase
        ;; Emit agent-started telemetry event for release executor
        _ (phase/emit-agent-started! ctx :release :releaser)
        _ (log/info logger :release :release/executor-invoked
                    {:data {:worktree-path (:worktree-path exec-context)
                            :executor (some-> (:executor exec-context) type str)
                            :environment-id (:environment-id exec-context)
                            :create-pr? (:create-pr? exec-context)
                            :releaser-agent? (some? releaser-agent)
                            :artifact-count (count (:workflow/artifacts workflow-state))}})

        ;; Prepare only (branch and commit). The push and the PR wait for
        ;; leave-release, which runs after the gates.
        result (try
                 (let [exec-result (release-executor/prepare-release
                                    workflow-state
                                    exec-context
                                    {:releaser releaser-agent})
                       r (if (:success? exec-result)
                     (let [release-artifact (first (:artifacts exec-result))]
                       (response/success
                        {:release/id (get release-artifact :artifact/id (random-uuid))
                         :release/status :prepared
                         :release/artifacts (:artifacts exec-result)
                         :release/prepared (:prepared exec-result)
                         ;; Read by the :release-ready gate.
                         :release/readiness (release-readiness ctx)
                         :release/metrics (:metrics exec-result)}))
                     ;; Execution failed — surface the executor's :errors
                     ;; so the next dogfood run can diagnose root cause from
                     ;; the event log instead of staring at a generic
                     ;; :anomalies.phase/agent-failed.
                     (do
                       (log/error logger :release :release/executor-failed
                                  {:data {:errors (:errors exec-result)
                                          :metrics (:metrics exec-result)}})
                       (response/failure
                        (messages/t :release/phase-failed)
                        {:data {:errors (:errors exec-result)
                                :metrics (:metrics exec-result)}})))]
                   ;; §7.7.2: the releaser session's recorded peg answers
                   ;; (and the lost-vs-unanswered answer-log marker) ride
                   ;; the executor result — thread them onto the phase
                   ;; result so attach-consultation can hand them to the
                   ;; consultation summary. Success and failure alike.
                   (cond-> r
                     (some? (:codex-answers exec-result))
                     (assoc :codex-answers (:codex-answers exec-result))
                     (some? (:codex-answer-log exec-result))
                     (assoc :codex-answer-log (:codex-answer-log exec-result))))
                 (catch Exception e
                   ;; The 2026-05-03 dogfood lost the actual exception here —
                   ;; the bare (response/failure e) wraps it but no log line
                   ;; ever fired, leaving the phase-completed event with
                   ;; :phase/duration-ms 0 and no actionable detail.
                   (log/error logger :release :release/executor-threw
                              {:message (ex-message e)
                               :data (cond-> {:exception/class (.getName (class e))}
                                       (ex-data e) (assoc :exception/data (ex-data e)))})
                   (response/failure e)))

        ;; Emit agent-completed telemetry event for release executor
        _ (phase/emit-agent-completed! ctx :release :releaser result)
        ;; SPEC s7.4.3: consultation provenance onto the durable result —
        ;; the gap instrument reads it at leave (same marker implement and
        ;; plan carry). Release has no recorded context-reads channel.
        result (attach-consultation result (:codex/outcome exec-context))]

    (phase/enter-context ctx :release :releaser gates budget start-time result)))))

;------------------------------------------------------------------------------ Layer 5

;; Registry methods
(defmethod ^{:stratum 5} phase/get-phase-interceptor-method :release
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

;; Register defaults on load
(phase/register-phase-defaults! :release default-config)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :release})
  (phase/phase-defaults :release)
  (phase/list-phases)
  :leave-this-here)
