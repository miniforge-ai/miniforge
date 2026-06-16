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

(ns ai.miniforge.cli.main.commands.pr-monitor
  "PR monitor loop — work-list resume and fresh-monitor paths.

   Stratified design (3 layers):
     Layer 0  constants + shell primitives
     Layer 1  monitor session (run-monitor!, resume-from-worklist!)
     Layer 2  entry point (pr-monitor-cmd)"
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and shell primitives

(def ^:private ms-per-second
  "Milliseconds in one second.
   Converts poll-interval-s (config/worklist unit) to poll-interval-ms
   (pr-lifecycle internal unit). Extracted per rule 006 — used in three sites."
  1000)

(def ^:private default-poll-interval-ms
  "Fallback used in run-monitor! when the monitor atom does not yet carry
   [:config :poll-interval-ms]. Mirrors the pr-lifecycle monitor default (60 s)."
  60000)

(defn- sh! [& args]
  (apply process/sh args))

(defn- remote-origin-url
  "Return the git remote origin URL for `repo-path`, or nil on failure."
  [repo-path]
  (let [r (sh! "git" "-C" repo-path "remote" "get-url" "origin")]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn- resolve-author
  "Resolve the GitHub author login from `author-opt` flag, gh CLI, or `default-author`."
  [author-opt default-author]
  (or author-opt
      (let [result (sh! "gh" "api" "user" "--jq" ".login")]
        (when (zero? (:exit result))
          (let [login (str/trim (:out result))]
            (when (seq login) login))))
      default-author))

(defn- parse-poll-interval
  "Parse poll-interval string in seconds, with bounds checking.
   Returns milliseconds, or nil when the value is out-of-range or unparseable."
  [interval-str {:keys [min-poll-interval-s max-poll-interval-s]}]
  (when interval-str
    (try
      (let [seconds (Long/parseLong (str interval-str))]
        (if (<= min-poll-interval-s seconds max-poll-interval-s)
          (* seconds ms-per-second)
          (do (display/print-error
               (messages/t :pr/monitor-interval-bounds
                           {:min min-poll-interval-s :max max-poll-interval-s :value seconds}))
              nil)))
      (catch NumberFormatException _
        (display/print-error (messages/t :pr/monitor-interval-invalid {:value interval-str}))
        nil))))

(defn- worklist-poll-ms
  "Derive poll-interval in milliseconds from the PR entries in a worklist.
   Takes the minimum :pr/poll-interval (seconds) across all entries.
   Returns nil when no entries carry that key — caller uses monitor default."
  [prs]
  (some->> (seq (keep :pr/poll-interval prs))
           (apply min)
           (* ms-per-second)))

;------------------------------------------------------------------------------ Layer 1
;; Monitor session — compose Layer 0 with pr-lifecycle calls

(defn- run-monitor!
  "Create a PR monitor from `mon-opts`, install a shutdown hook, run the loop.
   Single implementation shared by the fresh-monitor and resume paths."
  [mon-opts author]
  (let [monitor (pr-lifecycle/create-pr-monitor mon-opts)
        ;; Guard nil: create-pr-monitor may not populate [:config :poll-interval-ms]
        ;; before the atom is first deref'd. Fall back to default-poll-interval-ms.
        eff-ms  (or (get-in @monitor [:config :poll-interval-ms])
                    default-poll-interval-ms)
        path    (:worktree-path mon-opts)]
    (display/print-info (messages/t :pr/monitor-starting {:author author}))
    (display/print-info (messages/t :pr/monitor-polling {:seconds (/ eff-ms ms-per-second) :dir path}))
    (display/print-info (messages/t :pr/monitor-stop-hint))
    (let [shutdown (fn []
                     (display/print-info (messages/t :pr/monitor-stopping))
                     (try (pr-lifecycle/stop-pr-monitor-loop monitor) (catch Exception _)))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown))
      (let [evidence (pr-lifecycle/run-pr-monitor-loop monitor author)]
        (display/print-info (messages/t :pr/monitor-stopped {:evidence (pr-str evidence)}))))))

(defn- resume-from-worklist!
  "Load persisted worklist for `repo-path`, prune closed PRs, then run monitor.

   Return-type contract (see monitor_worklist.clj ns-doc):
     - load-worklist    → schema/success | schema/failure  → checked via schema/failed?
     - prune-closed-prs → WorklistEntry  | anomaly map    → checked via anomaly/anomaly?

   schema/success :worklist entry produces {:success? true :worklist <entry>}.
   :worklist is the data-key passed to schema/success; there is no separate
   value accessor in the schema component (see schema.interface/success).

   Exit semantics:
     - returns normally (exit 0 by default) when worklist is empty after pruning
     - shared/exit! 1 on unresolvable remote, missing worklist, prune failure, nil author"
  [repo-path cli-cfg]
  (let [origin-url (remote-origin-url repo-path)]
    (when-not origin-url
      (display/print-error (messages/t :pr/monitor-no-remote {:path repo-path}))
      (shared/exit! 1))
    (let [rkey        (pr-lifecycle/worklist-repo-key origin-url)
          wl-path     (pr-lifecycle/worklist-path (app-config/home-dir) rkey)
          load-result (pr-lifecycle/load-worklist wl-path)]
      (when (schema/failed? load-result)
        (display/print-error (messages/t :pr/monitor-no-worklist))
        (shared/exit! 1))
      (let [worklist       (:worklist load-result)
            original-count (count (:worklist/prs worklist))]
        (display/print-info (messages/t :pr/monitor-worklist-loaded {:count original-count}))
        (display/print-info (messages/t :pr/monitor-worklist-pruning))
        (let [pruned (pr-lifecycle/prune-closed-prs worklist)]
          (when (anomaly/anomaly? pruned)
            (display/print-error
             (messages/t :pr/monitor-worklist-prune-error
                         {:error (:anomaly/message pruned)}))
            (shared/exit! 1))
          (let [prs     (:worklist/prs pruned)
                removed (- original-count (count prs))]
            (when (pos? removed)
              (display/print-info
               (messages/t :pr/monitor-worklist-pruned
                           {:removed removed :remaining (count prs)})))
            (if (empty? prs)
              (display/print-info (messages/t :pr/monitor-worklist-empty))
              (let [author (resolve-author nil (:default-self-author cli-cfg))]
                (when-not author
                  (display/print-error (messages/t :pr/monitor-no-author))
                  (shared/exit! 1))
                (let [poll-ms  (worklist-poll-ms prs)
                      mon-opts (cond-> {:worktree-path repo-path :self-author author}
                                 poll-ms (assoc :poll-interval-ms poll-ms))]
                  (run-monitor! mon-opts author)))))))))

;------------------------------------------------------------------------------ Layer 2
;; Entry point

(defn pr-monitor-cmd
  "Start the PR monitor loop for autonomous comment resolution.

   With --author: creates a fresh monitor polling that author's open PRs.
   Without --author: resumes from a persisted work-list, using --repo (or
   cwd) as the repo path for work-list key derivation.

   Returns normally when the work-list is empty after pruning (exit 0).
   Calls shared/exit! 1 when no work-list exists and no --author is supplied."
  [opts]
  (let [{:keys [author poll-interval repo]} opts
        cli-cfg   (app-config/pr-monitor-config)
        cwd       (System/getProperty "user.dir")
        repo-path (or repo cwd)]
    (if author
      (let [resolved (resolve-author author (:default-self-author cli-cfg))
            poll-ms  (parse-poll-interval poll-interval cli-cfg)
            mon-opts (cond-> {:worktree-path repo-path :self-author resolved}
                       poll-ms (assoc :poll-interval-ms poll-ms))]
        (run-monitor! mon-opts resolved))
      (resume-from-worklist! repo-path cli-cfg))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Fresh monitor for a specific author
  (pr-monitor-cmd {:author "alice" :repo "/path/to/repo" :poll-interval "60"})

  ;; Resume from persisted work-list in the current directory
  (pr-monitor-cmd {})

  ;; Resume from an explicit repo path
  (pr-monitor-cmd {:repo "/path/to/other-repo"})

  :leave-this-here)
