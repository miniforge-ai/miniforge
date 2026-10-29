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
(ns ai.miniforge.workflow.runner-integration-test
  "Integration tests for the workflow runner that execute real phase pipelines."
  (:require
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.lifecycle-result :as opsv-result]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
   [ai.miniforge.workflow.isolation-support :as isolation]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.checkpoint-root-support :as checkpoint-root-support]
   [ai.miniforge.workflow.runner :as runner]
   ;; Require phase implementations
   [ai.miniforge.phase-software-factory.plan]
   [ai.miniforge.phase-software-factory.implement]
   [ai.miniforge.phase-software-factory.verify]
   [ai.miniforge.phase-software-factory.review]
   [ai.miniforge.phase-software-factory.release]
   ;; Require gate implementations
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]))

(use-fixtures :each checkpoint-root-support/with-temp-checkpoint-root)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-mocked-test-runner
  "Run body-fn with run-tests! mocked to prevent recursive bb test."
  [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [& _args] {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
      body-fn)))

(deftest ^{:stratum 0} run-pipeline-max-phases-test
  (testing "run-pipeline respects max phases option"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
      (is (= :completed (:execution/status result))))))

(def ^{:stratum 0} plan-workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :plan}
                       {:phase :done}]})

(def ^{:stratum 0} plan-input
  {:task "Test"
   :title "Test"
   :plan/tasks [{:task/type :implement
                 :task/title "Draft the change"
                 :task/description "Create a plan artifact without invoking the LLM"}]})

(def ^{:stratum 0} denied-plan-workflow
  {:workflow/id :test
   :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :plan :gates [:review-approved]}
                       {:phase :done}]})

(defn ^{:stratum 0} decision-rejecting-publish
  "Delegate every event to `publish` except :gate/decision, which the stream rejects."
  [publish stream event]
  (if (= :gate/decision (:event/type event))
    {:rejected? true :reason :fixture/decision-refused :event-type (:event/type event)}
    (publish stream event)))

(defn ^{:stratum 0} finished-phase-enter
  "An :enter that finished its own work successfully. Its output carries no
   review decision, so a :review-approved gate denies it."
  [phase-kw ctx]
  (assoc ctx :phase {:name phase-kw :status :completed
                     :started-at (System/currentTimeMillis)
                     :result {:status :success :output {:finished phase-kw}}}))

