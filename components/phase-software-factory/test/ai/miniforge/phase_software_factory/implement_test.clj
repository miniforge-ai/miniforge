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

(ns ai.miniforge.phase-software-factory.implement-test
  "Unit tests for the implement phase interceptor.
  
  Tests artifact creation, validation, and error handling."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase-software-factory.implement :as implement]
   [ai.miniforge.phase.interface :as phase]
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
   :execution/environment-id (random-uuid)
   :execution/worktree-path "/tmp/test-worktree"
   :execution/input {:description "Test implementation"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:plan {:result {:status :success
                                            :output mock-plan-result}}}})

(defn mock-curator-success
  "Mock for agent/curate-implement-output that returns success.

   The real curator inspects the executor environment for written files;
   tests that don't actually write files need to bypass that inspection."
  [{:keys [implementer-result]}]
  (response/success (:output implementer-result)
                    {:metrics (:metrics implementer-result)}))

(defn mock-curator-error
  "Mock for agent/curate-implement-output that returns the same error
   the implementer produced — used by tests verifying exception/error
   propagation without coupling to the curator's no-files verdict."
  [{:keys [implementer-result]}]
  (let [err (:error implementer-result)]
    (response/error (or (:message err) "Implementation failed")
                    {:data (or (:data err) {})})))

;------------------------------------------------------------------------------ Layer 0: Defaults Tests

(deftest default-config-test
  (testing "implement phase has correct default configuration"
    (is (= :implementer (:agent implement/default-config)))
    (is (= [:syntax :format :lint] (:gates implement/default-config)))
    (is (map? (:budget implement/default-config)))
    (is (= 30000 (get-in implement/default-config [:budget :tokens])))
    (is (= 8 (get-in implement/default-config [:budget :iterations])))
    (is (= 600 (get-in implement/default-config [:budget :time-seconds])))))

(deftest phase-defaults-registration-test
  (testing "implement phase defaults are registered"
    (let [defaults (phase/phase-defaults :implement)]
      (is (some? defaults))
      (is (= :implementer (:agent defaults)))
      (is (= [:syntax :format :lint] (:gates defaults))))))

;------------------------------------------------------------------------------ Layer 1: Interceptor Enter Tests

(deftest enter-implement-sets-phase-metadata-test
  (testing "implement phase sets correct phase metadata"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens 1000 :duration-ms 2000}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)]

        (is (= :implement (get-in result [:phase :name])))
        (is (= :implementer (get-in result [:phase :agent])))
        (is (= :running (get-in result [:phase :status])))))))

(deftest leave-implement-result-contains-environment-id-test
  (testing "leave-implement stores environment-id in result, not serialized :code/files"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens 800 :duration-ms 1500}))
                  agent/curate-implement-output mock-curator-success]
      (let [env-id (random-uuid)
            ctx (-> (create-base-context)
                    (assoc :execution/environment-id env-id))
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]

        (is (= :completed (get-in final-result [:phase :status]))
            "Phase should complete when agent returns success with nil output")
        (is (= env-id (get-in final-result [:phase :result :environment-id]))
            "Result should reference the environment-id")
        (is (nil? (get-in final-result [:phase :result :output]))
            "Result should NOT contain serialized :output / :code/files")))))

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
              interceptor (phase/get-phase-interceptor {:phase :implement})
              _result ((:enter interceptor) ctx-with-config)]
          
          ;; Verify plan was read from context and passed to agent
          (is (some? @plan-read-atom)
              "Agent should receive plan from context")
          (is (= (:plan/id mock-plan-result) (:plan/id @plan-read-atom))
              "Agent should receive the correct plan"))))))

(deftest implement-handles-nil-output-test
  (testing "implement phase treats nil agent output as success — code is in the environment"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens 200 :duration-ms 500}))
                  agent/curate-implement-output mock-curator-success]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]

        (is (= :completed (get-in final-result [:phase :status]))
            "Phase should complete — nil output means code is in environment, not an error")
        (is (= :success (get-in final-result [:phase :result :status]))
            "Result status should be :success")))))

