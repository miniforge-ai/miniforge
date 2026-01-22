(ns ai.miniforge.agent.reviewer-test
  "Tests for the reviewer agent."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.loop.interface :as loop]))

;------------------------------------------------------------------------------ Test fixtures

(defn- passing-gate
  "Create a gate that always passes."
  [gate-id]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (loop/pass-result gate-id :test))))

(defn- failing-gate
  "Create a gate that always fails."
  [gate-id error-message]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (loop/fail-result gate-id :test
                                        [(loop/make-error :test-error error-message)]))))

(defn- warning-gate
  "Create a gate that passes but emits warnings."
  [gate-id warning-message]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (assoc (loop/pass-result gate-id :test)
                             :warnings [(loop/make-error :warning warning-message)]))))

(def sample-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content {:code/files [{:path "src/example.clj"
                                    :content "(ns example)\n(defn hello [] \"world\")"
                                    :action :create}]}})

;------------------------------------------------------------------------------ Core functionality tests

(deftest test-create-reviewer
  (testing "Create reviewer with default configuration"
    (let [reviewer (reviewer/create-reviewer)]
      (is (some? reviewer)
          "Should create reviewer instance")
      (is (= :reviewer (:role reviewer))
          "Role should be :reviewer")))

  (testing "Create reviewer with custom gates"
    (let [custom-gates [(passing-gate :gate1)
                        (passing-gate :gate2)]
          reviewer (reviewer/create-reviewer {:gates custom-gates})]
      (is (some? reviewer)
          "Should create reviewer with custom gates")))

  (testing "Create reviewer with strict mode"
    (let [reviewer (reviewer/create-reviewer {:strict true})]
      (is (some? reviewer)
          "Should create strict reviewer"))))

(deftest test-reviewer-invoke-all-pass
  (testing "Review with all gates passing"
    (let [gates [(passing-gate :gate1)
                 (passing-gate :gate2)
                 (passing-gate :gate3)]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (= :success (:status result))
          "Invocation should succeed")

      (let [review (:artifact result)]
        (is (= :approved (:review/decision review))
            "Should be approved when all gates pass")

        (is (= 3 (:review/gates-passed review))
            "Should have 3 passed gates")

        (is (= 0 (:review/gates-failed review))
            "Should have 0 failed gates")

        (is (= 3 (:review/gates-total review))
            "Should have 3 total gates")

        (is (empty? (:review/blocking-issues review))
            "Should have no blocking issues")

        (is (reviewer/approved? review)
            "approved? helper should return true")))))

(deftest test-reviewer-invoke-some-fail
  (testing "Review with some gates failing"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "Test failure")
                 (passing-gate :gate3)]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (= :success (:status result))
          "Invocation should succeed")

      (let [review (:artifact result)]
        (is (= :conditionally-approved (:review/decision review))
            "Should be conditionally approved by default")

        (is (= 2 (:review/gates-passed review))
            "Should have 2 passed gates")

        (is (= 1 (:review/gates-failed review))
            "Should have 1 failed gate")

        (is (not (reviewer/approved? review))
            "approved? should return false")

        (is (reviewer/conditionally-approved? review)
            "conditionally-approved? should return true")))))

(deftest test-reviewer-invoke-strict-mode
  (testing "Review with strict mode rejects on any failure"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "Test failure")]
          reviewer (reviewer/create-reviewer {:gates gates :strict true})
          result (core/invoke reviewer {} sample-artifact)]

      (let [review (:artifact result)]
        (is (= :rejected (:review/decision review))
            "Should be rejected in strict mode")

        (is (reviewer/rejected? review)
            "rejected? helper should return true")

        (is (seq (:review/blocking-issues review))
            "Should have blocking issues in strict mode")))))

(deftest test-reviewer-invoke-with-warnings
  (testing "Review with warnings but all passing"
    (let [gates [(passing-gate :gate1)
                 (warning-gate :gate2 "Minor issue")]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (let [review (:artifact result)]
        (is (= :approved (:review/decision review))
            "Should still be approved with warnings")

        (is (seq (:review/warnings review))
            "Should have warnings")

        (is (= 2 (:review/gates-passed review))
            "Both gates should pass")))))

(deftest test-reviewer-no-llm-usage
  (testing "Reviewer uses no tokens (no LLM)"
    (let [reviewer (reviewer/create-reviewer)
          result (core/invoke reviewer {} sample-artifact)]

      (is (= 0 (get-in result [:metrics :tokens]))
          "Should use 0 tokens - no LLM calls"))))

;------------------------------------------------------------------------------ Schema validation tests

(deftest test-validate-review-artifact
  (testing "Validate valid review artifact"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gate-results []
                  :review/summary "All checks passed"
                  :review/gates-passed 3
                  :review/gates-failed 0
                  :review/gates-total 3}
          validation (reviewer/validate-review-artifact review)]
      (is (:valid? validation)
          "Valid review should pass validation")))

  (testing "Validate invalid review artifact - missing required fields"
    (let [review {:review/decision :approved}
          validation (reviewer/validate-review-artifact review)]
      (is (not (:valid? validation))
          "Review missing required fields should fail validation")))

  (testing "Validate invalid review artifact - gate counts don't add up"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gate-results []
                  :review/summary "Test"
                  :review/gates-passed 2
                  :review/gates-failed 0
                  :review/gates-total 5}  ; 2 + 0 ≠ 5
          validation (reviewer/validate-review-artifact review)]
      (is (not (:valid? validation))
          "Review with incorrect gate counts should fail validation"))))

