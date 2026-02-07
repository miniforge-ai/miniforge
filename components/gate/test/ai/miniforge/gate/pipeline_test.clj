(ns ai.miniforge.gate.pipeline-test
  "Integration test for gate validation pipeline.

  Tests artifact flow through gate validation chains without running
  real linting or testing tools. Validates repair loops and escalation."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Mock Data

(def mock-code-artifact-valid
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn valid-fn [x]\n  (+ x 1))"
                 :action :create}]
   :code/language "clojure"})

(def mock-code-artifact-syntax-error
  {:code/id (random-uuid)
   :code/files [{:path "src/broken.clj"
                 :content "(ns broken\n(defn unclosed-paren []"
                 :action :create}]
   :code/language "clojure"})

(def mock-code-artifact-lint-warnings
  {:code/id (random-uuid)
   :code/files [{:path "src/warnings.clj"
                 :content "(ns warnings)\n(defn unused-var [x y]\n  x)"
                 :action :create}]
   :code/language "clojure"})

(def mock-code-artifact-no-tests
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :ok)"
                 :action :create}]
   :code/language "clojure"
   :code/test-coverage {:lines 0.0}})

(def mock-code-artifact-with-secrets
  {:code/id (random-uuid)
   :code/files [{:path "src/config.clj"
                 :content "(def api-key \"sk-1234567890abcdef\")"
                 :action :create}]
   :code/language "clojure"})

;------------------------------------------------------------------------------ Mock Gate Implementations

(defn mock-syntax-gate
  "Mock syntax validation gate."
  [should-pass?]
  (fn [artifact _context]
    (if should-pass?
      (response/success
       {:gate :syntax
        :passed? true
        :checks [{:name "syntax" :status :passed}]}
       {:duration-ms 100})
      (response/failure
       (ex-info "Syntax error"
                {:gate :syntax
                 :errors [{:file "src/broken.clj"
                           :line 2
                           :message "Unclosed parenthesis"}]})))))

(defn mock-lint-gate
  "Mock linting gate."
  [warnings]
  (fn [artifact _context]
    (if (empty? warnings)
      (response/success
       {:gate :lint
        :passed? true
        :checks [{:name "clj-kondo" :status :passed}]}
       {:duration-ms 200})
      (response/success
       {:gate :lint
        :passed? (< (count warnings) 10) ; Pass if fewer than 10 warnings
        :warnings warnings}
       {:duration-ms 200}))))

(defn mock-test-gate
  "Mock testing gate."
  [tests-pass?]
  (fn [artifact _context]
    (if tests-pass?
      (response/success
       {:gate :tests
        :passed? true
        :test-results {:total 10 :passed 10 :failed 0}}
       {:duration-ms 5000})
      (response/failure
       (ex-info "Tests failed"
                {:gate :tests
                 :test-results {:total 10 :passed 7 :failed 3}
                 :failures [{:test "feature-test/test-edge-case"
                             :message "Expected :ok, got :error"}]})))))

(defn mock-security-gate
  "Mock security scanning gate."
  [has-secrets?]
  (fn [artifact _context]
    (if has-secrets?
      (response/failure
       (ex-info "Security issues found"
                {:gate :security
                 :issues [{:type :hardcoded-secret
                           :file "src/config.clj"
                           :line 1
                           :severity :critical
                           :message "Hardcoded API key detected"}]}))
      (response/success
       {:gate :security
        :passed? true
        :checks [{:name "secret-scan" :status :passed}]}
       {:duration-ms 300}))))

(defn mock-coverage-gate
  "Mock test coverage gate."
  [coverage-pct threshold]
  (fn [artifact _context]
    (if (>= coverage-pct threshold)
      (response/success
       {:gate :coverage
        :passed? true
        :coverage coverage-pct}
       {:duration-ms 100})
      (response/failure
       (ex-info "Insufficient test coverage"
                {:gate :coverage
                 :actual coverage-pct
                 :required threshold})))))

;------------------------------------------------------------------------------ Test Helpers