(deftest implement-marks-curator-recovered-errors-as-degraded-handoff-test
  (testing "curator recovery after an implementer-side error is marked as a degraded handoff"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/error "LLM output parse failed"
                                                 {:tokens 50 :duration-ms 250}))
                  agent/curate-implement-output
                  (fn [_]
                    (response/success {:code/files [{:path "src/core.clj"
                                                     :content "(ns core)"
                                                     :action :create}]
                                       :code/summary "curated recovery"}
                                      {:metrics {:tokens 25 :duration-ms 50}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        (is (= :completed (get-in final-result [:phase :status])))
        (is (true? (get-in enter-result [:phase :result :degraded-handoff?]))
            "Recovered curator output must mark the handoff as degraded")
        (is (true? (get-in enter-result [:phase :result :success?]))
            "Recovered curator output must normalize the result to success for downstream phase accounting")
        (is (true? (get-in final-result [:phase :artifact :code/degraded-handoff?])))
        (is (= :error (get-in final-result [:phase :artifact :code/raw-agent-status])))))))

(deftest implement-handles-agent-exception-test
  (testing "implement phase handles agent exceptions gracefully"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (throw (ex-info "Implementation failed"
                                               {:reason :llm-timeout})))
                  agent/curate-implement-output mock-curator-error]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            result ((:enter interceptor) ctx-with-config)]
        
        ;; impl-result wins because curator's mock doesn't set :curator/no-files-written
        ;; terminal code, so the cond falls through to (not (succeeded? impl-result)).
        (is (= false (get-in result [:phase :result :success]))
            "Result should indicate failure (response/failure shape from caught exception)")

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
                                                {:tokens 1500 :duration-ms 3000}))
                  agent/curate-implement-output mock-curator-success]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
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

(deftest leave-implement-preserves-curated-artifact-test
  (testing "successful implement preserves curated artifact on the outer phase map for downstream review"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/success nil {:tokens 200 :duration-ms 500}))
                  agent/curate-implement-output
                  (fn [_]
                    (response/success {:code/files [{:path "src/core.clj"
                                                     :content "(ns core)"
                                                     :action :create}]
                                       :code/summary "curated artifact"
                                       :code/scope-deviations []}
                                      {:metrics {:tokens 200 :duration-ms 500}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        (is (= "curated artifact" (get-in final-result [:phase :artifact :code/summary])))
        (is (= ["src/core.clj"] (get-in final-result [:phase :artifact :code/file-paths])))
        (is (= :success (get-in final-result [:phase :result :status]))
            "The persisted phase result stays lightweight")
        (is (nil? (get-in final-result [:phase :result :output]))
            "Serialized code does not go back into the environment-model result")
        (is (nil? (get-in final-result [:phase :artifact :code/files]))
            "Serialized code should not be persisted on the outer phase artifact")))))

(deftest leave-implement-does-not-count-failed-phase-as-completed-test
  (testing "failed implement does not append to :execution/phases-completed"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))
                  agent/curate-implement-output mock-curator-error]
      (let [ctx (-> (create-base-context)
                    (assoc-in [:phase :iterations] 8))
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        (is (= :failed (get-in final-result [:phase :status])))
        (is (empty? (get-in final-result [:execution :phases-completed] []))
            "Failed implement must not be counted as completed")))))

;------------------------------------------------------------------------------ Layer 3: Capsule File Loading

(deftest load-files-from-capsule-reads-via-execute-fn-test
  (testing "load-files-from-capsule reads files via execute-fn"
    (let [execute-fn (fn [_executor _env-id cmd _opts]
                       (cond
                         (= cmd "cat /workspace/src/core.clj")
                         {:data {:stdout "(ns core)" :exit-code 0}}

                         (= cmd "cat /workspace/src/util.clj")
                         {:data {:stdout "(ns util)" :exit-code 0}}

                         :else
                         {:data {:stdout "" :exit-code 1}}))
          result (#'implement/load-files-from-capsule
                  execute-fn :mock-executor :mock-env-id "/workspace"
                  ["src/core.clj" "src/util.clj"])]
      (is (= 2 (count result)))
      (is (= "src/core.clj" (:path (first result))))
      (is (= "(ns core)" (:content (first result))))
      (is (= "(ns util)" (:content (second result)))))))

(deftest load-files-from-capsule-skips-missing-files-test
  (testing "load-files-from-capsule skips files that don't exist in capsule"
    (let [execute-fn (fn [_executor _env-id cmd _opts]
                       (if (= cmd "cat /workspace/src/exists.clj")
                         {:data {:stdout "(ns exists)" :exit-code 0}}
                         {:data {:stdout "" :exit-code 1}}))
          result (#'implement/load-files-from-capsule
                  execute-fn :mock-executor :mock-env-id "/workspace"
                  ["src/exists.clj" "src/missing.clj"])]
      (is (= 1 (count result)))
      (is (= "src/exists.clj" (:path (first result)))))))

