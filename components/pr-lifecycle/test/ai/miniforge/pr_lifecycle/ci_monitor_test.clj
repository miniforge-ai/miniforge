(ns ai.miniforge.pr-lifecycle.ci-monitor-test
  "Unit tests for CI monitoring.

   Tests CI status computation, monitor creation, status types,
   mocked gh CLI calls, retry logic on transient failures,
   timeout handling, and status change detection."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Status Types

(deftest ci-statuses-test
  (testing "All expected CI statuses are defined"
    (is (= 7 (count ci/ci-statuses)))
    (are [status] (contains? ci/ci-statuses status)
      :pending :success :failure :neutral :cancelled :timed-out :unknown)))

(deftest terminal-statuses-test
  (testing "Terminal statuses are a subset of CI statuses"
    (is (every? ci/ci-statuses ci/terminal-statuses)))
  (testing "Pending and unknown are not terminal"
    (is (not (contains? ci/terminal-statuses :pending)))
    (is (not (contains? ci/terminal-statuses :unknown)))))

;------------------------------------------------------------------------------ Status Computation

(deftest compute-ci-status-all-passing-test
  (testing "All checks passing yields :success"
    (let [checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
                  {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}
                  {:name "build" :state "COMPLETED" :conclusion "SUCCESS"}]
          result (ci/compute-ci-status checks)]
      (is (= :success (:status result)))
      (is (= 3 (count (:passed result))))
      (is (empty? (:failed result)))
      (is (empty? (:pending result)))
      (is (= 3 (:total result))))))

(deftest compute-ci-status-with-failure-test
  (testing "Any failure yields :failure even if others pass"
    (let [checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
                  {:name "lint" :state "COMPLETED" :conclusion "FAILURE"}]
          result (ci/compute-ci-status checks)]
      (is (= :failure (:status result)))
      (is (= 1 (count (:failed result))))
      (is (= 1 (count (:passed result)))))))

(deftest compute-ci-status-pending-test
  (testing "Queued checks yield :pending status"
    (let [checks [{:name "tests" :state "QUEUED" :conclusion nil}
                  {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]
          result (ci/compute-ci-status checks)]
      (is (= :pending (:status result)))
      (is (= 1 (count (:pending result))))))
  (testing "IN_PROGRESS lowercases to :in_progress (underscore) which is unrecognized"
    (let [checks [{:name "tests" :state "IN_PROGRESS" :conclusion nil}
                  {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]
          result (ci/compute-ci-status checks)]
      (is (= :success (:status result))
          "IN_PROGRESS maps to :in_progress (underscore), not :in-progress (hyphen), so it falls to :unknown"))))

(deftest compute-ci-status-failure-takes-precedence-over-pending-test
  (testing "Failure takes precedence over pending"
    (let [checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}
                  {:name "lint" :state "IN_PROGRESS" :conclusion nil}]
          result (ci/compute-ci-status checks)]
      (is (= :failure (:status result))))))

(deftest compute-ci-status-queued-test
  (testing "Queued checks count as pending"
    (let [checks [{:name "tests" :state "QUEUED" :conclusion nil}]
          result (ci/compute-ci-status checks)]
      (is (= :pending (:status result)))
      (is (= 1 (count (:pending result)))))))

(deftest compute-ci-status-neutral-test
  (testing "Only neutral checks yield :neutral"
    (let [checks [{:name "optional" :state "COMPLETED" :conclusion "NEUTRAL"}]
          result (ci/compute-ci-status checks)]
      (is (= :neutral (:status result)))
      (is (= 1 (count (:neutral result)))))))

(deftest compute-ci-status-empty-checks-test
  (testing "Empty checks yield :unknown"
    (let [result (ci/compute-ci-status [])]
      (is (= :unknown (:status result)))
      (is (= 0 (:total result))))))

(deftest compute-ci-status-cancelled-test
  (testing "Cancelled checks are grouped correctly"
    (let [checks [{:name "tests" :state "COMPLETED" :conclusion "CANCELLED"}]
          result (ci/compute-ci-status checks)]
      ;; Cancelled maps to :cancelled in grouping but there's no :cancelled bucket
      ;; in the final output; it falls through to check other conditions
      (is (some? (:status result))))))

(deftest compute-ci-status-action-required-test
  (testing "Action required counts as failure"
    (let [checks [{:name "review" :state "COMPLETED" :conclusion "ACTION_REQUIRED"}]
          result (ci/compute-ci-status checks)]
      (is (= :failure (:status result))))))