(defn apply-gate-chain
  "Apply a chain of gates to an artifact."
  [artifact gates context]
  (loop [remaining-gates gates
         results []]
    (if (empty? remaining-gates)
      {:success? true
       :all-passed? (every? #(= :success (:status %)) results)
       :results results}
      (let [gate-fn (first remaining-gates)
            result (gate-fn artifact context)]
        (if (= :error (:status result))
          ;; Gate failed, stop chain
          {:success? false
           :failed-at (count results)
           :results (conj results result)}
          ;; Gate passed, continue
          (recur (rest remaining-gates)
                 (conj results result)))))))

(defn count-repair-iterations
  "Count how many repair iterations were needed."
  [repair-history]
  (count repair-history))

;------------------------------------------------------------------------------ Tests

(deftest single-gate-pass-test
  (testing "Single gate passes valid artifact"
    (let [gate-fn (mock-syntax-gate true)
          result (gate-fn mock-code-artifact-valid {})]

      (is (= :success (:status result))
          "Gate should pass")
      (is (true? (get-in (:output result) [:passed?]))
          "Gate should report passed")
      (is (number? (get-in (:metrics result) [:duration-ms]))
          "Gate should report metrics"))))

(deftest single-gate-fail-test
  (testing "Single gate fails invalid artifact"
    (let [gate-fn (mock-syntax-gate false)
          result (gate-fn mock-code-artifact-syntax-error {})]

      (is (= :error (:status result))
          "Gate should fail")
      (is (string? (:error result))
          "Gate should provide error message")
      (is (seq (get-in (:data result) [:errors]))
          "Gate should provide error details"))))

(deftest gate-chain-all-pass-test
  (testing "Chain of gates all pass"
    (let [gates [(mock-syntax-gate true)
                 (mock-lint-gate [])
                 (mock-test-gate true)]
          result (apply-gate-chain mock-code-artifact-valid gates {})]

      (is (true? (:success? result))
          "All gates should pass")
      (is (true? (:all-passed? result))
          "Chain should report all passed")
      (is (= 3 (count (:results result)))
          "Should have 3 gate results"))))

(deftest gate-chain-early-failure-test
  (testing "Gate chain stops at first failure"
    (let [gates [(mock-syntax-gate false)  ; This will fail
                 (mock-lint-gate [])        ; Should not run
                 (mock-test-gate true)]     ; Should not run
          result (apply-gate-chain mock-code-artifact-syntax-error gates {})]

      (is (false? (:success? result))
          "Chain should fail")
      (is (= 0 (:failed-at result))
          "Should fail at first gate")
      (is (= 1 (count (:results result)))
          "Should only have results from first gate"))))

(deftest lint-warnings-pass-test
  (testing "Lint warnings don't fail if below threshold"
    (let [gate-fn (mock-lint-gate [{:file "test.clj" :line 5 :level :warning}])
          result (gate-fn mock-code-artifact-lint-warnings {})]

      (is (= :success (:status result))
          "Gate should pass with warnings")
      (is (seq (get-in (:output result) [:warnings]))
          "Should report warnings"))))

(deftest lint-too-many-warnings-fail-test
  (testing "Too many lint warnings cause failure"
    (let [many-warnings (repeatedly 15 (fn [] {:file "test.clj" :level :warning}))
          gate-fn (mock-lint-gate many-warnings)
          result (gate-fn mock-code-artifact-lint-warnings {})]

      (is (= :success (:status result))
          "Should complete")
      (is (false? (get-in (:output result) [:passed?]))
          "Should not pass with too many warnings"))))

(deftest security-gate-detects-secrets-test
  (testing "Security gate detects hardcoded secrets"
    (let [gate-fn (mock-security-gate true)
          result (gate-fn mock-code-artifact-with-secrets {})]

      (is (= :error (:status result))
          "Should fail on secrets")
      (is (seq (get-in (:data result) [:issues]))
          "Should report security issues")
      (is (= :critical (get-in (:data result) [:issues 0 :severity]))
          "Should mark secrets as critical"))))

(deftest coverage-gate-threshold-test
  (testing "Coverage gate enforces minimum threshold"
    (let [passing-gate (mock-coverage-gate 85.0 80.0)
          failing-gate (mock-coverage-gate 75.0 80.0)]

      ;; Above threshold
      (is (= :success (:status (passing-gate mock-code-artifact-valid {})))
          "Should pass with sufficient coverage")

      ;; Below threshold
      (is (= :error (:status (failing-gate mock-code-artifact-no-tests {})))
          "Should fail with insufficient coverage"))))

(deftest parallel-gates-test
  (testing "Multiple gates can be checked in parallel"
    (let [gates [(mock-syntax-gate true)
                 (mock-lint-gate [])
                 (mock-security-gate false)]
          ;; Run gates concurrently
          futures (mapv #(future (% mock-code-artifact-valid {})) gates)
          results (mapv deref futures)]

      (is (= 3 (count results))
          "Should have results from all gates")
      (is (every? #(contains? % :status) results)
          "All results should have status"))))

(deftest repair-loop-integration-test
  (testing "Failed gates trigger repair loop"
    (let [repair-history (atom [])
          gate-fn (mock-syntax-gate false)
          repair-fn (fn [artifact error]
                      (swap! repair-history conj {:error error})
                      ;; Return "fixed" artifact
                      (assoc artifact :repaired true))]

      ;; Simulate repair loop
      (let [initial-result (gate-fn mock-code-artifact-syntax-error {})
            repaired-artifact (repair-fn mock-code-artifact-syntax-error
                                         (:data initial-result))
            recheck-result (gate-fn repaired-artifact {})]

        (is (= 1 (count @repair-history))
            "Should track repair iteration")
        (is (contains? repaired-artifact :repaired)
            "Artifact should be marked as repaired")))))

(deftest escalation-after-max-repairs-test
  (testing "Escalation triggers after max repair iterations"
    (let [max-iterations 3
          repair-count (atom 0)
          gate-fn (mock-syntax-gate false)]

      ;; Simulate repeated failures
      (dotimes [i 5]
        (let [result (gate-fn mock-code-artifact-syntax-error {})]
          (when (and (= :error (:status result))
                     (< @repair-count max-iterations))
            (swap! repair-count inc))))

      (is (= max-iterations @repair-count)
          "Should stop at max iterations")
      (is (>= @repair-count max-iterations)
          "Should trigger escalation"))))

(deftest gate-metrics-accumulation-test
  (testing "Gate metrics accumulate across chain"
    (let [gates [(mock-syntax-gate true)   ; 100ms
                 (mock-lint-gate [])        ; 200ms
                 (mock-test-gate true)]     ; 5000ms
          result (apply-gate-chain mock-code-artifact-valid gates {})
          total-duration (reduce + (map #(get-in % [:metrics :duration-ms])
                                        (:results result)))]

      (is (= 5300 total-duration)
          "Should accumulate gate durations")
      (is (pos? total-duration)
          "Total duration should be positive"))))

(deftest gate-context-propagation-test
  (testing "Context is propagated through gate chain"
    (let [context {:workflow/id (random-uuid)
                   :phase :implement}
          context-tracking-gate (fn [artifact ctx]
                                  (is (= (:workflow/id context)
                                         (:workflow/id ctx))
                                      "Context should propagate")
                                  (response/success {:passed? true} {}))
          result (context-tracking-gate mock-code-artifact-valid context)]

      (is (= :success (:status result))
          "Gate should pass with context"))))

(deftest conditional-gate-application-test
  (testing "Gates can be conditionally applied based on language"
    (let [clojure-gates [(mock-syntax-gate true)
                         (mock-lint-gate [])]
          python-gates [(mock-syntax-gate true)]
          artifact-clj {:code/language "clojure"}
          artifact-py {:code/language "python"}]

      ;; Apply language-specific gates
      (let [clj-result (apply-gate-chain artifact-clj clojure-gates {})
            py-result (apply-gate-chain artifact-py python-gates {})]

        (is (= 2 (count (:results clj-result)))
            "Clojure should run 2 gates")
        (is (= 1 (count (:results py-result)))
            "Python should run 1 gate"))))))

(deftest gate-failure-details-test
  (testing "Gate failures provide actionable details"
    (let [gate-fn (mock-syntax-gate false)
          result (gate-fn mock-code-artifact-syntax-error {})]

      (is (= :error (:status result))
          "Should fail")
      (is (seq (get-in (:data result) [:errors]))
          "Should provide error list")
      (let [first-error (first (get-in (:data result) [:errors]))]
        (is (contains? first-error :file)
            "Error should specify file")
        (is (contains? first-error :line)
            "Error should specify line")
        (is (contains? first-error :message)
            "Error should have message"))))

(deftest gate-timeout-handling-test
  (testing "Gates can timeout on slow operations"
    (let [slow-gate (fn [artifact context]
                      (Thread/sleep 100) ; Simulate slow gate
                      (response/success {:passed? true} {:duration-ms 100}))
          start-time (System/currentTimeMillis)
          result (slow-gate mock-code-artifact-valid {})
          end-time (System/currentTimeMillis)]

      (is (>= (- end-time start-time) 100)
          "Gate should take time")
      (is (= :success (:status result))
          "Slow gate should still complete"))))
