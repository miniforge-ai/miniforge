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

(ns ai.miniforge.cli.main.commands.pr
  "PR operations using GitHub CLI."
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.main.commands.pr-review :as pr-review]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Shell helpers

(defn- sh! [& args]
  (apply process/sh args))

(defn- checkout-pr! [pr-number]
  (let [r (sh! "gh" "pr" "checkout" (str pr-number))]
    (when (zero? (:exit r))
      (str/trim (:out (sh! "git" "branch" "--show-current"))))))

(defn- push! []
  (zero? (:exit (sh! "git" "push"))))

(defn- remote-origin-url
  "Return the git remote origin URL for `repo-path`, or nil on failure."
  [repo-path]
  (let [r (sh! "git" "-C" repo-path "remote" "get-url" "origin")]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

;------------------------------------------------------------------------------ Layer 1
;; PR commands

(defn pr-list-cmd
  "List PRs using GitHub CLI."
  [opts load-config-fn]
  (let [{:keys [repo config]} opts
        cfg (load-config-fn config)
        repos (if repo [repo] (get-in cfg [:fleet :repos] []))]

    (if (empty? repos)
      (do
        (display/print-error (messages/t :pr/no-repos))
        (println (messages/t :pr/no-repos-hint {:command (app-config/command-string "fleet add")})))
      (doseq [r repos]
        (println)
        (println (display/style (messages/t :pr/header {:repo r}) :foreground :cyan :bold true))
        (let [result (process/sh "gh" "pr" "list" "--repo" r "--json" "number,title,state,author,createdAt" "--limit" "10")]
          (if (zero? (:exit result))
            (try
              (let [prs (json/parse-string (:out result) true)]
                (if (empty? prs)
                  (println (messages/t :pr/no-open))
                  (doseq [{:keys [number title state author]} prs]
                    (let [status-style (case state
                                         "OPEN" :green
                                         "MERGED" :magenta
                                         "CLOSED" :red
                                         :white)
                          state-badge (display/style
                                       (messages/t :pr/list-state-badge {:state state})
                                       :foreground status-style)]
                      (println (messages/t :pr/list-row
                                           {:number      number
                                            :state-badge state-badge
                                            :title       title
                                            :author      (:login author
                                                                 (messages/t :pr/list-author-unknown))}))))))
              (catch Exception _
                (let [result2 (process/sh "gh" "pr" "list" "--repo" r "--limit" "10")]
                  (if (zero? (:exit result2))
                    (println (:out result2))
                    (display/print-error (messages/t :pr/list-failed {:error (:err result2)}))))))
            (display/print-error (messages/t :pr/query-failed {:error (:err result)}))))))))

(defn- gh-pr-base-ref
  "Resolve the base-branch ref for `pr-number` via the GitHub CLI.
   Returns a remote-prefixed ref like \"origin/main\", or nil on failure."
  [pr-number]
  (let [r (sh! "gh" "pr" "view" (str pr-number)
               "--json" "baseRefName" "--jq" ".baseRefName")]
    (when (zero? (:exit r))
      (let [base-name (str/trim (:out r))]
        (when (seq base-name)
          (str "origin/" base-name))))))

(defn pr-review-cmd
  "N13 §2.2 Standards Reviewer entry point.

   Checks out the given PR URL, derives the base ref, and runs the
   compliance-scanner in PR-scoped read-only mode. Prints rendered
   review comments per N13 §2.3 to stdout.

   With `--post`, additionally batch-posts the rendered comments as a
   single PR review (event=COMMENT) via `pr-lifecycle/post-review!`.
   The PR head SHA needed for the create-review API is derived from
   the worktree HEAD after `gh pr checkout`. Does NOT apply fixes —
   that remains the Comment Response Agent's job.

   Pass --repo <path> + --base <ref> to operate on an existing checkout
   without using `gh` to fetch metadata; `--url <pr-url>` is the default
   user-facing flow."
  [opts]
  (let [{:keys [url repo base]} opts]
    (cond
      ;; --repo + --base path: operate on existing checkout
      (and repo base)
      (pr-review/run-pr-review-by-path-cmd opts)

      ;; URL path: parse, checkout, derive base, delegate
      url
      (let [{:keys [number]} (pr-lifecycle/parse-pr-url url)]
        (cond
          (not number)
          (display/print-error (messages/t :pr/respond-bad-url))

          :else
          (do
            (display/print-info (messages/t :pr/reviewing {:url url}))
            (let [base-ref (gh-pr-base-ref number)
                  branch   (checkout-pr! number)]
              (cond
                (not branch)
                (display/print-error (messages/t :pr/respond-checkout-failed))

                (not base-ref)
                (display/print-error
                 (messages/t :pr/review-base-ref-failed))

                :else
                (let [cwd (System/getProperty "user.dir")]
                  (display/print-info
                   (messages/t :pr/respond-on-branch {:branch branch}))
                  (pr-review/run-pr-review!
                   cwd
                   (cond-> {:base-ref base-ref}
                     (:standards opts) (assoc :standards (:standards opts))
                     (:pack opts)      (assoc :pack (:pack opts))
                     (:rules opts)     (assoc :rules (:rules opts))
                     (:out opts)       (assoc :out (keyword (:out opts)))
                     (:post opts)      (assoc :post? true :pr-number number)))))))))

      :else
      (display/print-error
       (messages/t :pr/review-usage
                   {:command (app-config/command-string "pr review <pr-url>")})))))

(defn pr-respond-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/respond-usage {:command (app-config/command-string "pr respond <pr-url>")}))
      (let [{:keys [number]} (pr-lifecycle/parse-pr-url url)]
        (when-not number
          (display/print-error (messages/t :pr/respond-bad-url))
          (System/exit 1))
        (display/print-info (messages/t :pr/respond-checkout {:number number}))
        (let [branch (checkout-pr! number)]
          (when-not branch
            (display/print-error (messages/t :pr/respond-checkout-failed))
            (System/exit 1))
          (display/print-info (messages/t :pr/respond-on-branch {:branch branch}))
          (let [cwd (System/getProperty "user.dir")
                result (pr-lifecycle/respond-to-comments!
                        url cwd
                        (fn [spec run-opts]
                          (workflow-runner/run-workflow-from-spec! spec (merge {:quiet true} run-opts)))
                        push!
                        opts)]
            (display/print-info
             (messages/t :pr/respond-done
                         {:comments (:comments-found result)
                          :files    (:files-processed result)
                          :fixed    (count (filter :succeeded? (:fixes result)))
                          :pushed   (if (:pushed? result)
                                      (messages/t :pr/respond-pushed)
                                      "")}))))))))