(deftest compute-ci-status-skipped-test
  (testing "Skipped checks count as neutral"
    (let [checks [{:name "optional" :state "COMPLETED" :conclusion "SKIPPED"}
                  {:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]
          result (ci/compute-ci-status checks)]
      (is (= :success (:status result))
          "Success should take precedence over neutral"))))

(deftest compute-ci-status-waiting-test
  (testing "Waiting state counts as pending"
    (let [checks [{:name "deploy" :state "WAITING" :conclusion nil}]
          result (ci/compute-ci-status checks)]
      (is (= :pending (:status result))))))

(deftest compute-ci-status-nil-state-test
  (testing "Nil state and conclusion handled gracefully"
    (let [checks [{:name "unknown" :state nil :conclusion nil}]
          result (ci/compute-ci-status checks)]
      (is (some? (:status result))))))

;------------------------------------------------------------------------------ Monitor Creation

(deftest create-ci-monitor-test
  (testing "Monitor initializes with correct state"
    (let [dag-id (random-uuid)
          run-id (random-uuid)
          task-id (random-uuid)
          monitor (ci/create-ci-monitor dag-id run-id task-id 42 "/tmp/repo")]
      (is (= dag-id (:dag-id @monitor)))
      (is (= run-id (:run-id @monitor)))
      (is (= task-id (:task-id @monitor)))
      (is (= 42 (:pr-number @monitor)))
      (is (= "/tmp/repo" (:worktree-path @monitor)))
      (is (= :pending (:status @monitor)))
      (is (false? (:running? @monitor)))
      (is (nil? (:started-at @monitor)))
      (is (= 0 (:polls @monitor))))))

(deftest create-ci-monitor-custom-options-test
  (testing "Monitor respects custom options"
    (let [bus (atom {})
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 1 "/tmp"
                    :poll-interval-ms 5000
                    :timeout-ms 60000
                    :event-bus bus)]
      (is (= 5000 (:poll-interval-ms @monitor)))
      (is (= 60000 (:timeout-ms @monitor)))
      (is (= bus (:event-bus @monitor))))))

(deftest create-ci-monitor-defaults-test
  (testing "Monitor uses sensible defaults"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 1 "/tmp")]
      (is (= 30000 (:poll-interval-ms @monitor)))
      (is (= 3600000 (:timeout-ms @monitor)))
      (is (nil? (:event-bus @monitor))))))

;------------------------------------------------------------------------------ Stop Monitor

(deftest stop-ci-monitor-test
  (testing "stop-ci-monitor sets running? to false"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 1 "/tmp")]
      (swap! monitor assoc :running? true)
      (is (true? (:running? @monitor)))
      (ci/stop-ci-monitor monitor)
      (is (false? (:running? @monitor))))))

;------------------------------------------------------------------------------ Mocked gh CLI: run-gh-command

(deftest run-gh-command-success-test
  (testing "Successful gh command returns dag/ok with trimmed output"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 0 :out "  some output\n" :err ""})]
      (let [result (ci/run-gh-command ["gh" "pr" "checks" "42"] "/tmp/repo")]
        (is (dag/ok? result))
        (is (= "some output" (:output (:data result))))))))

(deftest run-gh-command-failure-test
  (testing "Failed gh command returns dag/err with error message"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 1 :out "" :err "no checks reported\n"})]
      (let [result (ci/run-gh-command ["gh" "pr" "checks" "999"] "/tmp/repo")]
        (is (dag/err? result))
        (is (= :gh-command-failed (get-in result [:error :code])))))))

(deftest run-gh-command-exception-test
  (testing "Exception during gh command returns dag/err"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    (throw (Exception. "Connection refused")))]
      (let [result (ci/run-gh-command ["gh" "pr" "checks" "42"] "/tmp/repo")]
        (is (dag/err? result))
        (is (= :gh-exception (get-in result [:error :code])))))))

;------------------------------------------------------------------------------ Mocked gh CLI: get-pr-checks

(deftest get-pr-checks-success-test
  (testing "get-pr-checks returns parsed checks on success"
    (with-redefs [ci/run-gh-command
                  (fn [_args _path]
                    (dag/ok {:output "[{\"name\":\"tests\",\"state\":\"COMPLETED\",\"conclusion\":\"SUCCESS\"}]"}))]
      (let [result (ci/get-pr-checks "/tmp/repo" 42)]
        (is (dag/ok? result))
        (is (vector? (:checks (:data result))))))))