(defn ^{:stratum 0} events-of [history event-type]
  (filterv #(= event-type (:event/type %)) history))

(deftest ^{:stratum 0} observe-with-no-prs-still-honors-a-denying-gate-test
  (testing "the real :observe enter skips when there are no PRs; its :leave still
            runs and fails the phase its gate denied"
    (let [ctx {:execution/id (random-uuid)
               :event-stream (events/create-event-stream {:sinks []})
               :execution/current-phase :observe
               :execution/metrics {} :execution/errors []
               :execution/response-chain (response/create :observe-gate)}
          [left result] (lifecycle/execute-phase-lifecycle
                         (phase/get-phase-interceptor {:phase :observe :gates [:review-approved]})
                         ctx)]
      (is (= :skipped (get-in result [:result :output :observe/status])) "precondition: no PRs")
      (is (= :deny (get-in result [:phase/decision-envelope :envelope/decision])))
      (is (= :failed (:status result)))
      (is (not (some #{:observe} (get-in left [:execution :phases-completed])))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} response-chain-records-phases-test
  (testing "response chain records phase results"
    (let [;; Gated phases advance only on an acknowledged :gate/decision.
          result (runner/run-pipeline plan-workflow plan-input
                                      {:event-stream (events/create-event-stream {:sinks []})})
          chain (:execution/response-chain result)]
      (is (= :completed (:execution/status result)))
      (is (= 1 (count (:response-chain chain))))
      (is (= :plan (get-in chain [:response-chain 0 :operation])))
      (is (= :completed (get-in result [:execution/phase-results :implement :status])))
      (is (true? (:succeeded? chain))))))

(deftest ^{:stratum 1} unacknowledged-plan-decision-never-reaches-the-dag-test
  (testing "without an event stream the plan's gate decision is not acknowledged:
            the run fails before the DAG executor receives the plan's tasks"
    (let [result (runner/run-pipeline plan-workflow plan-input {})
          refusal (some #(when (= :gate-decision (:boundary %)) %) (:execution/errors result))]
      (is (= :failed (:execution/status result)))
      (is (= :plan (:phase refusal)))
      (is (= :failed (get-in result [:execution/phase-results :plan :status])))
      (is (some? (get-in result [:execution/phase-results :plan :phase/decision-envelope])))
      (is (not (contains? (:execution/phase-results result) :implement))))))

(deftest ^{:stratum 1} refused-decision-finalizes-the-plan-as-failed-test
  (testing "a stream that rejects only the gate decision: the real leave-plan finalizes
            a failed plan (no success completion or milestone) and the DAG never runs"
    (let [stream (events/create-event-stream {:sinks []})
          result (with-redefs [events/publish! (partial decision-rejecting-publish events/publish!)]
                   (runner/run-pipeline plan-workflow plan-input {:event-stream stream}))
          history (events/get-events stream)]
      (is (= :failed (:execution/status result)))
      (is (some #(= :gate-decision (:boundary %)) (:execution/errors result)))
      (is (empty? (events-of history :phase/milestone-completed)))
      (is (empty? (events-of history :workflow/milestone-reached)))
      (is (seq (events-of history :phase/milestone-failed)))
      (is (not-any? #(= :success (:phase/outcome %)) (events-of history :workflow/phase-completed)))
      (is (not (contains? (:execution/phase-results result) :implement))))))

(deftest ^{:stratum 1} denied-plan-never-reaches-the-dag-test
  (testing "an acknowledged denial: leave-plan does not turn the plan back to :completed
            and the DAG executor does not run the denied plan's tasks"
    (let [stream (events/create-event-stream {:sinks []})
          result (runner/run-pipeline denied-plan-workflow plan-input {:event-stream stream})
          [decision] (events-of (events/get-events stream) :gate/decision)]
      (is (= :deny (get-in decision [:gate/decision-envelope :envelope/decision])))
      (is (= :failed (get-in result [:execution/phase-results :plan :status])))
      (is (= :failed (:execution/status result)))
      (is (not (contains? (:execution/phase-results result) :implement))))))

(deftest ^{:stratum 1} configured-denying-gate-is-never-completed-test
  (testing "one phase from each packaged family whose :leave did its own completed
            bookkeeping: observe, done, OPSV, compliance-scanner (security-compliance
            is in no project; its leave is covered in its component tests)"
    (doseq [phase-kw [:observe :done :opsv/plan :compliance-scan]]
      (testing (str phase-kw)
        (let [interceptor (assoc (phase/get-phase-interceptor {:phase phase-kw :gates [:review-approved]})
                                 :enter (partial finished-phase-enter phase-kw))
              ctx {:execution/id (random-uuid)
                   :event-stream (events/create-event-stream {:sinks []})
                   :execution/current-phase phase-kw
                   :execution/metrics {} :execution/errors []
                   :execution/response-chain (response/create :gate-families)}
              published (atom 0)
              [left result] (with-redefs [opsv-result/after-publication
                                          (fn [_ctx _phase result] (swap! published inc) result)]
                              (lifecycle/execute-phase-lifecycle interceptor ctx))]
          (is (= [:review-approved] (get-in interceptor [:config :gates])))
          (is (= (if (= :opsv/plan phase-kw) 1 0) @published)
              "OPSV still journals the events of what the phase did")
          (is (= :deny (get-in result [:phase/decision-envelope :envelope/decision])))
          (is (= :failed (:status result)))
          (is (not (some #{phase-kw} (get-in left [:execution :phases-completed])))))))))

;; Every pipeline this namespace runs acquires its worktree from a
;; throwaway host repository and checkpoints into a throwaway root — never
;; the checkout the test JVM was launched in, never `~/.miniforge`.
(clojure.test/use-fixtures :once isolation/with-isolated-host)
