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

(ns ai.miniforge.phase.implement-test
  "Unit tests for the implement phase interceptor.
  
  Tests artifact creation, validation, and error handling."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.implement :as implement]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Test Fixtures

(def mock-plan-result
  {:plan/id (random-uuid)
   :plan/tasks [{:task "Implement feature X"
                 :file "src/feature.clj"}]})

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}]
   :code/language "clojure"
   :code/summary "Implemented feature X"})

(def mock-empty-artifact
  {:code/id (random-uuid)
   :code/files []
   :code/language "clojure"
   :code/summary "No code generated"})

(defn create-base-context
  "Create minimal execution context for testing."
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test implementation"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:plan {:result {:status :success
                                            :output mock-plan-result}}}})

;------------------------------------------------------------------------------ Layer 0: Defaults Tests

(deftest default-config-test
  (testing "implement phase has correct default configuration"
    (is (= :implementer (:agent implement/default-config)))
    (is (= [:syntax :lint] (:gates implement/default-config)))
    (is (map? (:budget implement/default-config)))
    (is (= 30000 (get-in implement/default-config [:budget :tokens])))
    (is (= 8 (get-in implement/default-config [:budget :iterations])))
    (is (= 600 (get-in implement/default-config [:budget :time-seconds])))))

(deftest phase-defaults-registration-test
  (testing "implement phase defaults are registered"
    (let [defaults (registry/phase-defaults :implement)]
      (is (some? defaults))
      (is (= :implementer (:agent defaults)))
      (is (= [:syntax :lint] (:gates defaults))))))

;------------------------------------------------------------------------------ Layer 1: Interceptor Enter Tests

(deftest enter-implement-returns-artifact-test
  (testing "implement phase returns code artifact in result"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success mock-code-artifact
                                                {:tokens 1000 :duration-ms 2000}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (registry/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)]
        
        (testing "phase metadata is set"
          (is (= :implement (get-in result [:phase :name])))
          (is (= :implementer (get-in result [:phase :agent])))
          (is (= :running (get-in result [:phase :status]))))
        
        (testing "artifact is present in result"
          (is (some? (get-in result [:phase :result :output]))
              "Result should contain output")
          
          (let [artifact (get-in result [:phase :result :output])]
            (is (some? (:code/id artifact))
                "Artifact should have code/id")
            (is (some? (:code/files artifact))
                "Artifact should have code/files")
            (is (= "clojure" (:code/language artifact))
                "Artifact should specify language")))))))

(deftest artifact-contains-code-files-test
  (testing "artifact contains :code/files with proper structure"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success mock-code-artifact
                                                {:tokens 800 :duration-ms 1500}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (registry/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)
            files (get-in result [:phase :result :output :code/files])]
        
        (is (vector? files)
            "code/files should be a vector")
        (is (pos? (count files))
            "code/files should not be empty")
        
        (doseq [file files]
          (is (contains? file :path)
              "Each file should have :path")
          (is (contains? file :content)
              "Each file should have :content")
          (is (contains? file :action)
              "Each file should have :action")
          (is (#{:create :modify :delete} (:action file))
              "Action should be valid keyword"))))))

(deftest implement-reads-plan-from-context-test
  (testing "implement phase reads plan from execution phase results"
    (let [plan-read-atom (atom nil)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke (fn [_agent task _ctx]
                                  ;; Capture what plan was passed to agent
                                  (reset! plan-read-atom (:task/plan task))
                                  (response/success mock-code-artifact
                                                  {:tokens 500 :duration-ms 1000}))]
        (let [ctx (create-base-context)
              ctx-with-config (assoc ctx :phase-config {:phase :implement})
              interceptor (registry/get-phase-interceptor {:phase :implement})
              _result ((:enter interceptor) ctx-with-config)]
          
          ;; Verify plan was read from context and passed to agent
          (is (some? @plan-read-atom)
              "Agent should receive plan from context")
          (is (= (:plan/id mock-plan-result) (:plan/id @plan-read-atom))
              "Agent should receive the correct plan"))))))

(deftest implement-handles-empty-artifact-test
  (testing "implement phase handles empty artifact (no code generated)"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success mock-empty-artifact
                                                {:tokens 200 :duration-ms 500}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (registry/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)]
        
        ;; Phase should complete even with empty artifact
        (is (= :success (get-in result [:phase :result :status]))
            "Phase should succeed even with empty artifact")
        
        (let [files (get-in result [:phase :result :output :code/files])]
          (is (empty? files)
              "Files should be empty for empty artifact"))))))

(deftest implement-handles-agent-exception-test
  (testing "implement phase handles agent exceptions gracefully"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (throw (ex-info "Implementation failed"
                                               {:reason :llm-timeout})))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (registry/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)]
        
        (is (= false (get-in result [:phase :result :success]))
            "Result should indicate failure")
        
        (is (some? (get-in result [:phase :result :error]))
            "Error should be captured in result")
        
        (is (= "Implementation failed" 
               (get-in result [:phase :result :error :message]))
            "Error message should be preserved")))))

;------------------------------------------------------------------------------ Layer 2: Interceptor Leave Tests

(deftest leave-implement-records-metrics-test
  (testing "leave-implement records duration and token metrics"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success mock-code-artifact
                                                {:tokens 1500 :duration-ms 3000}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (registry/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        
        (is (= :completed (get-in final-result [:phase :status]))
            "Phase status should be completed")
        
        (is (number? (get-in final-result [:phase :duration-ms]))
            "Duration should be recorded")
        
        (is (= 1500 (get-in final-result [:phase :metrics :tokens]))
            "Token metrics should be recorded")

        (is (= (* 1500 0.000015) (get-in final-result [:phase :metrics :cost-usd]))
            "Cost-usd should be estimated from token count")

        (is (= (* 1500 0.000015) (get-in final-result [:execution/metrics :cost-usd]))
            "Cost-usd should be merged into execution metrics")

        (is (= :implement (first (get-in final-result [:execution :phases-completed])))
            "Implement should be added to phases-completed")))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.implement-test)
  :leave-this-here)