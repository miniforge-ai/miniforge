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
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-software-factory.implement :as implement]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]))

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

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

(deftest resolve-worktree-path-result-returns-anomaly-test
  (testing "missing implement worktree is available as anomaly data"
    (let [result (#'implement/resolve-worktree-path-result {:execution/id :test})]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :anomalies.phase/enter-failed (:anomaly/subtype result)))
      (is (= [:execution/id] (get-in result [:anomaly/data :ctx-keys]))))))

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

(deftest base-ref-candidates-include-resume-merge-base-ranges-test
  (testing "resumed workspaces diff from the original spec base to restored HEAD"
    (let [candidates (#'implement/base-ref-candidates
                      {:execution/environment-metadata {:base-sha "task-sha"
                                                        :base-branch "task-restored"}
                       :execution/opts {:resume-workspace {:branch "task-restored"}}
                       :execution/input {:context {:git-commit "spec-sha"
                                                   :git-branch "dogfood/source"}}})]
      (is (= ["spec-sha...HEAD"
              "refs/remotes/origin/dogfood/source...HEAD"
              "origin/dogfood/source...HEAD"
              "refs/heads/dogfood/source...HEAD"
              "task-sha"
              "refs/remotes/origin/task-restored"
              "origin/task-restored"
              "refs/heads/task-restored"]
             candidates)))))

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

(deftest leave-implement-tolerates-nil-tokens-metrics-test
  (testing "an agent result with an EXPLICIT nil :tokens (file-artifact fallback
            path) does not NPE in leave — tokens coerce to 0, verdict survives"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens nil :duration-ms 1200}))
                  agent/curate-implement-output mock-curator-success]
      (let [ctx (-> (create-base-context)
                    (assoc :execution/environment-id (random-uuid)))
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            ;; Pre-fix this threw a message-less NullPointerException.
            final-result ((:leave interceptor) enter-result)]
        (is (= :completed (get-in final-result [:phase :status])))
        (is (number? (get-in final-result [:execution/metrics :tokens]))
            "nil agent tokens accumulate as 0, not a throw")
        (is (number? (get-in final-result [:phase :metrics :cost-usd])))))))

(deftest leave-implement-boundary-coerces-raw-invalid-metrics-test
  (testing "metrics that bypass the response builders (raw nil :tokens) are
            caught at the phase-input boundary and coerced, not NPE'd"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens 10 :duration-ms 100}))
                  agent/curate-implement-output mock-curator-success]
      (let [ctx (-> (create-base-context)
                    (assoc :execution/environment-id (random-uuid)))
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            ;; Inject a raw, schema-invalid metrics map AFTER enter — simulates
            ;; a producer that did not go through response/success.
            tampered (assoc-in enter-result [:phase :result :metrics] {:tokens nil})
            final-result ((:leave interceptor) tampered)]
        (is (number? (get-in final-result [:execution/metrics :tokens]))
            "boundary coerced the invalid metrics; no NPE")
        (is (number? (get-in final-result [:phase :metrics :cost-usd]))))))
  (testing "a non-map :metrics (wrong shape entirely) is coerced, not thrown"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                (response/success nil {:tokens 10 :duration-ms 100}))
                  agent/curate-implement-output mock-curator-success]
      (let [ctx (assoc (create-base-context) :execution/environment-id (random-uuid))
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) (assoc ctx :phase-config {:phase :implement}))
            tampered (assoc-in enter-result [:phase :result :metrics] "not-a-map")
            final-result ((:leave interceptor) tampered)]
        (is (number? (get-in final-result [:execution/metrics :tokens]))
            "non-map metrics coerced to a valid shape; no throw")))))

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

(deftest implement-marks-curator-recovered-failure-shape-as-degraded-handoff-test
  (testing "curator recovery from a thrown-exception (response/failure :success false) handoff is still marked degraded"
    ;; response/error? must catch :success false (the shape produced when
    ;; agent/invoke throws and the wrapper synthesizes a failure response)
    ;; in addition to :status :error / :failed. A naive (= :error :status)
    ;; check would silently leave this handoff untagged.
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/failure "Implementation failed"
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
            enter-result ((:enter interceptor) ctx-with-config)]
        (is (true? (get-in enter-result [:phase :result :degraded-handoff?]))
            ":success false must still trigger the degraded-handoff flag")))))

