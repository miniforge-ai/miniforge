(ns ai.miniforge.pr-lifecycle.controller-test
  "Unit tests for the PR lifecycle controller state machine.

   Tests controller creation, state initialization, configuration,
   fix iteration enforcement, and history tracking.
   Does NOT test functions that call external services."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-lifecycle.controller :as controller]
   [ai.miniforge.pr-lifecycle.merge :as merge]))

;------------------------------------------------------------------------------ Test Data

(def test-task
  {:task/id "task-abc"
   :task/title "Implement feature X"
   :task/description "Add the feature"
   :task/acceptance-criteria ["Tests pass" "No lint errors"]
   :task/constraints ["No breaking changes"]})

(defn make-event-collector
  "Create a minimal event bus that collects published events."
  []
  (let [events (atom [])]
    {:publish! (fn [event] (swap! events conj event) nil)
     :events events}))

;------------------------------------------------------------------------------ Controller Creation

(deftest create-controller-initial-state-test
  (testing "Controller initializes with :pending status"
    (let [ctrl (controller/create-controller
                 "dag-1" "run-1" "task-1" test-task
                 :worktree-path "/tmp/repo")]
      (is (= :pending (:status @ctrl)))
      (is (nil? (:pr @ctrl)))
      (is (= 0 (:fix-iterations @ctrl)))
      (is (= 0 (:ci-retries @ctrl)))
      (is (empty? (:history @ctrl)))
      (is (inst? (:created-at @ctrl)))
      (is (inst? (:updated-at @ctrl))))))

(deftest create-controller-ids-test
  (testing "Controller stores DAG/run/task IDs"
    (let [ctrl (controller/create-controller
                 "dag-1" "run-1" "task-1" test-task
                 :worktree-path "/tmp")]
      (is (= "dag-1" (:dag/id @ctrl)))
      (is (= "run-1" (:run/id @ctrl)))
      (is (= "task-1" (:task/id @ctrl)))
      (is (uuid? (:controller/id @ctrl))))))

(deftest create-controller-stores-task-test
  (testing "Controller stores the task definition"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= test-task (:task @ctrl))))))

(deftest create-controller-default-config-test
  (testing "Controller uses default configuration values"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp/repo")]
      (is (= "/tmp/repo" (get-in @ctrl [:config :worktree-path])))
      (is (= 5 (get-in @ctrl [:config :max-fix-iterations])))
      (is (= 30000 (get-in @ctrl [:config :ci-poll-interval-ms])))
      (is (= 30000 (get-in @ctrl [:config :review-poll-interval-ms])))
      (is (= merge/default-merge-policy (get-in @ctrl [:config :merge-policy])))
      (is (true? (get-in @ctrl [:config :auto-resolve-comments]))))))

(deftest create-controller-custom-config-test
  (testing "Controller accepts custom configuration"
    (let [custom-policy {:method :rebase :require-ci-green? false}
          ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/custom/path"
                 :max-fix-iterations 10
                 :ci-poll-interval-ms 5000
                 :review-poll-interval-ms 10000
                 :merge-policy custom-policy
                 :auto-resolve-comments false)]
      (is (= "/custom/path" (get-in @ctrl [:config :worktree-path])))
      (is (= 10 (get-in @ctrl [:config :max-fix-iterations])))
      (is (= 5000 (get-in @ctrl [:config :ci-poll-interval-ms])))
      (is (= 10000 (get-in @ctrl [:config :review-poll-interval-ms])))
      (is (= custom-policy (get-in @ctrl [:config :merge-policy])))
      (is (false? (get-in @ctrl [:config :auto-resolve-comments]))))))

(deftest create-controller-with-dependencies-test
  (testing "Controller stores event-bus, logger, generate-fn"
    (let [bus (make-event-collector)
          gen-fn (fn [& _] nil)
          ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :event-bus bus
                 :generate-fn gen-fn)]
      (is (= bus (:event-bus @ctrl)))
      (is (= gen-fn (:generate-fn @ctrl))))))

;------------------------------------------------------------------------------ Fix Iteration Enforcement