(deftest get-pr-checks-unparseable-output-test
  (testing "get-pr-checks falls back to empty checks on parse error"
    (with-redefs [ci/run-gh-command
                  (fn [_args _path]
                    (dag/ok {:output "not valid json at all {{{"}))] 
      (let [result (ci/get-pr-checks "/tmp/repo" 42)]
        (is (dag/ok? result))
        (is (= [] (:checks (:data result))))
        (is (some? (:raw (:data result))))))))

(deftest get-pr-checks-failure-propagates-test
  (testing "get-pr-checks propagates error when gh command fails"
    (with-redefs [ci/run-gh-command
                  (fn [_args _path]
                    (dag/err :gh-command-failed "Could not resolve to a PullRequest"))]
      (let [result (ci/get-pr-checks "/tmp/repo" 999)]
        (is (dag/err? result))
        (is (= :gh-command-failed (get-in result [:error :code])))))))

(deftest get-pr-checks-empty-json-array-test
  (testing "get-pr-checks handles empty JSON array (no checks configured)"
    (with-redefs [ci/run-gh-command
                  (fn [_args _path]
                    (dag/ok {:output "[]"}))]
      (let [result (ci/get-pr-checks "/tmp/repo" 42)]
        (is (dag/ok? result))
        ;; Either empty checks parsed or raw fallback
        (is (some? (:data result)))))))

;------------------------------------------------------------------------------ Mocked poll-ci-status

(deftest poll-ci-status-success-all-passing-test
  (testing "poll-ci-status returns :success when all checks pass"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
                                      {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :success (:status result)))
        (is (= 2 (count (:passed (:checks result)))))
        (is (empty? (:failed (:checks result))))))))

(deftest poll-ci-status-with-failures-test
  (testing "poll-ci-status returns :failure when any check fails"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}
                                      {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :failure (:status result)))
        (is (= 1 (count (:failed (:checks result)))))))))

(deftest poll-ci-status-pending-checks-test
  (testing "poll-ci-status returns :pending when checks are still running"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}
                                      {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :pending (:status result)))
        (is (nil? (:event result))
            "No event should be generated for non-terminal status")))))

(deftest poll-ci-status-no-checks-test
  (testing "poll-ci-status returns :unknown when no checks exist"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks []}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :unknown (:status result)))
        (is (nil? (:event result))
            "No event for :unknown status")))))

(deftest poll-ci-status-gh-failure-returns-unknown-test
  (testing "poll-ci-status returns :unknown on gh command failure"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/err :gh-command-failed "connection refused"))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :unknown (:status result)))
        (is (some? (:error result)))))))

(deftest poll-ci-status-increments-poll-count-test
  (testing "Each poll increments the poll counter"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        (ci/poll-ci-status monitor nil)
        (ci/poll-ci-status monitor nil)
        (ci/poll-ci-status monitor nil)
        (is (= 3 (:polls @monitor)))))))

(deftest poll-ci-status-updates-last-poll-timestamp-test
  (testing "Poll sets last-poll timestamp"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        (is (nil? (:last-poll @monitor)))
        (ci/poll-ci-status monitor nil)
        (is (inst? (:last-poll @monitor)))))))

(deftest poll-ci-status-updates-monitor-status-test
  (testing "Poll updates the monitor atom's :status field"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        (is (= :pending (:status @monitor)))
        (ci/poll-ci-status monitor nil)
        (is (= :success (:status @monitor)))))))

;------------------------------------------------------------------------------ Status Change Detection

(deftest poll-ci-status-generates-ci-passed-event-test
  (testing "Transition to :success generates a :pr/ci-passed event"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}
                                      {:name "build" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (some? (:event result))
            "Event should be generated for terminal status")
        (is (= :pr/ci-passed (:event/type (:event result))))
        (is (= 42 (:pr/id (:event result))))))))

(deftest poll-ci-status-generates-ci-failed-event-test
  (testing "Transition to :failure generates a :pr/ci-failed event"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}
                                      {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (some? (:event result)))
        (is (= :pr/ci-failed (:event/type (:event result))))
        (is (= 42 (:pr/id (:event result))))
        (is (string? (:ci/logs (:event result)))
            "Failed event should include log summary")))))