(deftest implement-does-not-mark-metadata-only-mcp-submit-as-degraded-test
  (testing "curator recovery after a metadata-only MCP submit is a normal Codex handoff"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/error
                                  "LLM response could not be parsed as code artifact"
                                  {:data {:reject/reason :narrative-only-response
                                          :artifact-source :mcp}}))
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
        (is (not (get-in enter-result [:phase :result :degraded-handoff?]))
            "metadata-only MCP submit should not poison deterministic curator recovery")))))

(deftest implement-does-not-mark-already-implemented-as-degraded-handoff-test
  (testing "curator recovery alongside an :already-implemented agent verdict must NOT flag degraded-handoff"
    ;; :already-implemented is a deliberate skip — the agent correctly
    ;; recognised the work was completed in a prior turn. The curator's
    ;; per-session diff is structurally empty in that case, so any artifact
    ;; the curator surfaces is the prior turn's output, not a recovered
    ;; failure. Tagging it degraded would auto-reject every legitimate
    ;; repair iteration that recognized "already done."
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 {:status  :already-implemented
                                  :output  {:code/id (random-uuid)
                                            :code/files []
                                            :code/summary "prior turn satisfied the task"}
                                  :summary "prior turn satisfied the task"
                                  :metrics {:tokens 0 :duration-ms 0}})
                  agent/curate-implement-output
                  (fn [_]
                    (response/success {:code/files [{:path "src/core.clj"
                                                     :content "(ns core)"
                                                     :action :create}]
                                       :code/summary "curated recovery"}
                                      {:metrics {:tokens 0 :duration-ms 0}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)]
        (is (not (get-in enter-result [:phase :result :degraded-handoff?]))
            ":already-implemented must not set :degraded-handoff?")
        (is (nil? (get-in enter-result [:phase :result :raw-agent-status]))
            "no degraded-handoff means no :raw-agent-status sidecar")))))

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

(deftest leave-implement-handles-nil-start-time-test
  (testing "leave-implement does not NPE when resume left timing or iteration fields nil"
    (let [interceptor (phase/get-phase-interceptor {:phase :implement})
          ctx {:phase {:result {:status :error
                                :error {:message "agent failed"}}
                       :budget {:iterations 2}
                       :iterations nil}
               :execution/metrics {:tokens 0 :duration-ms 0}}
          result ((:leave interceptor) ctx)]
      (is (some? result) "leave-implement should not throw")
      (is (= 0 (get-in result [:phase :duration-ms]))
          "Duration should default to 0 when start-time is nil")
      (is (= 0 (get-in result [:metrics :implementation :repair-cycles]))
          "Nil iterations should fall back to first-attempt metrics"))))

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

(deftest leave-implement-rejects-already-implemented-after-review-feedback-test
  (testing "already-implemented after review feedback fails closed instead of skipping"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 {:status :already-implemented
                                  :output {:code/id (random-uuid)
                                           :code/files []
                                           :code/summary "No changes needed"}
                                  :metrics {:tokens 25 :duration-ms 250}})
                  agent/curate-implement-output mock-curator-error]
      (let [ctx (-> (create-base-context)
                    (assoc-in [:execution/phase-results :review :result :output :review/feedback]
                              ["Blocking issue still unresolved"]))
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        (is (= :failed (get-in final-result [:phase :status])))
        (is (= "Implement returned :already-implemented after prior review or verify failures without producing a repair artifact"
               (get-in final-result [:phase :error :message])))
        (is (empty? (get-in final-result [:execution :phases-completed] [])))))))