(defn pr-merge-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/merge-usage {:command (app-config/command-string "pr merge <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/merging {:url url}))
        (println (messages/t :pr/merge-todo))))))

;------------------------------------------------------------------------------ Layer 1
;; PR Monitor helpers

(defn- resolve-author
  "Resolve the GitHub author login from --author flag, gh CLI, or config default."
  [author-opt default-author]
  (or author-opt
      (let [result (sh! "gh" "api" "user" "--jq" ".login")]
        (when (zero? (:exit result))
          (let [login (str/trim (:out result))]
            (when (seq login) login))))
      default-author))

(defn- parse-poll-interval
  "Parse poll interval in seconds, with bounds checking. Returns milliseconds."
  [interval-str {:keys [min-poll-interval-s max-poll-interval-s]}]
  (when interval-str
    (try
      (let [seconds (Long/parseLong (str interval-str))]
        (if (<= min-poll-interval-s seconds max-poll-interval-s)
          (* seconds 1000)
          (do (display/print-error
               (messages/t :pr/monitor-interval-bounds
                           {:min min-poll-interval-s :max max-poll-interval-s :value seconds}))
              nil)))
      (catch NumberFormatException _
        (display/print-error (messages/t :pr/monitor-interval-invalid {:value interval-str}))
        nil))))

(defn- worklist-poll-ms
  "Derive a poll-interval in milliseconds from the PR entries in a worklist.
   Takes the minimum :pr/poll-interval (seconds) across all entries.
   Returns nil when no entries carry that key — caller uses monitor default."
  [prs]
  (some->> (seq (keep :pr/poll-interval prs))
           (apply min)
           (* 1000)))

