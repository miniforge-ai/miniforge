(ns ai.miniforge.phase.verify-failure-modes-test
  "Additional tests for verify phase failure modes and artifact validation.
  
  Extends the existing verify_test.clj with comprehensive failure mode testing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.phase.verify] ;; registers :verify defmethod
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Test Fixtures

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/example.clj"
                 :content "(ns example)\n(defn hello [] \"world\")"
                 :action :create}]
   :code/language "clojure"})

(def mock-empty-artifact
  {:code/id (random-uuid)
   :code/files []
   :code/language "clojure"})

(def mock-test-result
  {:test/id (random-uuid)
   :test/passed? true
   :test/coverage {:lines 85.0}})

(defn- create-base-context
  "Create base context for testing."
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test task"
                     :title "Test"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}})

;------------------------------------------------------------------------------ Artifact Validation Tests

(deftest verify-succeeds-with-artifact-present-test
  (testing "verify phase succeeds when code artifact is present"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_agent _task _ctx]
                                (response/success mock-test-result
                                                {:tokens 200 :duration-ms 600}))]
      (let [ctx (-> (create-base-context)
                   (assoc-in [:execution/phase-results :implement]
                            {:result {:status :success
                                     :output mock-code-artifact}})
                   (assoc :phase-config {:phase :verify}))
            interceptor (registry/get-phase-interceptor {:phase :verify})
            result ((:enter interceptor) ctx)]
        
        (is (= :success (get-in result [:phase :result :status]))
            "Verify should succeed with artifact present")
        
        (is (some? (get-in result [:phase :result :output]))
            "Should return test results")))))

(deftest verify-handles-empty-artifact-test
  (testing "verify phase handles empty artifact (zero files)"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_agent _task _ctx]
                                (response/success {:test/passed? true
                                                 :test/note "No code to test"}
                                                {:tokens 50 :duration-ms 100}))]
      (let [ctx (-> (create-base-context)
                   (assoc-in [:execution/phase-results :implement]
                            {:result {:status :success
                                     :output mock-empty-artifact}})
                   (assoc :phase-config {:phase :verify}))
            interceptor (registry/get-phase-interceptor {:phase :verify})
            result ((:enter interceptor) ctx)]
        
        ;; Phase should complete (agent decides how to handle empty artifact)
        (is (some? (get-in result [:phase :result]))
            "Phase should return a result")))))

(deftest verify-with-missing-implement-result-test
  (testing "verify phase fails fast when implement phase result is completely missing"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_agent _task _ctx]
                                (response/success {:test/passed? true}
                                                {:tokens 10 :duration-ms 50}))]
      (let [ctx (-> (create-base-context)
                   ;; NO implement phase result at all
                   (assoc :phase-config {:phase :verify}))
            interceptor (registry/get-phase-interceptor {:phase :verify})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Verify phase received no code artifact"
                              ((:enter interceptor) ctx))
            "Verify should throw when no code artifact is available")))))

(deftest verify-descriptive-error-on-agent-failure-test
  (testing "verify phase provides descriptive error when agent fails"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_ _ _]
                                (throw (ex-info "Test generation failed: syntax error in code"
                                               {:reason :syntax-error
                                                :file "src/example.clj"
                                                :line 42})))]
      (let [ctx (-> (create-base-context)
                   (assoc-in [:execution/phase-results :implement]
                            {:result {:status :success
                                     :output mock-code-artifact}})
                   (assoc :phase-config {:phase :verify}))
            interceptor (registry/get-phase-interceptor {:phase :verify})
            result ((:enter interceptor) ctx)]
        
        (is (= false (get-in result [:phase :result :success]))
            "Result should indicate failure")
        
        (is (some? (get-in result [:phase :result :error :message]))
            "Error message should be present")
        
        (is (= "Test generation failed: syntax error in code"
               (get-in result [:phase :result :error :message]))
            "Error message should be descriptive")
        
        (is (= :syntax-error (get-in result [:phase :result :error :data :reason]))
            "Error data should be preserved")))))

(deftest verify-captures-test-failures-test
  (testing "verify phase captures test failures from agent"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_ _ _]
                                (response/success {:test/id (random-uuid)
                                                 :test/passed? false
                                                 :test/failures [{:test "test-feature"
                                                                 :expected true
                                                                 :actual false
                                                                 :message "Feature not working"}]}
                                                {:tokens 150 :duration-ms 400}))]
      (let [ctx (-> (create-base-context)
                   (assoc-in [:execution/phase-results :implement]
                            {:result {:status :success
                                     :output mock-code-artifact}})
                   (assoc :phase-config {:phase :verify}))
            interceptor (registry/get-phase-interceptor {:phase :verify})
            result ((:enter interceptor) ctx)]
        
        (is (= :success (get-in result [:phase :result :status]))
            "Phase should succeed even with test failures")
        
        (let [test-result (get-in result [:phase :result :output])]
          (is (false? (:test/passed? test-result))
              "Test result should indicate failure")
          (is (seq (:test/failures test-result))
              "Failures should be captured"))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.verify-failure-modes-test)
  :leave-this-here)