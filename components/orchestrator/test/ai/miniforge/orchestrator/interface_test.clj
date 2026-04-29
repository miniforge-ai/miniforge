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

(ns ai.miniforge.orchestrator.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.orchestrator.interface :as sut]
   [ai.miniforge.orchestrator.core :as core]
   [ai.miniforge.orchestrator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- task
  "Build a task map with an optional :task/type and other overrides."
  [task-type & {:as overrides}]
  (merge {:task/id    (random-uuid)
          :task/type  task-type
          :task/title (str (name task-type) " task")}
         overrides))

(defn- repair-entry
  [error-type fix-description]
  {:error-type      error-type
   :fix-description fix-description})

(defn- zettel
  [& {:as overrides}]
  (merge {:zettel/id      (random-uuid)
          :zettel/title   "Z"
          :zettel/content "body"}
         overrides))

(defn- learning-with-confidence
  [confidence]
  {:zettel/source {:source/confidence confidence}})

;; Capture/no-op knowledge-store stub used to keep the knowledge coordinator
;; tests honest without dragging in the real knowledge component.
(defn- capture-knowledge-store
  "Return [stub captured-atom]. Calls to inject-knowledge/capture-inner-loop-learning
   are recorded, and inject-knowledge returns whatever vector is configured."
  [{:keys [inject-result capture-fn promote-fn]
    :or   {inject-result []
           capture-fn    (fn [_store learning] {:zettel/id   (random-uuid)
                                                :zettel/source learning})
           promote-fn    (fn [_store _id _opts] :promoted)}}]
  (let [captured (atom {:inject [] :capture [] :promote []})]
    [{::store true
      :stub/inject-result inject-result
      :stub/capture-fn    capture-fn
      :stub/promote-fn    promote-fn
      :stub/captured      captured}
     captured]))

;------------------------------------------------------------------------------ Layer 1
;; Re-exports — interface forwards to core/protocol

(deftest interface-re-exports-test
  (testing "interface namespace re-exports the four protocols"
    (is (= proto/Orchestrator         sut/Orchestrator))
    (is (= proto/TaskRouter           sut/TaskRouter))
    (is (= proto/BudgetManager        sut/BudgetManager))
    (is (= proto/KnowledgeCoordinator sut/KnowledgeCoordinator)))
  (testing "interface namespace re-exports the public factories"
    (is (= core/create-control-plane         sut/create-control-plane))
    (is (= core/create-orchestrator          sut/create-orchestrator))
    (is (= core/create-router                sut/create-router))
    (is (= core/create-budget-manager        sut/create-budget-manager))
    (is (= core/create-knowledge-coordinator sut/create-knowledge-coordinator))
    (is (= core/default-config               sut/default-config))))

;------------------------------------------------------------------------------ Layer 1
;; default-config

(deftest default-config-shape-test
  (testing "default-config carries the documented keys"
    (let [cfg core/default-config]
      (is (contains? cfg :default-budget))
      (is (contains? cfg :knowledge-injection?))
      (is (contains? cfg :learning-capture?))
      (is (contains? cfg :escalation-threshold))
      (let [b (:default-budget cfg)]
        (is (pos? (:max-tokens b)))
        (is (pos? (:max-cost-usd b)))
        (is (pos? (:timeout-ms b)))))))

;------------------------------------------------------------------------------ Layer 1
;; SimpleTaskRouter / route-task / can-handle?

(deftest route-task-uses-task-type-mapping-test
  (testing "Each known task type routes to its expected agent role"
    (let [router (sut/create-router)]
      (is (= :planner     (-> (sut/route-task router (task :plan)      {}) :agent-role)))
      (is (= :planner     (-> (sut/route-task router (task :design)    {}) :agent-role)))
      (is (= :implementer (-> (sut/route-task router (task :implement) {}) :agent-role)))
      (is (= :tester      (-> (sut/route-task router (task :test)      {}) :agent-role)))
      (is (= :reviewer    (-> (sut/route-task router (task :review)    {}) :agent-role))))))

(deftest route-task-unknown-defaults-to-implementer-test
  (testing "Unknown task type falls back to :implementer"
    (let [router (sut/create-router)]
      (is (= :implementer (-> (sut/route-task router (task :unknown) {}) :agent-role))))))

(deftest route-task-includes-reason-string-test
  (testing "Routing decision carries a non-blank :reason"
    (let [router (sut/create-router)
          {:keys [reason]} (sut/route-task router (task :implement) {})]
      (is (string? reason))
      (is (not (str/blank? reason))))))

(deftest can-handle?-test
  (testing "can-handle? matches the mapping"
    (let [router (sut/create-router)]
      (is (true?  (sut/can-handle? router (task :plan)      :planner)))
      (is (false? (sut/can-handle? router (task :plan)      :implementer)))
      (is (true?  (sut/can-handle? router (task :implement) :implementer)))
      (is (false? (sut/can-handle? router (task :review)    :tester))))))