;------------------------------------------------------------------------------ Helper function tests

(deftest test-review-summary
  (testing "Get review summary"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gates-passed 3
                  :review/gates-failed 0
                  :review/gates-total 3
                  :review/blocking-issues []
                  :review/warnings []}
          summary (reviewer/review-summary review)]
      (is (= :approved (:decision summary)))
      (is (= 3 (:gates-passed summary)))
      (is (= 0 (:gates-failed summary)))
      (is (= 0 (:blocking-issues-count summary)))
      (is (= 0 (:warnings-count summary))))))

(deftest test-decision-helpers
  (testing "Decision helper functions"
    (let [approved {:review/decision :approved}
          rejected {:review/decision :rejected}
          conditional {:review/decision :conditionally-approved}]

      (is (reviewer/approved? approved))
      (is (not (reviewer/approved? rejected)))
      (is (not (reviewer/approved? conditional)))

      (is (reviewer/rejected? rejected))
      (is (not (reviewer/rejected? approved)))
      (is (not (reviewer/rejected? conditional)))

      (is (reviewer/conditionally-approved? conditional))
      (is (not (reviewer/conditionally-approved? approved)))
      (is (not (reviewer/conditionally-approved? rejected))))))

(deftest test-get-blocking-issues
  (testing "Extract blocking issues"
    (let [review {:review/blocking-issues ["Issue 1" "Issue 2"]}]
      (is (= ["Issue 1" "Issue 2"] (reviewer/get-blocking-issues review)))
      (is (empty? (reviewer/get-blocking-issues {}))))))

(deftest test-get-warnings
  (testing "Extract warnings"
    (let [review {:review/warnings ["Warning 1" "Warning 2"]}]
      (is (= ["Warning 1" "Warning 2"] (reviewer/get-warnings review)))
      (is (empty? (reviewer/get-warnings {}))))))

(deftest test-get-recommendations
  (testing "Extract recommendations"
    (let [review {:review/recommendations ["Fix A" "Fix B"]}]
      (is (= ["Fix A" "Fix B"] (reviewer/get-recommendations review)))
      (is (empty? (reviewer/get-recommendations {}))))))

;------------------------------------------------------------------------------ Integration tests

(deftest test-reviewer-with-real-gates
  (testing "Review with syntax gate"
    (let [gates [(loop/syntax-gate)]
          reviewer (reviewer/create-reviewer {:gates gates})
          valid-artifact {:artifact/type :code
                          :artifact/content {:code/files [{:path "src/valid.clj"
                                                           :content "(ns valid)\n(defn f [] 1)"
                                                           :action :create}]}}
          result (core/invoke reviewer {} valid-artifact)]

      (is (= :success (:status result)))
      ;; Note: actual gate behavior depends on loop implementation
      (is (some? (:artifact result))
          "Should return review artifact")))

  (testing "Review with multiple real gates"
    (let [gates [(loop/syntax-gate)
                 (loop/lint-gate)
                 (loop/policy-gate :security {:policies [:no-secrets]})]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (= :success (:status result)))
      (is (= 3 (get-in result [:artifact :review/gates-total]))
          "Should run all 3 gates"))))

;------------------------------------------------------------------------------ Edge case tests

(deftest test-reviewer-with-no-gates
  (testing "Review with no gates should approve"
    (let [reviewer (reviewer/create-reviewer {:gates []})
          result (core/invoke reviewer {} sample-artifact)]

      (let [review (:artifact result)]
        (is (= :approved (:review/decision review))
            "Should approve when no gates configured")
        (is (= 0 (:review/gates-total review)))))))

(deftest test-reviewer-with-gate-exception
  (testing "Review handles gate exceptions gracefully"
    (let [error-gate (loop/custom-gate :error-gate :test
                                        (fn [_artifact _context]
                                          (throw (Exception. "Gate crashed"))))
          reviewer (reviewer/create-reviewer {:gates [error-gate]})
          result (core/invoke reviewer {} sample-artifact)]

      (is (= :success (:status result))
          "Should succeed even when gate throws")

      (let [review (:artifact result)]
        ;; Gate exception should be captured and treated as failure
        (is (= 1 (:review/gates-failed review))
            "Exception should be treated as gate failure")))))

(deftest test-reviewer-metrics
  (testing "Reviewer returns proper metrics"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "fail")]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)
          metrics (:metrics result)]

      (is (= 1 (:gates-passed metrics)))
      (is (= 1 (:gates-failed metrics)))
      (is (= 2 (:gates-total metrics)))
      (is (number? (:duration-ms metrics)))
      (is (= 0 (:tokens metrics))
          "Should report 0 tokens"))))
