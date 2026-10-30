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
   head branch, so the worktree must have that branch checked out.
   Otherwise the commit lands on whatever branch is checked out and the
   push sends the PR branch's unchanged local ref."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} checked-out-branch
  "The branch checked out in `worktree-path`, or nil when git cannot say."
  [worktree-path]
  (let [{:keys [exit out]} (process/sh {:dir (str worktree-path)}
                                       "git" "rev-parse" "--abbrev-ref" "HEAD")]
    (when (zero? exit)
      (str/trim out))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fix-refusal
  "Nil when a change-request fix may start for `pr-info`; otherwise the
   handler result explaining why not, after logging it. `config` is the
   monitor config (:worktree-path :generate-fn :logger)."
  [{:keys [worktree-path generate-fn logger]} pr-info]
  (let [branch (:pr/branch pr-info)
        checked-out (when generate-fn (checked-out-branch worktree-path))]
    (cond
      (not generate-fn)
      (do (when logger
            (log/warn logger :pr-monitor :handler/no-generate-fn
                      {:message (messages/system-t :monitor/fix-without-generate-fn)}))
          {:success? false :reason :no-generate-fn})

      (or (str/blank? branch) (not= branch checked-out))
      (do (when logger
            (log/warn logger :pr-monitor :handler/worktree-not-on-pr-branch
                      {:message (messages/system-t :monitor/worktree-not-on-pr-branch
                                                   {:branch branch :checked-out checked-out
                                                    :path worktree-path})
                       :data {:pr-number (:pr/number pr-info)}}))
          {:success? false :reason :worktree-not-on-pr-branch
           :pr/branch branch :checked-out checked-out}))))