;------------------------------------------------------------------------------ Layer 1
;; SimpleBudgetManager

(deftest budget-set-and-check-test
  (testing "set-budget stores per-workflow limits, check-budget reports them"
    (let [bm (sut/create-budget-manager)
          wf-id (random-uuid)
          budget {:max-tokens 1000 :max-cost-usd 1.0 :timeout-ms 60000}]
      (sut/set-budget bm wf-id budget)
      (let [{:keys [within-budget? remaining used budget]} (sut/check-budget bm wf-id)]
        (is (true? within-budget?))
        (is (= {:tokens 0 :cost-usd 0.0 :duration-ms 0} used))
        (is (= 1000   (:tokens remaining)))
        (is (= 1.0    (:cost-usd remaining)))
        (is (= 60000  (:time-ms remaining)))
        (is (= 1000   (:max-tokens budget)))))))

(deftest budget-track-accumulates-test
  (testing "Repeated track-usage calls accumulate across all three counters"
    (let [bm (sut/create-budget-manager)
          wf-id (random-uuid)]
      (sut/set-budget bm wf-id {:max-tokens 1000 :max-cost-usd 1.0 :timeout-ms 60000})
      (sut/track-usage bm wf-id {:tokens 100 :cost-usd 0.10 :duration-ms 500})
      (sut/track-usage bm wf-id {:tokens 200 :cost-usd 0.20 :duration-ms 700})
      (let [{:keys [used remaining within-budget?]} (sut/check-budget bm wf-id)]
        (is (= 300  (:tokens used)))
        (is (< (Math/abs (- 0.30 (:cost-usd used))) 1e-9))
        (is (= 1200 (:duration-ms used)))
        (is (= 700  (:tokens remaining)))
        (is (true? within-budget?))))))

(deftest budget-exceeded-reports-not-within-budget-test
  (testing "Spending past the cap flips :within-budget? to false"
    (let [bm (sut/create-budget-manager)
          wf-id (random-uuid)]
      (sut/set-budget bm wf-id {:max-tokens 100 :max-cost-usd 1.0 :timeout-ms 60000})
      (sut/track-usage bm wf-id {:tokens 200 :cost-usd 0.10 :duration-ms 0})
      (is (false? (:within-budget? (sut/check-budget bm wf-id)))))))

(deftest budget-default-applies-when-not-set-test
  (testing "An unset workflow falls back to the default budget"
    (let [bm (sut/create-budget-manager)
          wf-id (random-uuid)
          {:keys [budget within-budget?]} (sut/check-budget bm wf-id)]
      (is (true? within-budget?))
      (is (= (-> core/default-config :default-budget :max-tokens)
             (:max-tokens budget))))))

;------------------------------------------------------------------------------ Layer 1
;; build-repair-learning

(deftest build-repair-learning-shape-test
  (testing "build-repair-learning returns the documented learning capture shape"
    (let [tk (task :implement :task/title "Add greet fn")
          history [(repair-entry :compile-error "added missing import")
                   (repair-entry :test-failure  "fixed off-by-one")]
          learning (core/build-repair-learning :implementer tk history)]
      (is (= :implementer (:agent learning)))
      (is (= (:task/id tk) (:task-id learning)))
      (is (string? (:title learning)))
      (is (string? (:content learning)))
      (is (= [:repair :inner-loop :implementer] (:tags learning)))
      (is (= 0.7 (:confidence learning))))))

;------------------------------------------------------------------------------ Layer 1
;; format-zettel-for-context / format-knowledge-block

(deftest format-zettel-includes-title-and-content-test
  (testing "Each zettel renders title + content; dewey is bracketed when present"
    (let [with-dewey (zettel :zettel/title "T" :zettel/content "C" :zettel/dewey "210")
          without    (zettel :zettel/title "T" :zettel/content "C")]
      (is (str/includes? (core/format-zettel-for-context with-dewey) "[210]"))
      (is (str/includes? (core/format-zettel-for-context with-dewey) "T"))
      (is (str/includes? (core/format-zettel-for-context with-dewey) "C"))
      (is (not (str/includes? (core/format-zettel-for-context without) "["))))))

(deftest format-knowledge-block-empty-test
  (testing "Empty zettel list produces no block"
    (is (nil? (core/format-knowledge-block [] :implementer)))))

(deftest format-knowledge-block-renders-test
  (testing "Non-empty zettel list produces a block containing each title"
    (let [block (core/format-knowledge-block
                 [(zettel :zettel/title "Alpha")
                  (zettel :zettel/title "Beta")]
                 :implementer)]
      (is (string? block))
      (is (str/includes? block "Alpha"))
      (is (str/includes? block "Beta")))))