(deftest leave-implement-preserves-verified-already-implemented-noop-test
  (testing "already-implemented survives curator no-files when the task is already satisfied"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 {:status :already-implemented
                                  :output {:code/id (random-uuid)
                                           :code/files []
                                           :code/summary "Behavioral harness already exists"}
                                  :summary "Behavioral harness already exists"
                                  :metrics {:tokens 25 :duration-ms 250}})
                  agent/curate-implement-output mock-curator-error]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :implement})
            interceptor (phase/get-phase-interceptor {:phase :implement})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        (is (= :already-implemented (get-in enter-result [:phase :result :status])))
        (is (= :already-implemented (get-in final-result [:phase :status])))
        (is (= :already-implemented (get-in final-result [:phase :skipped-reason])))
        (is (= "Behavioral harness already exists"
               (get-in final-result [:phase :result :summary])))
        (is (nil? (get-in final-result [:phase :error])))
        (is (= [:implement] (get-in final-result [:execution :phases-completed])))))))

;------------------------------------------------------------------------------ Layer 2b: Stream watchdog stall classification

(defn- mock-curator-no-files
  "Mock curator returning the terminal :curator/no-files-written verdict —
   the empty-diff outcome a killed/stalled agent leaves behind."
  [_]
  (response/error "No files written to environment"
                  {:data {:code :curator/no-files-written}}))

(deftest enter-implement-arms-watchdog-and-classifies-stall-test
  (testing "a silently stalled agent stream is detected, killed, and re-coded as retriable"
    (let [;; agent/invoke blocks (no chunks emitted) until the watchdog kill
          ;; interrupts the phase thread; then it unwinds into the failure path.
          invoked? (atom false)
          interrupted? (atom false)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke (fn [_ _ _]
                                   (reset! invoked? true)
                                   (try
                                     ;; Block far longer than the watchdog
                                     ;; threshold + first check interval.
                                     (Thread/sleep 15000)
                                     (response/success nil {:tokens 0})
                                     (catch InterruptedException _e
                                       (reset! interrupted? true)
                                       (throw (ex-info "stream killed"
                                                       {:reason :interrupted})))))
                    agent/curate-implement-output mock-curator-no-files]
        (let [ctx (-> (create-base-context)
                      ;; An event-stream makes create-streaming-callback return a
                      ;; non-nil on-chunk, which is what arms the watchdog (a
                      ;; non-streaming run has no chunk ping-source by design).
                      (assoc :event-stream (es/create-event-stream {:sinks []}))
                      (assoc-in [:user-config :self-healing :agent/stream-gap-threshold-ms] 1)
                      ;; Tiny check interval so the watchdog fires near-instantly
                      ;; instead of waiting the 5s default — keeps the suite fast.
                      (assoc-in [:user-config :self-healing :agent/stream-check-interval-ms] 10)
                      (assoc-in [:user-config :llm :backend] :echo)
                      (assoc :phase-config {:phase :implement}))
              interceptor (phase/get-phase-interceptor {:phase :implement})
              result ((:enter interceptor) ctx)]
          (is @invoked? "agent/invoke ran")
          (is @interrupted? "the watchdog interrupted the blocked stream")
          (is (true? (get-in result [:phase :watchdog-state :stalled?]))
              "ctx carries the stall outcome for leave-implement")
          (is (pos? (get-in result [:phase :watchdog-state :stall/gap-duration-ms]))
              "measured gap is recorded")
          (is (not= :curator/no-files-written
                    (get-in result [:phase :result :error :data :code]))
              "stalled result must NOT be the terminal no-files verdict")
          (is (= :agent-stalled
                 (get-in result [:phase :result :error :data :code]))
              "stalled result is re-coded as :agent-stalled"))))))