(defn- run-monitor!
  "Create a PR monitor from `mon-opts`, install a shutdown hook, and run the loop.

   Shared by both the fresh-monitor and resume-from-worklist paths.
   Prints status lines before starting and after stopping."
  [mon-opts author]
  (let [monitor (pr-lifecycle/create-pr-monitor mon-opts)
        eff-ms  (get-in @monitor [:config :poll-interval-ms])
        path    (:worktree-path mon-opts)]
    (display/print-info (messages/t :pr/monitor-starting {:author author}))
    (display/print-info (messages/t :pr/monitor-polling {:seconds (/ eff-ms 1000) :dir path}))
    (display/print-info (messages/t :pr/monitor-stop-hint))
    (let [shutdown (fn []
                     (display/print-info (messages/t :pr/monitor-stopping))
                     (try (pr-lifecycle/stop-pr-monitor-loop monitor) (catch Exception _)))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown))
      (let [evidence (pr-lifecycle/run-pr-monitor-loop monitor author)]
        (display/print-info (messages/t :pr/monitor-stopped {:evidence (pr-str evidence)}))))))

;------------------------------------------------------------------------------ Layer 2
;; PR Monitor — worklist resume path

(defn- resume-from-worklist!
  "Load persisted worklist for `repo-path`, prune closed PRs, run monitor.

   Exit codes:
   - exits 0 when the worklist exists but is empty after pruning
   - exits 1 when the remote URL is unresolvable, no worklist exists,
     or the pruning gh call fails"
  [repo-path cli-cfg]
  (let [origin-url (remote-origin-url repo-path)]
    (when-not origin-url
      (display/print-error (messages/t :pr/monitor-no-remote {:path repo-path}))
      (System/exit 1))
    (let [rkey        (pr-lifecycle/worklist-repo-key origin-url)
          wl-path     (pr-lifecycle/worklist-path (app-config/home-dir) rkey)
          load-result (pr-lifecycle/load-worklist wl-path)]
      (when (schema/failed? load-result)
        (display/print-error (messages/t :pr/monitor-no-worklist))
        (System/exit 1))
      (let [worklist       (:worklist load-result)
            original-count (count (:worklist/prs worklist))]
        (display/print-info (messages/t :pr/monitor-worklist-loaded {:count original-count}))
        (display/print-info (messages/t :pr/monitor-worklist-pruning))
        (let [pruned (pr-lifecycle/prune-closed-prs worklist)]
          (when (anomaly/anomaly? pruned)
            (display/print-error
             (messages/t :pr/monitor-worklist-prune-error
                         {:error (:anomaly/message pruned)}))
            (System/exit 1))
          (let [prs     (:worklist/prs pruned)
                removed (- original-count (count prs))]
            (when (pos? removed)
              (display/print-info
               (messages/t :pr/monitor-worklist-pruned
                           {:removed removed :remaining (count prs)})))
            (if (empty? prs)
              (display/print-info (messages/t :pr/monitor-worklist-empty))
              (let [author   (resolve-author nil (:default-self-author cli-cfg))
                    poll-ms  (worklist-poll-ms prs)
                    mon-opts (cond-> {:worktree-path repo-path :self-author author}
                               poll-ms (assoc :poll-interval-ms poll-ms))]
                (run-monitor! mon-opts author)))))))))

;------------------------------------------------------------------------------ Layer 2
;; PR Monitor (continuous loop)

(defn pr-monitor-cmd
  "Start the PR monitor loop for autonomous comment resolution.

   With --author: creates a fresh monitor polling that author's open PRs.
   Without --author: resumes from a persisted work-list. Uses --repo (or
   cwd) as the repo path for work-list key derivation.

   Exits 0 when the work-list exists but is empty after pruning closed PRs.
   Exits 1 when no work-list exists and no --author was supplied.

   Polls open PRs, classifies new comments, and routes them to handlers
   (fix change-requests, answer questions, skip noise). Runs continuously
   until stopped with Ctrl+C, budget exhausted, or no open PRs remain."
  [opts]
  (let [{:keys [author poll-interval repo]} opts
        cli-cfg   (app-config/pr-monitor-config)
        cwd       (System/getProperty "user.dir")
        repo-path (or repo cwd)]
    (if author
      ;; Fresh monitor path — identical to prior behavior
      (let [resolved (resolve-author author (:default-self-author cli-cfg))
            poll-ms  (parse-poll-interval poll-interval cli-cfg)
            mon-opts (cond-> {:worktree-path repo-path :self-author resolved}
                       poll-ms (assoc :poll-interval-ms poll-ms))]
        (run-monitor! mon-opts resolved))
      ;; Worklist resume path
      (resume-from-worklist! repo-path cli-cfg))))
