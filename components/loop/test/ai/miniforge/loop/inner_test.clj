(ns ai.miniforge.loop.inner-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.loop.inner :as inner]
            [ai.miniforge.loop.gates :as gates]
            [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement})

(defn make-generate-fn
  "Create a generate function that produces the given artifact."
  [artifact & {:keys [tokens] :or {tokens 100}}]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens tokens}))

(defn valid-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(defn invalid-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn broken ["})

;------------------------------------------------------------------------------ Layer 1
;; State transition tests

(deftest valid-transition-test
  (testing "pending -> generating is valid"
    (is (inner/valid-transition? :pending :generating)))

  (testing "generating -> validating is valid"
    (is (inner/valid-transition? :generating :validating)))

  (testing "validating -> complete is valid"
    (is (inner/valid-transition? :validating :complete)))

  (testing "validating -> repairing is valid"
    (is (inner/valid-transition? :validating :repairing)))

  (testing "repairing -> generating is valid"
    (is (inner/valid-transition? :repairing :generating)))

  (testing "repairing -> escalated is valid"
    (is (inner/valid-transition? :repairing :escalated)))

  (testing "pending -> complete is invalid"
    (is (not (inner/valid-transition? :pending :complete))))

  (testing "complete -> anything is invalid"
    (is (not (inner/valid-transition? :complete :pending)))
    (is (not (inner/valid-transition? :complete :generating)))))

(deftest terminal-state-test
  (testing ":complete is terminal"
    (is (inner/terminal-state? :complete)))

  (testing ":failed is terminal"
    (is (inner/terminal-state? :failed)))

  (testing ":escalated is terminal"
    (is (inner/terminal-state? :escalated)))

  (testing ":pending is not terminal"
    (is (not (inner/terminal-state? :pending))))

  (testing ":generating is not terminal"
    (is (not (inner/terminal-state? :generating)))))

(deftest transition-test
  (let [loop-state (inner/create-inner-loop test-task {})]
    (testing "valid transition updates state"
      (let [next-state (inner/transition loop-state :generating)]
        (is (= :generating (:loop/state next-state)))))

    (testing "invalid transition throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid state transition"
                            (inner/transition loop-state :complete))))))

;------------------------------------------------------------------------------ Layer 1
;; Loop state creation tests

(deftest create-inner-loop-test
  (testing "creates loop with required fields"
    (let [loop-state (inner/create-inner-loop test-task {})]
      (is (uuid? (:loop/id loop-state)))
      (is (= :inner (:loop/type loop-state)))
      (is (= :pending (:loop/state loop-state)))
      (is (= 0 (:loop/iteration loop-state)))
      (is (= (:task/id test-task) (get-in loop-state [:loop/task :task/id])))))

  (testing "respects max-iterations option"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 10})]
      (is (= 10 (get-in loop-state [:loop/config :max-iterations])))))

  (testing "respects budget option"
    (let [budget {:max-tokens 50000 :max-cost-usd 1.0}
          loop-state (inner/create-inner-loop test-task {:budget budget})]
      (is (= budget (get-in loop-state [:loop/config :budget]))))))

;------------------------------------------------------------------------------ Layer 1
;; Termination tests

(deftest should-terminate-test
  (testing "terminal state triggers termination"
    (let [loop-state (-> (inner/create-inner-loop test-task {})
                         (assoc :loop/state :complete))]
      (is (:terminate? (inner/should-terminate? loop-state)))))

  (testing "max iterations triggers termination"
    (let [loop-state (-> (inner/create-inner-loop test-task {:max-iterations 3})
                         (assoc :loop/iteration 3))]
      (is (:terminate? (inner/should-terminate? loop-state)))
      (is (= :max-iterations (:reason (inner/should-terminate? loop-state))))))

  (testing "budget exhausted triggers termination"
    (let [loop-state (-> (inner/create-inner-loop test-task
                                                   {:budget {:max-tokens 100}})
                         (assoc-in [:loop/metrics :tokens] 100))]
      (is (:terminate? (inner/should-terminate? loop-state)))
      (is (= :budget-exhausted (:reason (inner/should-terminate? loop-state))))))

  (testing "normal state does not trigger termination"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 5})]
      (is (nil? (inner/should-terminate? loop-state))))))

;------------------------------------------------------------------------------ Layer 2
;; Step function tests

(deftest generate-step-test
  (testing "successful generation transitions to validating"
    (let [loop-state (inner/create-inner-loop test-task {})
          generate-fn (make-generate-fn (valid-artifact))
          result (inner/generate-step loop-state generate-fn {})]
      (is (= :validating (:loop/state result)))
      (is (some? (:loop/artifact result)))
      (is (= 1 (:loop/iteration result)))
      (is (= 1 (get-in result [:loop/metrics :generate-calls])))))

  (testing "generation failure transitions to failed"
    (let [loop-state (inner/create-inner-loop test-task {})
          failing-fn (fn [_t _c] (throw (Exception. "Generation error")))
          result (inner/generate-step loop-state failing-fn {})]
      (is (= :failed (:loop/state result)))
      (is (seq (:loop/errors result))))))