;------------------------------------------------------------------------------ Layer 2
;; SimpleKnowledgeCoordinator — uses with-redefs to stub knowledge calls.

(deftest knowledge-coord-injection-disabled-returns-nil-test
  (testing "When :knowledge-injection? is false, inject-for-agent returns nil"
    (let [[store _] (capture-knowledge-store {})
          coord (sut/create-knowledge-coordinator store
                                                  (assoc core/default-config
                                                         :knowledge-injection? false))]
      (is (nil? (sut/inject-for-agent coord :implementer (task :implement) {}))))))

(deftest knowledge-coord-injection-enabled-returns-block-test
  (testing "When enabled, inject-for-agent merges task and context tags into the query
            and packages the result into a {:formatted :zettels :count} map"
    (let [seen-args (atom nil)
          fake-zettels [(zettel :zettel/title "K1") (zettel :zettel/title "K2")]
          coord (sut/create-knowledge-coordinator ::store core/default-config)]
      (with-redefs [ai.miniforge.knowledge.interface/inject-knowledge
                    (fn [store agent-role context]
                      (reset! seen-args [store agent-role context])
                      fake-zettels)]
        (let [ret (sut/inject-for-agent coord :implementer
                                        (task :implement :task/tags [:clj])
                                        {:tags [:io]})]
          (is (= 2 (:count ret)))
          (is (= fake-zettels (:zettels ret)))
          (is (string? (:formatted ret)))
          (is (str/includes? (:formatted ret) "K1"))
          (let [[store role context] @seen-args]
            (is (= ::store store))
            (is (= :implementer role))
            (is (= #{:clj :io} (set (:tags context))))))))))

(deftest should-promote-learning?-confidence-threshold-test
  (testing "Confidence ≥ 0.85 ⇒ :promote? true; below ⇒ false"
    (let [coord (sut/create-knowledge-coordinator ::store core/default-config)]
      (is (true?  (:promote? (sut/should-promote-learning? coord (learning-with-confidence 0.90)))))
      (is (true?  (:promote? (sut/should-promote-learning? coord (learning-with-confidence 0.85)))))
      (is (false? (:promote? (sut/should-promote-learning? coord (learning-with-confidence 0.84)))))
      (is (false? (:promote? (sut/should-promote-learning? coord (learning-with-confidence 0.0)))))
      (is (false? (:promote? (sut/should-promote-learning? coord {})))))))

(deftest should-promote-learning?-includes-confidence-and-reason-test
  (testing "should-promote-learning? returns the confidence and a non-blank reason"
    (let [coord (sut/create-knowledge-coordinator ::store core/default-config)
          decision (sut/should-promote-learning? coord (learning-with-confidence 0.90))]
      (is (= 0.90 (:confidence decision)))
      (is (string? (:reason decision)))
      (is (not (str/blank? (:reason decision)))))))

(deftest capture-execution-learning-no-op-when-not-repaired-test
  (testing "execution-result without :repaired? skips capture"
    (let [coord (sut/create-knowledge-coordinator ::store core/default-config)]
      (is (nil? (sut/capture-execution-learning
                 coord {:repaired? false :agent-role :implementer
                        :task (task :implement) :repair-history []}))))))

(deftest capture-execution-learning-disabled-test
  (testing "When :learning-capture? false, capture is skipped even on a repair"
    (let [coord (sut/create-knowledge-coordinator
                 ::store
                 (assoc core/default-config :learning-capture? false))
          history [(repair-entry :compile-error "fixed import")]]
      (is (nil? (sut/capture-execution-learning
                 coord {:repaired? true :agent-role :implementer
                        :task (task :implement)
                        :repair-history history}))))))

(deftest capture-execution-learning-calls-knowledge-store-test
  (testing "On a repaired execution, capture-inner-loop-learning is called and the
            result is returned"
    (let [seen   (atom nil)
          coord  (sut/create-knowledge-coordinator ::store core/default-config)
          history [(repair-entry :test-failure "fixed off-by-one")]]
      (with-redefs [ai.miniforge.knowledge.interface/capture-inner-loop-learning
                    (fn [store learning]
                      (reset! seen [store learning])
                      {:zettel/id (random-uuid)
                       :zettel/source {:source/confidence 0.5}})
                    ;; promote-learning is conditionally called; stub it to a no-op
                    ;; so the test doesn't depend on confidence threshold.
                    ai.miniforge.knowledge.interface/promote-learning
                    (fn [_store _id _opts] nil)]
        (let [ret (sut/capture-execution-learning
                   coord {:repaired? true
                          :agent-role :implementer
                          :task (task :implement)
                          :repair-history history})]
          (is (some? ret))
          (let [[store learning] @seen]
            (is (= ::store store))
            (is (= :implementer (:agent learning)))
            (is (string? (:title learning)))))))))