(deftest leave-implement-stall-is-retriable-and-reports-reason-test
  (testing "a stalled watchdog state yields a non-terminal status and :agent-stalled reason"
    (let [result {:status :error
                  :error {:message "stream killed"
                          :data {:code :agent-stalled}}
                  :metrics {:tokens 0 :duration-ms 0}}
          ctx (-> (create-base-context)
                  (assoc :phase {:name :implement
                                 :agent :implementer
                                 :status :running
                                 :iterations 1
                                 :budget {:iterations 8}
                                 :started-at (System/currentTimeMillis)
                                 :result result
                                 :watchdog-state {:stalled? true
                                                  :stall/gap-duration-ms 120000}}))
          final-ctx (implement/leave-implement ctx)]
      ;; Not the terminal :failed — the phase should retry.
      (is (not= :failed (get-in final-ctx [:phase :status]))
          "a stalled run must not terminate as :failed")
      ;; derive-termination-reason resolves the stall to :agent-stalled.
      (let [reason (phase-terminal/derive-termination-reason
                     result {:stalled? true :stall/gap-duration-ms 120000})]
        (is (= :agent-stalled (:phase/termination-reason reason)))
        (is (= 120000 (:stall/gap-duration-ms reason)))))))

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

;------------------------------------------------------------------------------ Network-drop classifier (PR-C of network-resilience stack)
;; Tests for `network-drop-in-result?` — private, accessed via #'. PR-B's
;; network-monitor emits the verdict in three locations the classifier
;; matches against: the canonical agent result shape produced by
;; `result-boundary/error-response` (`[:error :data :timeout :type]`),
;; the raw exec-result shape (`[:timeout :type]`) for test harnesses,
;; and the formatted-error-message channel.

