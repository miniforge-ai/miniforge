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
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

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
                                         :white)]
                      (println (str "  #" number " "
                                    (display/style (str "[" state "]") :foreground status-style)
                                    " " title
                                    " (" (:login author "unknown") ")"))))))
              (catch Exception _
                (let [result2 (process/sh "gh" "pr" "list" "--repo" r "--limit" "10")]
                  (if (zero? (:exit result2))
                    (println (:out result2))
                    (display/print-error (messages/t :pr/list-failed {:error (:err result2)}))))))
            (display/print-error (messages/t :pr/query-failed {:error (:err result)}))))))))

(defn pr-review-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/review-usage {:command (app-config/command-string "pr review <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/reviewing {:url url}))
        (println (messages/t :pr/review-todo))))))

(defn pr-respond-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/respond-usage {:command (app-config/command-string "pr respond <pr-url>")}))
      (let [parse-url (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/parse-pr-url)
            respond!  (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/respond-to-comments!)
            run-spec  (requiring-resolve 'ai.miniforge.cli.workflow-runner/run-workflow-from-spec!)
            {:keys [number]} (parse-url url)]
        (when-not number
          (display/print-error "Could not parse PR number from URL")
          (System/exit 1))
        (display/print-info (str "Checking out PR #" number "..."))
        (let [branch (checkout-pr! number)]
          (when-not branch
            (display/print-error "Failed to checkout PR branch")
            (System/exit 1))
          (display/print-info (str "On branch: " branch))
          (let [cwd (System/getProperty "user.dir")
                result (respond! url cwd
                                 (fn [spec run-opts] (run-spec spec (merge {:quiet true} run-opts)))
                                 push!
                                 opts)]
            (display/print-info
             (str "\nDone: " (:comments-found result) " comment(s), "
                  (:files-processed result) " file(s) processed, "
                  (count (filter :succeeded? (:fixes result))) " fixed"
                  (when (:pushed? result) ", pushed")))))))))

(defn pr-merge-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/merge-usage {:command (app-config/command-string "pr merge <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/merging {:url url}))
        (println (messages/t :pr/merge-todo))))))

;------------------------------------------------------------------------------ Layer 1
;; PR Monitor (continuous loop)

(defn- resolve-author
  "Resolve the GitHub author login from --author flag, gh CLI, or default."
  [author-opt]
  (or author-opt
      (let [result (sh! "gh" "api" "user" "--jq" ".login")]
        (when (zero? (:exit result))
          (let [login (str/trim (:out result))]
            (when (seq login) login))))))

(defn- parse-poll-interval
  "Parse poll interval in seconds, with bounds checking. Returns milliseconds."
  [interval-str default-ms]
  (if interval-str
    (try
      (let [seconds (Long/parseLong (str interval-str))]
        (if (<= 5 seconds 3600)
          (* seconds 1000)
          (do (display/print-error (str "Poll interval must be 5-3600 seconds, got " seconds ". Using default."))
              default-ms)))
      (catch NumberFormatException _
        (display/print-error (str "Invalid poll interval: " interval-str ". Using default."))
        default-ms))
    default-ms))

(defn pr-monitor-cmd
  "Start the PR monitor loop for autonomous comment resolution.

   Polls open PRs, classifies new comments, and routes them to handlers
   (fix change-requests, answer questions, skip noise). Runs continuously
   until stopped with Ctrl+C, budget exhausted, or no open PRs remain."
  [opts]
  (let [{:keys [author poll-interval]} opts
        cwd        (System/getProperty "user.dir")
        author     (or (resolve-author author) "miniforge[bot]")
        poll-ms    (parse-poll-interval poll-interval 60000)
        create-mon (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/create-pr-monitor)
        run-loop   (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/run-pr-monitor-loop)
        stop-loop  (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/stop-pr-monitor-loop)
        monitor    (create-mon {:worktree-path    cwd
                                :self-author      author
                                :poll-interval-ms poll-ms})]
    (display/print-info (str "Starting PR monitor for author: " author))
    (display/print-info (str "Polling every " (/ poll-ms 1000) "s in " cwd))
    (display/print-info "Press Ctrl+C to stop.")
    (let [shutdown (fn []
                     (display/print-info "\nStopping monitor...")
                     (try (stop-loop monitor) (catch Exception _)))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown))
      (let [evidence (run-loop monitor author)]
        (display/print-info (str "\nMonitor stopped. Evidence: " (pr-str evidence)))))))
