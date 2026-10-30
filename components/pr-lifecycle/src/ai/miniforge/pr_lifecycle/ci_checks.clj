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
   [cheshire.core :as json]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; GitHub CLI helpers
(def ^{:stratum 0} check-json-fields
  "Fields requested from `gh pr checks --json`. gh 2.102 accepts bucket,
   completedAt, description, event, link, name, startedAt, state and
   workflow; it rejects `conclusion` and `detailsUrl` with exit 1."
  "name,state,bucket,link,workflow")

(def ^{:stratum 0} checks-pending-exit-code
  "Exit code of plain-output `gh pr checks` while checks are pending.
   With --json gh 2.102 exits 0 instead; both mean the same thing here."
  8)

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

;; Output parsing
(defn ^{:stratum 0} parse-checks-json
  "Parse `gh pr checks --json` output into an ok result of {:checks [...]},
   or an error result when the output is not a JSON array."
  [output]
  (try+
    (let [checks (json/parse-string output true)]
      (if (sequential? checks)
        (dag/ok {:checks (vec checks)})
        (dag/err :gh-output-unparseable "gh pr checks did not return a JSON array"
                 {:raw output})))
    (catch Exception e
      (dag/err :gh-output-unparseable (ex-message e) {:raw output}))))

;; Check classification
(def ^{:stratum 0} bucket->group
  "Status group for each value of the `bucket` field of `gh pr checks --json`.
   gh derives the bucket from a check's state and conclusion."
  {"pass"     :passed
   "fail"     :failed
   "pending"  :pending
   "skipping" :neutral
   "cancel"   :cancelled})

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

(defn ^{:stratum 1} checks-not-started?
  "True when a failed `gh pr checks` call means CI has not finished
   reporting: the pending exit code, or `no checks reported` right after a
   push, before any workflow has registered a check."
  [result]
  (let [{:keys [message data]} (:error result)]
    (or (= checks-pending-exit-code (:exit-code data))
        (str/starts-with? (str message) "no checks reported"))))

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

;; Check classification
(defn ^{:stratum 1} check-group
  "Status group of one gh check, from its `bucket`; :unknown when absent."
  [check]
  (get bucket->group (:bucket check) :unknown))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} get-pr-checks
  "Get CI check status for a PR.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number

   Returns an ok result of {:checks [...]}, adding :pending? true when gh
   reports that checks have not finished; otherwise gh's error result."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "checks" (str pr-number) "--json" check-json-fields]
                worktree-path)]
    (cond
      (dag/ok? result) (parse-checks-json (:output (:data result)))
      (checks-not-started? result) (dag/ok {:checks [] :pending? true})
      :else result)))

;; Status computation
(defn ^{:stratum 2} compute-ci-status
  "Compute overall CI status from individual checks.

   Arguments:
   - checks: Sequence of `gh pr checks --json` maps with :name :state :bucket

   A cancelled check is not a pass: it outranks passed and neutral checks.

   Returns {:status keyword :passed [] :failed [] :pending [] :neutral []
            :cancelled [] :total n}"
  [checks]
  (let [grouped (group-by check-group checks)
        passed (vec (get grouped :passed []))
        failed (vec (get grouped :failed []))
        pending (vec (get grouped :pending []))
        neutral (vec (get grouped :neutral []))
        cancelled (vec (get grouped :cancelled []))

        status (cond
                 (seq failed) :failure
                 (seq pending) :pending
                 (seq cancelled) :cancelled
                 (seq passed) :success
                 (seq neutral) :neutral
                 :else :unknown)]

    {:status status
     :passed passed
     :failed failed
     :pending pending
     :neutral neutral
     :cancelled cancelled
     :total (count checks)}))
