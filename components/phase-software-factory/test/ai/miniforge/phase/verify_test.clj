(ns ai.miniforge.phase.verify-test
  "Tests for the verify phase interceptor.

   Verify runs the test suite directly in the executor environment —
   no tester agent. Tests here cover: environment-based test execution,
   fail-fast on missing environment-id, and pass/fail result shapes."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.verify :as verify]
   [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Test fixtures

(defn create-base-context
  "Create base context with executor environment for testing."
  []
  {:execution/id (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/tmp/test-worktree"
   :execution/input {:description "Test task"
                     :title "Test"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}})

(defn with-mocked-test-runner
  "Run body-fn with run-tests! mocked to return a passing result."
  [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [_ & _opts] {:passed? true :test-count 5 :assertion-count 10
                                :fail-count 0 :error-count 0 :output "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."})}
      body-fn)))

(defn with-failing-test-runner
  "Run body-fn with run-tests! mocked to return a failing result."
  [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [_ & _opts] {:passed? false :test-count 3 :assertion-count 6
                                :fail-count 2 :error-count 0
                                :output "Ran 3 tests containing 6 assertions.\n2 failures, 0 errors."})}
      body-fn)))

;------------------------------------------------------------------------------ Layer 0: Defaults tests

(deftest default-config-test
  (testing "default config has correct structure"
    (is (nil? (:agent verify/default-config))
        "Verify phase has no agent — runs tests directly")
    (is (= [:pre-verify-lint :tests-pass :coverage] (:gates verify/default-config)))
    (is (map? (:budget verify/default-config)))
    (is (= 3 (get-in verify/default-config [:budget :iterations])))))

(deftest phase-defaults-registration-test
  (testing "verify phase defaults are registered"
    (let [defaults (registry/phase-defaults :verify)]
      (is (some? defaults))
      (is (nil? (:agent defaults))
          "Verify has no agent in the new environment model")
      (is (= [:pre-verify-lint :tests-pass :coverage] (:gates defaults))))))

;------------------------------------------------------------------------------ Layer 1: Interceptor enter tests

(deftest enter-verify-basic-test
  (testing "enter-verify sets up phase context and runs tests"
    (with-mocked-test-runner
      (fn []
        (let [ctx (assoc (create-base-context) :phase-config {:phase :verify})
              result (verify/enter-verify ctx)]

          (testing "phase metadata is set"
            (is (= :verify (get-in result [:phase :name])))
            (is (nil? (get-in result [:phase :agent]))
                "No agent in new environment model")
            (is (= [:pre-verify-lint :tests-pass :coverage] (get-in result [:phase :gates])))
            (is (= :running (get-in result [:phase :status])))
            (is (number? (get-in result [:phase :started-at]))))

          (testing "budget is set from defaults"
            (is (= 3 (get-in result [:phase :budget :iterations]))))

          (testing "result carries test metrics in new environment model shape"
            (is (= :success (get-in result [:phase :result :status])))
            (is (some? (get-in result [:phase :result :environment-id]))
                "Result references the execution environment-id")
            (is (string? (get-in result [:phase :result :summary]))
                "Result carries a human-readable summary")
            (is (pos? (get-in result [:phase :result :metrics :pass-count]))
                "Pass count captured in metrics")
            (is (= 0 (get-in result [:phase :result :metrics :fail-count]))
                "Zero failures captured in metrics")
            (is (string? (get-in result [:phase :result :metrics :test-output]))
                "Test output string captured in metrics for evidence bundle")))))))

(deftest enter-verify-failing-tests-test
  (testing "enter-verify sets :error status when tests fail"
    (with-failing-test-runner
      (fn []
        (let [ctx (assoc (create-base-context) :phase-config {:phase :verify})
              result (verify/enter-verify ctx)]
          (is (= :error (get-in result [:phase :result :status])))
          (is (some? (get-in result [:phase :result :error :message])))
          ;; Fail count captured in metrics for implement-retry loop
          (is (pos? (get-in result [:phase :result :metrics :fail-count]))
              "Fail count captured in metrics when tests fail"))))))

(deftest enter-verify-fails-fast-without-environment-test
  (testing "enter-verify throws when :execution/environment-id is absent"
    (let [ctx (-> (create-base-context)
                  (dissoc :execution/environment-id)
                  (assoc :phase-config {:phase :verify}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            (verify/enter-verify ctx))))))

(deftest enter-verify-uses-execution-worktree-path-test
  (testing "enter-verify passes :execution/worktree-path to test runner"
    (let [captured-path (atom nil)
          run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
      (with-redefs-fn
        {run-var (fn [path & _opts]
                   (reset! captured-path path)
                   {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
        (fn []
          (let [ctx (-> (create-base-context)
                        (assoc :execution/worktree-path "/tmp/my-worktree")
                        (assoc :phase-config {:phase :verify}))]
            (verify/enter-verify ctx)
            (is (= "/tmp/my-worktree" @captured-path))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase.verify-test)
  :leave-this-here)
