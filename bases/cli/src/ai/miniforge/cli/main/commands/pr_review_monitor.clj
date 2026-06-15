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

(ns ai.miniforge.cli.main.commands.pr-review-monitor
  "N13 §2.2 Standards Reviewer auto-trigger.

   Single-pass orchestrator: list open PRs in scope, dedup against
   PRs we've already standards-reviewed at their current head SHA,
   for each remaining PR run scan/classify/render/post against an
   ephemeral worktree pinned to that SHA.

   v0 ships `--once` (single pass). The persistent daemon loop is a
   v1 follow-up — once cleanly works, the loop is just a `while +
   sleep` wrap."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.commands.pr-review :as pr-review]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;; ── helpers ──────────────────────────────────────────────────────────

(defn- gh-pr-base-ref
  "Resolve the base-branch ref for `pr-number` via `gh pr view`,
   running in `worktree-path` so {owner}/{repo} resolves from the
   git remote. Returns `\"origin/<base>\"` or nil on failure."
  [worktree-path pr-number]
  (try
    (let [r (process/shell {:dir (str worktree-path)
                            :out :string
                            :err :string
                            :continue true}
                           "gh" "pr" "view" (str pr-number)
                           "--json" "baseRefName"
                           "--jq" ".baseRefName")]
      (when (zero? (:exit r))
        (let [name (str/trim (:out r ""))]
          (when (seq name) (str "origin/" name)))))
    (catch Throwable _ nil)))

(defn- review-one-pr!
  "Bracket: ephemeral worktree at PR head SHA → run pr-review with
   --post against that worktree. Per-PR errors don't abort the pass
   — they're surfaced to the operator and we move on."
  [base-repo {:pr/keys [number sha url]} review-opts]
  (display/print-info (messages/t :pr/review-monitor-running {:n number :sha (subs sha 0 (min 7 (count sha)))}))
  (let [base-ref (gh-pr-base-ref base-repo number)]
    (cond
      (not base-ref)
      (display/print-error
       (messages/t :pr/review-monitor-base-failed {:n number}))

      :else
      (let [r (pr-lifecycle/with-pr-worktree
               base-repo number sha
               (fn [{:keys [worktree-path]}]
                 (pr-review/run-pr-review!
                  worktree-path
                  (assoc review-opts
                         :base-ref  base-ref
                         :post?     true
                         :pr-number number
                         :commit-id sha))))]
        (when-not (dag/ok? r)
          (display/print-error
           (messages/t :pr/review-monitor-pr-failed
                       {:n number
                        :url url
                        :code (str (get-in r [:error :code]))
                        :message (or (get-in r [:error :message]) "")})))))))

(defn- existing-shas-by-pr
  "For each PR in `prs`, look up the marker-bearing review SHAs.
   Returns a map keyed by `:pr/number`. PRs whose lookup fails get
   an empty set (so we treat them as needing review and let the
   per-PR error path catch any subsequent failure)."
  [base-repo prs]
  (reduce
   (fn [acc {:pr/keys [number]}]
     (let [r (pr-lifecycle/existing-review-shas base-repo number)]
       (assoc acc number (if (dag/ok? r) (:data r) #{}))))
   {}
   prs))

(defn- run-pass!
  "One pass over the in-scope open PR set."
  [base-repo author review-opts]
  (let [r (pr-lifecycle/poll-open-prs base-repo author)]
    (cond
      (not (dag/ok? r))
      (display/print-error
       (messages/t :pr/review-monitor-poll-failed
                   {:code (str (get-in r [:error :code]))
                    :message (or (get-in r [:error :message]) "")}))

      :else
      (let [prs (:prs (:data r))]
        (display/print-info
         (messages/t :pr/review-monitor-poll-summary {:count (count prs) :author author}))
        (let [existing (existing-shas-by-pr base-repo prs)
              {:keys [needs-review already-reviewed]}
              (pr-lifecycle/partition-needs-review prs existing)]
          (display/print-info
           (messages/t :pr/review-monitor-partition
                       {:needs (count needs-review)
                        :skip  (count already-reviewed)}))
          (doseq [pr needs-review]
            (review-one-pr! base-repo pr review-opts)))))))

;; ── command entry ────────────────────────────────────────────────────

(defn pr-review-monitor-cmd
  "CLI entry for `bb miniforge pr review-monitor`.

   v0: single-pass when `--once` (the only mode). The persistent
   loop is intentionally deferred — v0 lets operators cron / loop
   externally, and v1 will add `--poll-interval` here.

   Flags:
   - --repo <path>   base repo for `gh pr list` + `git worktree`
                     (defaults to cwd)
   - --author <login> filter PRs by author. Required.
   - --standards <path> .standards dir (default `.standards`)
   - --pack <name|path> standards pack to apply
   - --rules <selector> rule selector (default :all)
   - --once          single pass (default; v0 has no other mode)"
  [opts]
  (let [base-repo (get opts :repo (str (fs/cwd)))
        author    (get opts :author)]
    (cond
      (not (fs/exists? base-repo))
      (display/print-error
       (messages/t :pr/review-monitor-repo-not-found {:path base-repo}))

      (or (nil? author) (and (string? author) (str/blank? author)))
      (display/print-error
       (messages/t :pr/review-monitor-author-required))

      :else
      (try
        (let [review-opts (cond-> {}
                            (:standards opts) (assoc :standards (:standards opts))
                            (:pack opts)      (assoc :pack (:pack opts))
                            (:rules opts)     (assoc :rules (:rules opts)))]
          (run-pass! base-repo author review-opts))
        (catch Exception e
          (display/print-error
           (messages/t :pr/review-monitor-failed {:message (ex-message e)})))))))