(deftest handle-ci-failure-max-iterations-test
  (testing "handle-ci-failure! throws when max iterations exceeded"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 3)]
      ;; Simulate having already used all iterations
      (swap! ctrl assoc :fix-iterations 3)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Max fix iterations exceeded"
            (controller/handle-ci-failure! ctrl "some ci logs"))))))

(deftest handle-review-feedback-max-iterations-test
  (testing "handle-review-feedback! throws when max iterations exceeded"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 2)]
      (swap! ctrl assoc :fix-iterations 2)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Max fix iterations exceeded"
            (controller/handle-review-feedback! ctrl [{:body "Fix"}]))))))

(deftest handle-ci-failure-increments-iteration-before-max-test
  (testing "handle-ci-failure! transitions to :fixing and increments counter"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 5
                 :generate-fn (fn [& _] nil))]
      ;; We need to set up PR info for the fix loop
      (swap! ctrl assoc
             :pr {:pr/id 123 :pr/branch "feat/x" :pr/head-sha "abc"}
             :fix-iterations 0)
      ;; The actual fix-ci-failure call will fail because generate-fn
      ;; returns nil, but we can verify the state transitions happened
      (try
        (controller/handle-ci-failure! ctrl "CI logs here")
        (catch Exception _e
          ;; Expected - fix generation will fail
          nil))
      ;; Verify state was updated before the fix attempt
      (is (= :fixing (:status @ctrl)))
      (is (= 1 (:fix-iterations @ctrl))))))

(deftest handle-ci-failure-sets-failed-on-max-test
  (testing "handle-ci-failure! sets :failed status when at max"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 3)]
      (swap! ctrl assoc :fix-iterations 3)
      (try
        (controller/handle-ci-failure! ctrl "logs")
        (catch clojure.lang.ExceptionInfo _e nil))
      (is (= :failed (:status @ctrl))))))

;------------------------------------------------------------------------------ State Manipulation (via atom)

(deftest controller-status-transitions-test
  (testing "Status can be manually transitioned for testing"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= :pending (:status @ctrl)))

      (swap! ctrl assoc :status :creating-pr)
      (is (= :creating-pr (:status @ctrl)))

      (swap! ctrl assoc :status :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      (swap! ctrl assoc :status :monitoring-review)
      (is (= :monitoring-review (:status @ctrl)))

      (swap! ctrl assoc :status :ready-to-merge)
      (is (= :ready-to-merge (:status @ctrl)))

      (swap! ctrl assoc :status :merged)
      (is (= :merged (:status @ctrl))))))

(deftest controller-pr-info-storage-test
  (testing "PR info can be stored and retrieved"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")
          pr-info {:pr/id 42
                   :pr/url "https://github.com/org/repo/pull/42"
                   :pr/branch "feat/bar"
                   :pr/head-sha "def456"}]
      (swap! ctrl assoc :pr pr-info)
      (is (= 42 (get-in @ctrl [:pr :pr/id])))
      (is (= "feat/bar" (get-in @ctrl [:pr :pr/branch]))))))

(deftest controller-history-accumulation-test
  (testing "History events accumulate correctly"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (swap! ctrl update :history conj
             {:type :pr-created :data {:pr-id 1} :timestamp (java.util.Date.)})
      (swap! ctrl update :history conj
             {:type :ci-passed :data {} :timestamp (java.util.Date.)})
      (swap! ctrl update :history conj
             {:type :merged :data {:sha "abc"} :timestamp (java.util.Date.)})
      (is (= 3 (count (:history @ctrl))))
      (is (= [:pr-created :ci-passed :merged]
             (mapv :type (:history @ctrl)))))))

(deftest controller-fix-iteration-counter-test
  (testing "Fix iteration counter increments correctly"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= 0 (:fix-iterations @ctrl)))
      (swap! ctrl update :fix-iterations inc)
      (is (= 1 (:fix-iterations @ctrl)))
      (swap! ctrl update :fix-iterations inc)
      (swap! ctrl update :fix-iterations inc)
      (is (= 3 (:fix-iterations @ctrl))))))

(deftest controller-monitors-nil-initially-test
  (testing "CI and review monitors are nil initially"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (nil? (:ci-monitor @ctrl)))
      (is (nil? (:review-monitor @ctrl))))))