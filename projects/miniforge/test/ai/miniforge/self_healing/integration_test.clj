(ns ai.miniforge.self-healing.integration-test
  "Integration tests for self-healing wiring into execution pipeline."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.agent.core :as core]
            [ai.miniforge.agent.protocol :as protocol]
            [ai.miniforge.self-healing.interface :as sh]
            [ai.miniforge.self-healing.backend-health :as health]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures — temp files for health/approval data

(def ^:dynamic *test-health-path* nil)

(defn- temp-health-path []
  (str (System/getProperty "java.io.tmpdir")
       "/miniforge-test-health-" (random-uuid) ".edn"))

(defn cleanup-fixture [f]
  (let [path (temp-health-path)]
    (binding [*test-health-path* path]
      ;; Redirect health file to temp path
      (with-redefs [health/load-health (fn []
                                         (let [file (clojure.java.io/file path)]
                                           (if (.exists file)
                                             (clojure.edn/read-string (slurp file))
                                             {:backends {}
                                              :switch-cooldowns {}
                                              :default-backend :anthropic
                                              :fallback-order [:anthropic :openai :codex :ollama :google]})))
                    health/save-health! (fn [data]
                                          (clojure.java.io/make-parents path)
                                          (spit path (pr-str data)))]
        (try
          (f)
          (finally
            (let [file (clojure.java.io/file path)]
              (when (.exists file)
                (.delete file)))))))))

(use-fixtures :each cleanup-fixture)

;------------------------------------------------------------------------------ Layer 1
;; Helper: create a throwing agent for testing

(defrecord ThrowingAgent [id role capabilities config memory-id state throw-ex]
  protocol/Agent
  (invoke [_this _task _context]
    (throw throw-ex))
  (validate [_this output _context]
    {:valid? true :errors []})
  (repair [_this output _errors _context]
    {:repaired output :changes [] :success true})

  protocol/AgentLifecycle
  (init [this _new-config] this)
  (status [_this] {:status :ready})
  (shutdown [this] this)
  (abort [this _reason] {:aborted true}))

(defrecord SuccessAgent [id role capabilities config memory-id state]
  protocol/Agent
  (invoke [_this _task _context]
    {:success true
     :outputs [{:artifact/id (random-uuid)
                :artifact/type :code
                :artifact/content "ok"}]
     :decisions [:completed]
     :signals [:done]
     :metrics (core/make-metrics)})
  (validate [_this output _context]
    {:valid? true :errors []})
  (repair [_this output _errors _context]
    {:repaired output :changes [] :success true})

  protocol/AgentLifecycle
  (init [this _new-config] this)
  (status [_this] {:status :ready})
  (shutdown [this] this)
  (abort [this _reason] {:aborted true}))