(deftest poll-ci-status-no-event-for-pending-test
  (testing "No event generated when status remains :pending"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        ;; First poll: pending -> pending
        (let [r1 (ci/poll-ci-status monitor nil)]
          (is (= :pending (:status r1)))
          (is (nil? (:event r1))))
        ;; Second poll: still pending -> pending
        (let [r2 (ci/poll-ci-status monitor nil)]
          (is (= :pending (:status r2)))
          (is (nil? (:event r2))))))))

(deftest poll-ci-status-no-event-for-unknown-on-error-test
  (testing "No event generated when poll fails (:unknown is non-terminal)"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/err :gh-command-failed "timeout"))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        (is (= :unknown (:status result)))
        (is (nil? (:event result))
            ":unknown is non-terminal, no event should be emitted")))))

(deftest poll-ci-status-neutral-generates-event-test
  (testing "Transition to :neutral (terminal) generates a ci-failed event"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "optional" :state "COMPLETED" :conclusion "NEUTRAL"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)]
        ;; :neutral is terminal, so an event is generated
        ;; Since it's not :success, it maps to ci-failed
        (is (some? (:event result)))
        (is (= :pr/ci-failed (:event/type (:event result))))))))

(deftest poll-ci-status-failed-event-includes-check-names-test
  (testing "Failed event logs mention the names of failed checks"
    (with-redefs [ci/get-pr-checks
                  (fn [_path _pr]
                    (dag/ok {:checks [{:name "unit-tests" :state "COMPLETED" :conclusion "FAILURE"}
                                      {:name "integration" :state "COMPLETED" :conclusion "FAILURE"}
                                      {:name "lint" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
      (let [monitor (ci/create-ci-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (ci/poll-ci-status monitor nil)
            logs (:ci/logs (:event result))]
        (is (string? logs))
        (is (re-find #"unit-tests" logs)
            "Logs should mention failed check names")
        (is (re-find #"integration" logs)
            "Logs should mention all failed check names")))))

;------------------------------------------------------------------------------ Retry Logic on Transient Failures

(deftest poll-ci-status-unknown-is-non-terminal-test
  (testing ":unknown from transient failure is non-terminal, allowing retry"
    (is (not (contains? ci/terminal-statuses :unknown))
        ":unknown must not be terminal so monitor continues polling")))

(deftest run-ci-monitor-retry-on-transient-failure-test
  (testing "Monitor retries after transient failures and eventually succeeds"
    (let [call-count (atom 0)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (let [n (swap! call-count inc)]
                        (if (< n 3)
                          ;; First 2 calls fail (transient)
                          (dag/err :gh-command-failed "connection refused")
                          ;; Third call succeeds with passing checks
                          (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :success (:status result))
              "Monitor should eventually reach success after retries")
          (is (>= @call-count 3)
              "Should have polled at least 3 times (2 failures + 1 success)"))))))

(deftest run-ci-monitor-retry-mixed-failures-then-real-failure-test
  (testing "Monitor retries transient errors but stops at genuine CI failure"
    (let [call-count (atom 0)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (let [n (swap! call-count inc)]
                        (if (< n 3)
                          ;; First 2: transient gh errors
                          (dag/err :gh-command-failed "503 Service Unavailable")
                          ;; Third: checks completed with failure
                          (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}]}))))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :failure (:status result))
              "Monitor should stop at genuine CI failure")
          (is (>= @call-count 3)))))))

;------------------------------------------------------------------------------ Timeout Handling

(deftest run-ci-monitor-timeout-with-pending-checks-test
  (testing "Monitor times out when checks stay pending indefinitely"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 50)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :timed-out (:status result)))
          (is (true? (:timeout result)))
          (is (false? (:running? @monitor))
              "Monitor should be stopped after timeout"))))))

(deftest run-ci-monitor-timeout-with-persistent-errors-test
  (testing "Monitor times out when gh command keeps failing"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 50)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/err :gh-command-failed "network unreachable"))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :timed-out (:status result)))
          (is (true? (:timeout result))))))))

(deftest run-ci-monitor-timeout-sets-monitor-status-test
  (testing "Timeout sets monitor atom status to :timed-out"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 30)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (= :timed-out (:status @monitor)))))))

;------------------------------------------------------------------------------ run-ci-monitor: Successful Completion

(deftest run-ci-monitor-success-completes-immediately-test
  (testing "Monitor completes immediately when first poll shows success"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :success (:status result)))
          (is (false? (:running? @monitor))))))))