(deftest load-files-from-capsule-returns-nil-for-empty-scope-test
  (testing "load-files-from-capsule returns nil when no files in scope"
    (is (nil? (#'implement/load-files-from-capsule
               (fn [& _] nil) :x :y "/w" [])))))

(deftest resolve-existing-files-uses-capsule-in-governed-mode-test
  (testing "resolve-existing-files prefers capsule path when execute-fn is on context"
    (let [capsule-called (atom false)
          execute-fn (fn [_executor _env-id _cmd _opts]
                       (reset! capsule-called true)
                       {:data {:stdout "(ns capsule)" :exit-code 0}})
          ctx {:execution/execute-fn execute-fn
               :execution/executor :mock
               :execution/environment-id :mock-env}
          result (#'implement/resolve-existing-files
                  ctx nil "/workspace" ["src/a.clj"])]
      (is @capsule-called "Should have called execute-fn")
      (is (= 1 (count result))))))

;------------------------------------------------------------------------------ Rate-limit classifier (2026-04-17 widening)
;; Tests for `rate-limit-in-result?` — it's private so we access via #'.

(defn- rate-limit? [result]
  (#'implement/rate-limit-in-result? result))

(deftest rate-limit-classifier-detects-classic-keywords-test
  (testing "classic patterns from the narrow regex still match"
    (doseq [msg ["You've hit your limit"
                 "Rate-limit exceeded"
                 "Got HTTP 429 from backend"
                 "Quota exceeded"
                 "Your quota resets 2pm EST"]]
      (is (rate-limit? {:error {:message msg}})
          (str "should classify as rate-limit: " msg)))))

(deftest rate-limit-classifier-detects-widened-patterns-test
  (testing "patterns added 2026-04-17 after dogfood observation"
    (doseq [msg ["HTTP 503 Service Unavailable"
                 "Too Many Requests"
                 "Model is overloaded, please try again later"
                 "Backend at capacity"
                 "Request was throttled"
                 "You\u2019ve hit your usage limit"       ; curly apostrophe
                 "Please try again in 30 seconds"]]
      (is (rate-limit? {:error {:message msg}})
          (str "should classify as rate-limit: " msg)))))

(deftest rate-limit-classifier-ignores-unrelated-errors-test
  (testing "ordinary task errors do NOT match rate-limit patterns"
    (doseq [msg ["Syntax error at line 5"
                 "File not found: src/foo.clj"
                 "Test failed: expected 1 got 2"
                 nil]]
      (is (not (rate-limit? {:error {:message msg}}))
          (str "should NOT classify as rate-limit: " (pr-str msg))))))

(deftest rate-limit-classifier-flags-suspicious-short-termination-test
  (testing "curator no-files + <30s duration = infra failure (observed 2026-04-17)"
    ;; The motivating case: LLM backend returned empty content in ~4s; the
    ;; curator fast-failed with :curator/no-files-written. Classifier should
    ;; now route this through rate-limit handling (pause/resume), not terminate.
    (is (rate-limit?
         {:status :error
          :error {:message "Curator: implementer wrote no files to the environment"
                  :data {:code :curator/no-files-written}}
          :metrics {:duration-ms 4567}}))))

(deftest rate-limit-classifier-does-not-flag-legitimate-curator-failures-test
  (testing "curator no-files after a full-length attempt is a real task failure"
    ;; If the implementer ran for the full ~11 min and still wrote nothing,
    ;; that's a real task problem (prompt, scope, tooling) — not an infra hiccup.
    ;; Should NOT be classified as rate-limit; should fall through to terminal.
    (is (not (rate-limit?
              {:status :error
               :error {:message "Curator: implementer wrote no files to the environment"
                       :data {:code :curator/no-files-written}}
               :metrics {:duration-ms 660000}})))))  ; 11 min

(deftest rate-limit-classifier-does-not-flag-short-but-successful-test
  (testing "fast completion WITHOUT the curator no-files signal is not infra-suspect"
    (is (not (rate-limit? {:status :success
                           :metrics {:duration-ms 1000}})))))

(deftest rate-limit-classifier-zero-duration-does-not-flag-test
  (testing "missing/zero duration does not satisfy the short-termination heuristic"
    (is (not (rate-limit?
              {:status :error
               :error {:message "x" :data {:code :curator/no-files-written}}
               :metrics {:duration-ms 0}})))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.implement-test)
  :leave-this-here)
