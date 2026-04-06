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

(ns ai.miniforge.pr-lifecycle.ci-monitor
  "CI status monitoring for PRs.

   Polls GitHub for CI check status and emits events
   when checks complete (pass or fail)."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; CI status types

(def ci-statuses
  "Possible CI check statuses."
  #{:pending    ; Checks running
    :success    ; All checks passed
    :failure    ; One or more checks failed
    :neutral    ; Checks completed with neutral status
    :cancelled  ; Checks were cancelled
    :timed-out  ; Checks timed out
    :unknown})  ; Could not determine status

(def terminal-statuses
  "CI statuses that indicate checks are complete."
  #{:success :failure :neutral :cancelled :timed-out})

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn run-gh-command
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

(defn get-pr-checks
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

(defn get-pr-status
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

;------------------------------------------------------------------------------ Layer 1
;; Status computation

(defn compute-ci-status
  "Compute overall CI status from individual checks.

   Arguments:
   - checks: Sequence of check maps with :state and :conclusion

   Returns {:status keyword :passed [] :failed [] :pending []}"
  [checks]
  (let [grouped (group-by (fn [check]
                            (get check :state "")
                                  (get check :conclusion "")
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

(defn get-check-logs
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

;------------------------------------------------------------------------------ Layer 2
;; Monitoring loop

(defn create-ci-monitor
  "Create a CI monitor for a PR.

   Arguments:
   - dag-id: DAG run ID
   - run-id: Run instance ID
   - task-id: Task ID
   - pr-number: PR number
   - worktree-path: Path to git worktree

   Options:
   - :poll-interval-ms - Polling interval (default 30000)
   - :timeout-ms - Total timeout (default 3600000 = 1 hour)
   - :event-bus - Event bus for publishing events

   Returns monitor state atom."
  [dag-id run-id task-id pr-number worktree-path
   & {:keys [poll-interval-ms timeout-ms event-bus]
      :or {poll-interval-ms 30000 timeout-ms 3600000}}]
  (atom {:dag-id dag-id
         :run-id run-id
         :task-id task-id
         :pr-number pr-number
         :worktree-path worktree-path
         :poll-interval-ms poll-interval-ms
         :timeout-ms timeout-ms
         :event-bus event-bus
         :status :pending
         :started-at nil
         :last-poll nil
         :polls 0
         :running? false}))

(defn poll-ci-status
  "Poll CI status once.

   Arguments:
   - monitor: Monitor state atom
   - logger: Optional logger

   Returns {:status keyword :checks [...] :event event-or-nil}"
  [monitor logger]
  (let [{:keys [dag-id run-id task-id pr-number worktree-path]} @monitor
        checks-result (get-pr-checks worktree-path pr-number)]

    (swap! monitor assoc
           :last-poll (java.util.Date.)
           :polls (inc (:polls @monitor 0)))

    (if (dag/err? checks-result)
      (do
        (when logger
          (log/warn logger :pr-lifecycle :ci/poll-failed
                    {:message "Failed to poll CI status"
                     :data {:pr-number pr-number
                            :error (:error checks-result)}}))
        {:status :unknown :error (:error checks-result)})

      (let [checks (:checks (:data checks-result))
            computed (compute-ci-status checks)
            status (:status computed)
            prev-status (:status @monitor)]

        ;; Update monitor state
        (swap! monitor assoc :status status :last-checks computed)

        ;; Generate event if status changed to terminal
        (when (and (not= prev-status status)
                   (contains? terminal-statuses status))
          (when logger
            (log/info logger :pr-lifecycle :ci/status-changed
                      {:message "CI status changed"
                       :data {:pr-number pr-number
                              :from prev-status
                              :to status}})))

        {:status status
         :checks computed
         :event (when (contains? terminal-statuses status)
                  (if (= status :success)
                    (events/ci-passed dag-id run-id task-id pr-number nil)
                    (events/ci-failed dag-id run-id task-id pr-number nil
                                      (str "Failed checks: "
                                           (str/join ", "
                                                     (map :name (:failed computed)))))))}))))

(defn run-ci-monitor
  "Run the CI monitor until checks complete or timeout.

   Arguments:
   - monitor: Monitor state atom
   - logger: Optional logger

   Options:
   - :on-poll - Callback (fn [poll-result]) after each poll
   - :on-complete - Callback (fn [final-status]) when complete

   Returns final status map."
  [monitor logger & {:keys [on-poll on-complete]}]
  (let [{:keys [poll-interval-ms timeout-ms event-bus]} @monitor
        start-time (System/currentTimeMillis)]

    (swap! monitor assoc :running? true :started-at (java.util.Date.))

    (when logger
      (log/info logger :pr-lifecycle :ci/monitor-started
                {:message "CI monitor started"
                 :data {:pr-number (:pr-number @monitor)
                        :timeout-ms timeout-ms}}))

    (loop []
      (let [elapsed (- (System/currentTimeMillis) start-time)
            poll-result (poll-ci-status monitor logger)]

        ;; Call poll callback
        (when on-poll
          (on-poll poll-result))

        ;; Publish event if generated
        (when-let [event (:event poll-result)]
          (when event-bus
            (events/publish! event-bus event logger)))

        (cond
          ;; Terminal status reached
          (contains? terminal-statuses (:status poll-result))
          (do
            (swap! monitor assoc :running? false)
            (when on-complete
              (on-complete poll-result))
            poll-result)

          ;; Timeout
          (>= elapsed timeout-ms)
          (do
            (swap! monitor assoc :running? false :status :timed-out)
            (when logger
              (log/warn logger :pr-lifecycle :ci/timeout
                        {:message "CI monitor timed out"
                         :data {:pr-number (:pr-number @monitor)
                                :elapsed-ms elapsed}}))
            {:status :timed-out :timeout true})

          ;; Continue polling
          :else
          (do
            (Thread/sleep poll-interval-ms)
            (recur)))))))

(defn stop-ci-monitor
  "Stop a running CI monitor."
  [monitor]
  (swap! monitor assoc :running? false))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a CI monitor
  (def monitor
    (create-ci-monitor
     (random-uuid) (random-uuid) (random-uuid)
     123 "/path/to/repo"
     :poll-interval-ms 10000))

  ;; Poll once
  (poll-ci-status monitor nil)

  ;; Compute status from checks
  (compute-ci-status
   [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
    {:name "lint" :state "COMPLETED" :conclusion "FAILURE"}
    {:name "build" :state "IN_PROGRESS" :conclusion nil}])
  ; => {:status :pending :passed [{...}] :failed [{...}] :pending [{...}]}

  ;; Run until complete (would block in real use)
  ;; (run-ci-monitor monitor nil :on-poll println)

  :leave-this-here)
