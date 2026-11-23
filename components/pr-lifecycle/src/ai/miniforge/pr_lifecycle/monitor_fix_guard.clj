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
(ns ai.miniforge.pr-lifecycle.monitor-fix-guard
  "Whether the PR monitor may start a change-request fix. The fix loop
   writes and commits in the monitor's worktree and then pushes the PR's
   head branch by name, so the worktree must have that branch checked out,
   at the PR's head commit or a commit built on it (an earlier fix this
   cycle). A matching name alone is not enough: a fork's PR from its own
   `main` would then commit onto, and push to, this repository's `main`."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.schema.interface :as schema]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git-output
  "Trimmed stdout of `git args` run in `dir`, or nil when git fails."
  [dir & args]
  (let [{:keys [exit out]} (apply process/sh {:dir (str dir)} "git" args)]
    (when (zero? exit)
      (str/trim out))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} checked-out-branch
  "{:branch :sha} checked out in `worktree-path`, or nil when git cannot
   say."
  [worktree-path]
  (let [branch (git-output worktree-path "rev-parse" "--abbrev-ref" "HEAD")
        sha (git-output worktree-path "rev-parse" "HEAD")]
    (when (and branch sha)
      {:branch branch :sha sha})))

(defn ^{:stratum 1} head-builds-on?
  "True when commit `sha` is HEAD or an ancestor of it in `worktree-path`.
   False for a blank sha or one git does not have."
  [worktree-path sha]
  (boolean (and (not (str/blank? sha))
                (git-output worktree-path "merge-base" "--is-ancestor" sha "HEAD"))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} fix-refusal
  "Nil when a change-request fix may start for `pr-info`; otherwise the
   handler result (a schema/failure carrying :reason) explaining why not,
   after logging it. `config` is the monitor config (:worktree-path
   :generate-fn :logger)."
  [{:keys [worktree-path generate-fn logger]} pr-info]
  (let [{branch :pr/branch sha :pr/sha} pr-info
        head (when generate-fn (checked-out-branch worktree-path))]
    (cond
      (not generate-fn)
      (let [message (messages/system-t :monitor/fix-without-generate-fn)]
        (when logger
          (log/warn logger :pr-monitor :handler/no-generate-fn {:message message}))
        (schema/failure :fix message {:reason :no-generate-fn}))

      (or (str/blank? branch)
          (not= branch (:branch head))
          (not (head-builds-on? worktree-path sha)))
      (let [message (messages/system-t :monitor/worktree-not-on-pr-branch
                                       {:branch branch :checked-out (:branch head)
                                        :sha sha :head (:sha head) :path worktree-path})]
        (when logger
          (log/warn logger :pr-monitor :handler/worktree-not-on-pr-branch
                    {:message message :data {:pr-number (:pr/number pr-info)}}))
        (schema/failure :fix message {:reason :worktree-not-on-pr-branch
                                      :pr/branch branch :pr/sha sha :checked-out head})))))