(defn- network-drop? [result]
  (#'implement/network-drop-in-result? result))

(deftest network-drop-classifier-matches-canonical-agent-result-shape-test
  ;; Production path: `streaming-error-response` builds an llm-error
  ;; map with `:timeout` on the `:error`; `result-boundary/error-
  ;; response` merges that into `[:error :data]` via `response/error`.
  ;; This is the shape the implement phase actually sees from a real
  ;; network-drop, so it's the primary classifier target.
  (testing "[:error :data :timeout :type] = :network-drop → true"
    (is (network-drop?
          {:status :error
           :error {:message "Adaptive timeout: Network drop: 3 consecutive probes failed for claude (type: network-drop, elapsed: 30000ms)"
                   :data {:type "adaptive_timeout"
                          :stderr ""
                          :stdout ""
                          :timeout {:type :network-drop
                                    :backend-key :claude
                                    :elapsed-ms 30000
                                    :message "Network drop: 3 consecutive probes failed for claude"}}}}))))

(deftest network-drop-classifier-matches-raw-timeout-shape-test
  ;; Back-compat for raw stream-exec-fn results that haven't been
  ;; wrapped by result-boundary/error-response yet (test harnesses,
  ;; direct exec-result captures). Production rarely takes this path.
  (testing "raw top-level [:timeout :type] = :network-drop → true"
    (is (network-drop? {:timeout {:type :network-drop
                                  :backend-key :claude
                                  :elapsed-ms 30000
                                  :message "Network drop: 3 consecutive probes failed for claude"}}))))

(deftest network-drop-classifier-matches-formatted-error-message-test
  (testing "[:error :message] carrying the formatted Adaptive timeout text → true"
    ;; format-timeout-error produces:
    ;;   "Adaptive timeout: <message> (type: <type-keyword>, elapsed: <n>ms)"
    (is (network-drop?
          {:error {:message "Adaptive timeout: Network drop: 3 consecutive probes failed for claude (type: network-drop, elapsed: 30000ms)"}})))

  (testing "case-insensitive match on the explicit `type:` marker"
    ;; Anchored on the documented format-timeout-error fragment so a
    ;; conversational message that happens to mention 'network drop'
    ;; in prose does NOT trip the classifier.
    (is (network-drop? {:error {:message "TYPE: NETWORK-DROP at provider"}}))
    (is (network-drop?
          {:error {:message "...adaptive timeout (type:network-drop, elapsed:...)"}}))))

(deftest network-drop-classifier-ignores-unrelated-results-test
  (testing "successful or non-network errors must NOT classify as network-drop"
    (is (not (network-drop? {:status :success :output "ok"})))
    (is (not (network-drop? {:status :error :error {:message "Rate limit exceeded"}})))
    (is (not (network-drop? {:status :error :error {:message "Syntax error at line 5"}})))
    (is (not (network-drop? {:timeout {:type :stagnation :elapsed-ms 180000}}))
        "stagnation timeout is a distinct envelope — different recovery strategy")
    (is (not (network-drop? {:timeout {:type :hard-limit :elapsed-ms 600000}})))
    (is (not (network-drop? {:timeout {:type :stream-idle :elapsed-ms 120000}})))
    (is (not (network-drop?
               {:error {:data {:timeout {:type :stagnation :elapsed-ms 180000}}}}))
        "nested stagnation envelope is also distinct"))

  (testing "prose mentions of 'network drop' without the type marker do NOT match"
    ;; Regression for tightened regex — only the explicit
    ;; `type: network-drop` marker emitted by format-timeout-error
    ;; should trip the classifier, not the bare phrase.
    (is (not (network-drop?
               {:error {:message "Network Drop detected on upstream connection"}})))
    (is (not (network-drop?
               {:error {:message "Network drop: please retry shortly"}}))))

  (testing "empty / missing structures classify cleanly as false"
    (is (not (network-drop? {:error {:message nil}})))
    (is (not (network-drop? {})))))

(deftest network-drop-classifier-distinct-from-rate-limit-test
  ;; Both classifiers exist because the two anomalies want different
  ;; recovery strategies: rate-limit pauses + waits; network-drop
  ;; retries from the last persisted checkpoint. Confirm a real
  ;; network-drop result does NOT also classify as rate-limit (and vice
  ;; versa) — otherwise the two branches would race in the leave-
  ;; implement cond. Uses the canonical [:error :data :timeout] shape
  ;; the implement phase actually receives, not the raw top-level form.
  (let [network-result {:status :error
                        :error {:message "Adaptive timeout: Network drop: 3 consecutive probes failed for claude (type: network-drop, elapsed: 30000ms)"
                                :data {:type "adaptive_timeout"
                                       :timeout {:type :network-drop
                                                 :backend-key :claude
                                                 :elapsed-ms 30000}}}}
        rate-limit-result {:error {:message "HTTP 429 rate limit exceeded"}}]
    (is (network-drop? network-result))
    (is (not (rate-limit? network-result))
        "network-drop must NOT trip the rate-limit classifier")
    (is (rate-limit? rate-limit-result))
    (is (not (network-drop? rate-limit-result))
        "rate-limit must NOT trip the network-drop classifier")))

;------------------------------------------------------------------------------ Backend-timeout classifier (PR-C of phase-timeout stack)
;; Companion to the network-drop classifier — same three-location path
;; priority but matches the implementer's `:stream-idle` / `:stagnation`
;; / `:hard-limit` adaptive-timeout types. The two arms are mutually
;; exclusive on `:timeout :type` so they never both fire on the same
;; result.

(defn- backend-timeout? [result]
  (#'implement/backend-timeout-in-result? result))

(deftest backend-timeout-classifier-matches-stream-idle-canonical-shape-test
  ;; The 2026-06-05 dogfood (`adhoc-944448986`) revealed this exact
  ;; production shape on the reviewer side; PR-C wires the implement-
  ;; phase equivalent.
  (testing "[:error :data :timeout :type] = :stream-idle → true"
    (is (backend-timeout?
          {:status :error
           :error {:message "Adaptive timeout: No stream output for 360000ms (type: stream-idle, elapsed: 360087ms)"
                   :data {:type "adaptive_timeout"
                          :timeout {:type :stream-idle
                                    :elapsed-ms 360087}}}}))))

(deftest backend-timeout-classifier-matches-stagnation-canonical-shape-test
  (testing "[:error :data :timeout :type] = :stagnation → true"
    (is (backend-timeout?
          {:status :error
           :error {:data {:timeout {:type :stagnation
                                    :elapsed-ms 180000}}}}))))

(deftest backend-timeout-classifier-matches-hard-limit-canonical-shape-test
  (testing "[:error :data :timeout :type] = :hard-limit → true"
    (is (backend-timeout?
          {:status :error
           :error {:data {:timeout {:type :hard-limit
                                    :elapsed-ms 600000}}}}))))

(deftest backend-timeout-classifier-matches-raw-timeout-shape-test
  (testing "raw top-level [:timeout :type] for back-compat / test harnesses"
    (is (backend-timeout? {:timeout {:type :stream-idle :elapsed-ms 360000}}))
    (is (backend-timeout? {:timeout {:type :stagnation  :elapsed-ms 180000}}))
    (is (backend-timeout? {:timeout {:type :hard-limit  :elapsed-ms 600000}}))))

(deftest backend-timeout-classifier-matches-formatted-error-message-test
  (testing "[:error :message] carrying the explicit `type:` marker"
    (is (backend-timeout?
          {:error {:message "Adaptive timeout: No stream output for 360000ms (type: stream-idle, elapsed: 360087ms)"}}))
    (is (backend-timeout?
          {:error {:message "Adaptive timeout: stagnation timeout (type: stagnation, elapsed: 180000ms)"}}))
    (is (backend-timeout?
          {:error {:message "Adaptive timeout: hard-limit hit (type: hard-limit, elapsed: 600000ms)"}})))

  (testing "case-insensitive match on the type marker"
    (is (backend-timeout? {:error {:message "TYPE: STREAM-IDLE at provider"}}))
    (is (backend-timeout? {:error {:message "...type:Hard-Limit..."}}))))

(deftest backend-timeout-classifier-ignores-unrelated-results-test
  (testing "successful or non-timeout errors must NOT classify"
    (is (not (backend-timeout? {:status :success :output "ok"})))
    (is (not (backend-timeout? {:status :error :error {:message "Rate limit exceeded"}})))
    (is (not (backend-timeout? {:status :error :error {:message "Syntax error at line 5"}})))
    (is (not (backend-timeout? {})))
    (is (not (backend-timeout? {:error {:message nil}}))))

  (testing "network-drop is a DISTINCT envelope — must NOT classify as backend-timeout"
    ;; The two arms are mutually exclusive on `:timeout :type`; this
    ;; is the contract `leave-implement` relies on so the cond chain
    ;; can't accidentally route a network-drop through the backend-
    ;; timeout branch (or vice versa).
    (is (not (backend-timeout? {:timeout {:type :network-drop :elapsed-ms 30000}})))
    (is (not (backend-timeout?
               {:error {:data {:timeout {:type :network-drop :elapsed-ms 30000}}}})))
    (is (not (backend-timeout?
               {:error {:message "Adaptive timeout: (type: network-drop, elapsed: 30000ms)"}}))))

  (testing "prose mentions without the type marker do NOT match"
    (is (not (backend-timeout? {:error {:message "Stream idle on UI subscription"}})))
    (is (not (backend-timeout? {:error {:message "Provider stagnation across regions"}})))))

(deftest backend-timeout-classifier-distinct-from-network-drop-and-rate-limit-test
  ;; Three classifiers feed three distinct verdicts:
  ;; - backend-timeout      → :implement/backend-timeout (retriable + terminal)
  ;; - network-drop         → :implement/network-dropped (retriable + terminal)
  ;; - rate-limit           → :implement/rate-limited    (immediate terminal)
  ;; The leave-implement cond chain depends on no two firing on the
  ;; same result.
  (let [stream-idle-result {:status :error
                            :error {:data {:timeout {:type :stream-idle :elapsed-ms 360000}}}}
        network-drop-result {:status :error
                             :error {:data {:timeout {:type :network-drop :elapsed-ms 30000}}}}
        rate-limit-result {:error {:message "HTTP 429 rate limit exceeded"}}]
    (testing "stream-idle classifies as backend-timeout only"
      (is (backend-timeout? stream-idle-result))
      (is (not (network-drop? stream-idle-result)))
      (is (not (rate-limit? stream-idle-result))))
    (testing "network-drop classifies as network-drop only"
      (is (network-drop? network-drop-result))
      (is (not (backend-timeout? network-drop-result)))
      (is (not (rate-limit? network-drop-result))))
    (testing "rate-limit classifies as rate-limit only"
      (is (rate-limit? rate-limit-result))
      (is (not (backend-timeout? rate-limit-result)))
      (is (not (network-drop? rate-limit-result))))))

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
