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
  "PR subcommands: list, review, respond, merge.
   The monitor command is implemented in pr-monitor and delegated here."
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.pr-monitor :as pr-monitor]
   [ai.miniforge.cli.main.commands.pr-review :as pr-review]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;------------------------------------------------------------------------------ Layer 0
;; Shell primitives

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
        cfg   (load-config-fn config)
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
                                         "OPEN"   :green
                                         "MERGED" :magenta
                                         "CLOSED" :red
                                         :white)
                          state-badge  (display/style
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
  "Resolve the base-branch ref for `pr-number` via gh CLI.
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
   single PR review via `pr-lifecycle/post-review!`. The PR head SHA
   is derived from the worktree HEAD after `gh pr checkout`. Does NOT
   apply fixes — that remains the Comment Response Agent's job.

   Pass --repo <path> + --base <ref> to operate on an existing checkout
   without using `gh` to fetch metadata; `--url <pr-url>` is the default
   user-facing flow."
  [opts]
  (let [{:keys [url repo base]} opts]
    (cond
      (and repo base)
      (pr-review/run-pr-review-by-path-cmd opts)

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
                (display/print-error (messages/t :pr/review-base-ref-failed))

                :else
                (let [cwd (System/getProperty "user.dir")]
                  (display/print-info (messages/t :pr/respond-on-branch {:branch branch}))
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
          (shared/exit! 1))
        (display/print-info (messages/t :pr/respond-checkout {:number number}))
        (let [branch (checkout-pr! number)]
          (when-not branch
            (display/print-error (messages/t :pr/respond-checkout-failed))
            (shared/exit! 1))
          (display/print-info (messages/t :pr/respond-on-branch {:branch branch}))
          (let [cwd    (System/getProperty "user.dir")
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

(defn pr-monitor-cmd
  "Delegates to pr-monitor/pr-monitor-cmd. See that namespace for full docs."
  [opts]
  (pr-monitor/pr-monitor-cmd opts))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; List PRs across fleet repos
  ;; (pr-list-cmd {} load-config)

  ;; Review a PR via URL
  ;; (pr-review-cmd {:url "https://github.com/org/repo/pull/42"})

  ;; Respond to comments on a PR
  ;; (pr-respond-cmd {:url "https://github.com/org/repo/pull/42"})

  :leave-this-here)