(deftest validate-step-test
  (testing "all gates pass transitions to complete"
    (let [loop-state (-> (inner/create-inner-loop test-task {})
                         (assoc :loop/state :validating)
                         (assoc :loop/artifact (valid-artifact)))
          result (inner/validate-step loop-state (gates/minimal-gates) {})]
      (is (= :complete (:loop/state result)))
      (is (seq (:loop/gate-results result)))))

  (testing "gate failure transitions to repairing"
    (let [loop-state (-> (inner/create-inner-loop test-task {})
                         (assoc :loop/state :validating)
                         (assoc :loop/artifact (invalid-artifact)))
          result (inner/validate-step loop-state (gates/minimal-gates) {})]
      (is (= :repairing (:loop/state result)))
      (is (seq (:loop/errors result))))))

(deftest repair-step-test
  (let [mock-repair-fn (fn [artifact _errors _ctx]
                         {:success? true
                          :artifact (assoc artifact
                                           :artifact/content
                                           "(defn fixed [] :ok)")
                          :tokens-used 50})]
    (testing "successful repair transitions to generating"
      (let [loop-state (-> (inner/create-inner-loop test-task {:max-iterations 5})
                           (assoc :loop/state :repairing)
                           (assoc :loop/artifact (invalid-artifact))
                           (assoc :loop/errors [{:code :syntax-error :message "Bad"}]))
            result (inner/repair-step loop-state
                                      (repair/default-strategies)
                                      {:repair-fn mock-repair-fn})]
        (is (= :generating (:loop/state result)))
        (is (= "(defn fixed [] :ok)"
               (get-in result [:loop/artifact :artifact/content]))))))

  (testing "max iterations triggers escalation"
    (let [loop-state (-> (inner/create-inner-loop test-task {:max-iterations 2})
                         (assoc :loop/state :repairing)
                         (assoc :loop/iteration 2)  ; At max
                         (assoc :loop/errors [{:code :error :message "Error"}]))
          result (inner/repair-step loop-state
                                    (repair/default-strategies)
                                    {})]
      (is (= :escalated (:loop/state result))))))

;------------------------------------------------------------------------------ Layer 2
;; Full loop tests

(deftest run-inner-loop-test
  (testing "successful generation completes in one iteration"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 5})
          generate-fn (make-generate-fn (valid-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {})]
      (is (:success result))
      (is (= 1 (:iterations result)))
      (is (some? (:artifact result)))
      (is (= :gates-passed (get-in result [:termination :reason])))))

  (testing "repair cycle works"
    (let [call-count (atom 0)
          generate-fn (fn [_task _ctx]
                        (swap! call-count inc)
                        (if (= 1 @call-count)
                          {:artifact (invalid-artifact) :tokens 100}
                          {:artifact (valid-artifact) :tokens 100}))
          mock-repair-fn (fn [artifact _errors _ctx]
                           {:success? true
                            :artifact artifact  ; Return same artifact, generate will fix it
                            :tokens-used 50})
          loop-state (inner/create-inner-loop test-task {:max-iterations 5})
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       (repair/default-strategies)
                                       {:repair-fn mock-repair-fn})]
      (is (:success result))
      (is (= 2 (:iterations result)))))

  (testing "escalation with hints resumes generation"
    (let [hints-seen (atom nil)
          loop-state (inner/create-inner-loop test-task {:max-iterations 1})
          generate-fn (fn [_task ctx]
                        (when-let [hints (:escalation-hints ctx)]
                          (reset! hints-seen hints))
                        (if (:escalation-hints ctx)
                          {:artifact (valid-artifact) :tokens 10}
                          {:artifact (invalid-artifact) :tokens 10}))
          escalation-fn (fn [_state & _opts] {:action :continue :hints "use this"})
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {:escalation-fn escalation-fn})]
      (is (:success result))
      (is (= 2 (:iterations result)))
      (is (= "use this" @hints-seen))
      (is (= :gates-passed (get-in result [:termination :reason])))))

  (testing "max iterations escalates"
    (let [loop-state (inner/create-inner-loop test-task {:max-iterations 2})
          generate-fn (make-generate-fn (invalid-artifact))
          result (inner/run-inner-loop loop-state
                                       generate-fn
                                       (gates/minimal-gates)
                                       [(repair/retry-strategy {:delay-ms 0})]
                                       {})]
      (is (not (:success result)))
      (is (= :max-iterations (get-in result [:termination :reason]))))))

(deftest run-simple-test
  (testing "run-simple with valid generation"
    (let [result (inner/run-simple test-task
                                   (make-generate-fn (valid-artifact))
                                   {:max-iterations 3})]
      (is (:success result))
      (is (= 1 (:iterations result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.loop.inner-test)

  :leave-this-here)