(defn- make-throwing-agent [ex]
  (->ThrowingAgent (random-uuid) :tester #{} {} (random-uuid) {:status :ready} ex))

(defn- make-success-agent []
  (->SuccessAgent (random-uuid) :tester #{} {} (random-uuid) {:status :ready}))

(defn- make-task []
  {:task/id (random-uuid)
   :task/type :test
   :task/status :pending
   :task/inputs []})

;------------------------------------------------------------------------------ Layer 2
;; Tests

(deftest test-agent-failure-records-health
  (testing "Agent failure records backend health with success?=false"
    (let [recorded (atom [])
          executor (core/create-executor)]
      (with-redefs [sh/record-backend-call! (fn [backend success?]
                                                   (swap! recorded conj {:backend backend :success? success?})
                                                   nil)
                    health/load-health (fn [] {:backends {} :switch-cooldowns {}
                                               :default-backend :anthropic
                                               :fallback-order [:anthropic :openai]})]
        (let [result (protocol/execute executor
                                        (make-throwing-agent (ex-info "boom" {}))
                                        (make-task)
                                        {:llm-backend {:config {:backend :anthropic}}})]
          (is (false? (:success result)))
          (is (some #(and (= :anthropic (:backend %))
                          (false? (:success? %)))
                    @recorded)))))))

(deftest test-agent-success-records-health
  (testing "Agent success records backend health with success?=true"
    (let [recorded (atom [])
          executor (core/create-executor)]
      (with-redefs [sh/record-backend-call! (fn [backend success?]
                                                   (swap! recorded conj {:backend backend :success? success?})
                                                   nil)
                    health/load-health (fn [] {:backends {} :switch-cooldowns {}
                                               :default-backend :anthropic
                                               :fallback-order [:anthropic :openai]})]
        (let [result (protocol/execute executor
                                        (make-success-agent)
                                        (make-task)
                                        {:llm-backend {:config {:backend :openai}}})]
          (is (:success result))
          (is (some #(and (= :openai (:backend %))
                          (true? (:success? %)))
                    @recorded)))))))

(deftest test-known-error-triggers-workaround-retry
  (testing "Known error pattern triggers workaround detection and retry"
    (let [call-count (atom 0)
          executor (core/create-executor)
          ;; Agent that fails first time, succeeds on retry
          retry-agent (reify
                        protocol/Agent
                        (invoke [_ _task _context]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              (throw (ex-info "simulated error" {}))
                              {:success true
                               :outputs []
                               :decisions [:completed]
                               :signals [:done]
                               :metrics (core/make-metrics)})))
                        (validate [_ output _context]
                          {:valid? true :errors []})
                        (repair [_ output _errors _context]
                          {:repaired output :changes [] :success true})
                        protocol/AgentLifecycle
                        (init [this _config] this)
                        (status [_] {:status :ready})
                        (shutdown [this] this)
                        (abort [this _reason] {:aborted true}))]
      ;; Make self-healing return a successful workaround
      (with-redefs [sh/record-backend-call! (fn [_ _] nil)
                    health/load-health (fn [] {:backends {} :switch-cooldowns {}
                                               :default-backend :anthropic
                                               :fallback-order [:anthropic :openai]})
                    sh/detect-and-apply-workaround
                    (fn [_error _opts]
                      {:workaround-found? true
                       :applied? true
                       :success? true
                       :message "Test workaround applied"})]
        (let [result (protocol/execute executor retry-agent (make-task)
                                        {:llm-backend {:config {:backend :anthropic}}})]
          (is (:success result))
          (is (true? (:self-healing/workaround-applied result)))
          (is (= 2 @call-count)))))))

(deftest test-backend-switch-on-health-degradation
  (testing "Multiple failures trigger backend switch via check-and-switch-if-needed"
    (let [switch-called? (atom false)]
      (with-redefs [sh/record-backend-call! (fn [_ _] nil)
                    health/check-and-switch-if-needed
                    (fn [backend _threshold _cooldown]
                      (when (= backend :anthropic)
                        (reset! switch-called? true)
                        {:should-switch? true
                         :from :anthropic
                         :to :openai
                         :cooldown-until (java.time.Instant/now)}))]
        ;; Directly call the interface function
        (let [result (sh/check-backend-health-and-switch
                       {:llm {:backend :anthropic}
                        :config {:self-healing {:enabled true
                                                :backend-auto-switch true
                                                :backend-health-threshold 0.90
                                                :backend-switch-cooldown-ms 1800000}}})]
          (is @switch-called?)
          (is (:switched? result))
          (is (= :openai (:to result))))))))

(deftest test-self-healing-graceful-degradation
  (testing "When self-healing is unavailable, execution works normally"
    (let [executor (core/create-executor)]
      ;; Simulate requiring-resolve returning nil (self-healing not on classpath)
      ;; The try/catch in record-backend-health! and try-self-healing-on-failure
      ;; should swallow errors and let execution proceed normally
      (with-redefs [sh/record-backend-call! (fn [_ _] (throw (ex-info "not available" {})))
                    health/load-health (fn [] (throw (ex-info "not available" {})))]
        ;; Success case — should still work
        (let [result (protocol/execute executor
                                        (make-success-agent)
                                        (make-task)
                                        {})]
          (is (:success result)))

        ;; Failure case — should still return error result (not throw)
        (let [result (protocol/execute executor
                                        (make-throwing-agent (ex-info "real error" {}))
                                        (make-task)
                                        {})]
          (is (false? (:success result)))
          (is (= "real error" (or (:message (:error result)) (:error result)))))))))
