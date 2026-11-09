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
(ns ai.miniforge.pr-lifecycle.ci-checks
  "GitHub CI check queries for PRs.

   Runs `gh pr checks` and `gh pr view` and classifies individual checks
   into an overall CI status. The polling loop lives in `ci-monitor`."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; GitHub CLI helpers
(defn ^{:stratum 0} run-gh-command
  "Run a gh CLI command and return result."
  [args worktree-path]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        args)]
      (if (zero? (:exit result))
        (dag/ok {:output (str/trim (:out result ""))})
        (dag/err :gh-command-failed
                 (str/trim (:err result ""))
                 {:exit-code (:exit result)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

;; Value coercion
(defn- ^{:stratum 0} keywordize-val
  "Coerce a map value to a lower-case keyword, returning `default` when nil."
  [m k default]
  (or (some-> (get m k) str/lower-case keyword) default))

(defn ^{:stratum 0} get-check-logs
  "Get logs for failed checks.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - check-name: Name of the check

   Returns result with logs."
  [_worktree-path _pr-number check-name]
  ;; Note: gh doesn't have a direct way to get check logs
  ;; This would typically go through the GitHub API or action artifacts
  (dag/ok {:logs nil
           :message "Log retrieval requires GitHub API access"
           :check-name check-name}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-pr-checks
  "Get CI check status for a PR.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number

   Returns result with check information."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "checks" (str pr-number) "--json"
                 "name,state,conclusion,detailsUrl"]
                worktree-path)]
    (if (dag/ok? result)
      (try
        (let [checks (edn/read-string
                      (str "[" (str/replace (:output (:data result))
                                            #"\"(\w+)\":" ":$1 ")
                           "]"))]
          (dag/ok {:checks checks}))
        (catch Exception _e
          ;; Fallback: try to parse as JSON manually
          (dag/ok {:checks [] :raw (:output (:data result))})))
      result)))

(defn ^{:stratum 1} get-pr-status
  "Get overall PR status including checks.

   Returns result with :state :mergeable :reviewDecision etc."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json"
                 "state,mergeable,reviewDecision,statusCheckRollup,headRefOid"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:raw (:output (:data result))})
      result)))

;; Status computation
(defn ^{:stratum 1} compute-ci-status
  "Compute overall CI status from individual checks.

   Arguments:
   - checks: Sequence of check maps with :state and :conclusion

   Returns {:status keyword :passed [] :failed [] :pending []}"
  [checks]
  (let [grouped (group-by (fn [check]
                            (let [state      (keywordize-val check :state :unknown)
                                  conclusion (keywordize-val check :conclusion :unknown)]
                              (cond
                                (= state :completed)
                                (case conclusion
                                  :success :passed
                                  (:failure :action-required) :failed
                                  (:neutral :skipped) :neutral
                                  :cancelled :cancelled
                                  :timed-out :timed-out
                                  :failed)

                                (#{:in-progress :queued :waiting :pending} state)
                                :pending

                                :else :unknown)))
                          checks)
        passed (vec (get grouped :passed []))
        failed (vec (get grouped :failed []))
        pending (vec (get grouped :pending []))
        neutral (vec (get grouped :neutral []))

        status (cond
                 (seq failed) :failure
                 (seq pending) :pending
                 (seq passed) :success
                 (seq neutral) :neutral
                 :else :unknown)]

    {:status status
     :passed passed
     :failed failed
     :pending pending
     :neutral neutral
     :total (count checks)}))