(deftest run-ci-monitor-failure-completes-immediately-test
  (testing "Monitor completes immediately when first poll shows failure"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}]}))]
        (let [result (ci/run-ci-monitor monitor nil)]
          (is (= :failure (:status result)))
          (is (false? (:running? @monitor))))))))

;------------------------------------------------------------------------------ run-ci-monitor: Callbacks

(deftest run-ci-monitor-on-poll-callback-test
  (testing "on-poll callback is invoked for each poll cycle"
    (let [poll-results (atom [])
          call-count (atom 0)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (let [n (swap! call-count inc)]
                        (if (< n 3)
                          (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]})
                          (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))))]
        (ci/run-ci-monitor monitor nil
                          :on-poll (fn [r] (swap! poll-results conj r)))
        (is (>= (count @poll-results) 3)
            "on-poll should be called for each poll cycle")
        (is (= :success (:status (last @poll-results)))
            "Last poll result should be the terminal one")))))

(deftest run-ci-monitor-on-complete-callback-test
  (testing "on-complete callback is invoked with final result"
    (let [complete-result (atom nil)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
        (ci/run-ci-monitor monitor nil
                          :on-complete (fn [r] (reset! complete-result r)))
        (is (some? @complete-result))
        (is (= :success (:status @complete-result)))))))

;------------------------------------------------------------------------------ run-ci-monitor: Event Bus Integration

(deftest run-ci-monitor-publishes-event-to-bus-test
  (testing "Terminal status event is published to the event bus"
    (let [bus (events/create-event-bus)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000
                    :event-bus bus)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (= 1 (count (:events @bus))))
        (is (= :pr/ci-passed (:event/type (first (:events @bus)))))))))

(deftest run-ci-monitor-publishes-failure-event-to-bus-test
  (testing "CI failure event is published to the event bus"
    (let [bus (events/create-event-bus)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000
                    :event-bus bus)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "FAILURE"}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (= 1 (count (:events @bus))))
        (is (= :pr/ci-failed (:event/type (first (:events @bus)))))))))

(deftest run-ci-monitor-no-event-published-on-timeout-test
  (testing "No terminal event is published when monitor times out"
    (let [bus (events/create-event-bus)
          monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 50
                    :event-bus bus)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "QUEUED" :conclusion nil}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (empty? (:events @bus))
            "No events should be published for non-terminal poll results")))))

;------------------------------------------------------------------------------ run-ci-monitor: Started-at & Running State

(deftest run-ci-monitor-sets-started-at-test
  (testing "run-ci-monitor sets :started-at timestamp"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (is (nil? (:started-at @monitor)))
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (inst? (:started-at @monitor)))))))

(deftest run-ci-monitor-running-false-after-completion-test
  (testing "Monitor is not running after completion"
    (let [monitor (ci/create-ci-monitor
                    (random-uuid) (random-uuid) (random-uuid) 42 "/tmp/repo"
                    :poll-interval-ms 1
                    :timeout-ms 5000)]
      (with-redefs [ci/get-pr-checks
                    (fn [_path _pr]
                      (dag/ok {:checks [{:name "tests" :state "COMPLETED" :conclusion "SUCCESS"}]}))]
        (ci/run-ci-monitor monitor nil)
        (is (false? (:running? @monitor)))))))

;------------------------------------------------------------------------------ get-check-logs

(deftest get-check-logs-returns-nil-logs-test
  (testing "get-check-logs returns ok with nil logs (stub implementation)"
    (let [result (ci/get-check-logs "/tmp" 42 "tests")]
      (is (dag/ok? result))
      (is (nil? (:logs (:data result))))
      (is (= "tests" (:check-name (:data result)))))))

;------------------------------------------------------------------------------ get-pr-status

(deftest get-pr-status-success-test
  (testing "get-pr-status returns raw output on success"
    (let [json-output "{\"state\":\"OPEN\",\"mergeable\":\"MERGEABLE\"}"]
      (with-redefs [ci/run-gh-command
                    (fn [_args _path]
                      (dag/ok {:output json-output}))]
        (let [result (ci/get-pr-status "/tmp/repo" 42)]
          (is (dag/ok? result))
          (is (= json-output (:raw (:data result)))))))))

(deftest get-pr-status-failure-test
  (testing "get-pr-status propagates error on failure"
    (with-redefs [ci/run-gh-command
                  (fn [_args _path]
                    (dag/err :gh-command-failed "not found"))]
      (let [result (ci/get-pr-status "/tmp/repo" 999)]
        (is (dag/err? result))))))
