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

(ns ai.miniforge.tui-views.ws2-subscription-test
  "WS2 tests: PR monitor and control-plane event subscription + model handlers.

   Verifies that:
   1. New msg constructors produce the expected vector shape.
   2. subscription/translate-event maps :pr-monitor/* and :control-plane/* events.
   3. update/events handlers correctly mutate the model."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.subscription :as sub]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Helpers

(defn- init []
  (model/init-model))

(defn- make-pr [repo number & [extra]]
  (merge {:pr/repo repo :pr/number number} extra))

(defn- event [type & [extra]]
  (merge {:event/id (random-uuid) :event/type type} extra))

;------------------------------------------------------------------------------ msg constructors — Layer 0d/0e/0f

(deftest msg-pr-monitor-loop-started-shape-test
  (testing "pr-monitor-loop-started returns correct shape"
    (let [[type payload] (msg/pr-monitor-loop-started 42 {:poll-interval-ms 60000})]
      (is (= :msg/pr-monitor-loop-started type))
      (is (= 42 (:pr-id payload)))
      (is (= {:poll-interval-ms 60000} (:config payload))))))

(deftest msg-pr-monitor-budget-warning-shape-test
  (testing "pr-monitor-budget-warning carries remaining and total"
    (let [[type payload] (msg/pr-monitor-budget-warning 42 3 10)]
      (is (= :msg/pr-monitor-budget-warning type))
      (is (= 42 (:pr-id payload)))
      (is (= 3 (:remaining payload)))
      (is (= 10 (:total payload))))))

(deftest msg-pr-monitor-escalated-shape-test
  (testing "pr-monitor-escalated carries pr-id and reason"
    (let [[type payload] (msg/pr-monitor-escalated 42 "Human review needed")]
      (is (= :msg/pr-monitor-escalated type))
      (is (= 42 (:pr-id payload)))
      (is (= "Human review needed" (:reason payload))))))

(deftest msg-control-plane-agent-discovered-shape-test
  (testing "control-plane-agent-discovered has session-id and agent-data"
    (let [sid (random-uuid)
          [type payload] (msg/control-plane-agent-discovered sid {:name "agent-1"})]
      (is (= :msg/control-plane-agent-discovered type))
      (is (= sid (:session-id payload))))))

(deftest msg-subscription-status-changed-shape-test
  (testing "subscription-status-changed has status and last-event-at"
    (let [ts  (java.util.Date.)
          [type payload] (msg/subscription-status-changed :stale ts)]
      (is (= :msg/subscription-status-changed type))
      (is (= :stale (:status payload)))
      (is (= ts (:last-event-at payload))))))

;------------------------------------------------------------------------------ subscription/translate-event — pr-monitor events

(deftest translate-pr-monitor-loop-started-test
  (testing "translates :pr-monitor/loop-started"
    (let [ev  (event :pr-monitor/loop-started
                     {:pr/id 42 :config {:poll-interval-ms 60000}})
          msg (sub/translate-event ev)]
      (is (= :msg/pr-monitor-loop-started (first msg)))
      (is (= 42 (:pr-id (second msg)))))))

(deftest translate-pr-monitor-loop-stopped-test
  (testing "translates :pr-monitor/loop-stopped"
    (let [ev  (event :pr-monitor/loop-stopped {:pr/id 42 :stop/reason "merged"})
          msg (sub/translate-event ev)]
      (is (= :msg/pr-monitor-loop-stopped (first msg)))
      (is (= 42 (:pr-id (second msg))))
      (is (= "merged" (:reason (second msg)))))))

(deftest translate-pr-monitor-budget-warning-test
  (testing "translates :pr-monitor/budget-warning"
    (let [ev  (event :pr-monitor/budget-warning
                     {:pr/id 42 :budget/remaining 2 :budget/total 10})
          msg (sub/translate-event ev)]
      (is (= :msg/pr-monitor-budget-warning (first msg)))
      (is (= 2 (:remaining (second msg))))
      (is (= 10 (:total (second msg)))))))

(deftest translate-pr-monitor-budget-exhausted-test
  (testing "translates :pr-monitor/budget-exhausted"
    (let [ev  (event :pr-monitor/budget-exhausted {:pr/id 42})
          msg (sub/translate-event ev)]
      (is (= :msg/pr-monitor-budget-exhausted (first msg)))
      (is (= 42 (:pr-id (second msg)))))))

(deftest translate-pr-monitor-escalated-test
  (testing "translates :pr-monitor/escalated"
    (let [ev  (event :pr-monitor/escalated
                     {:pr/id 42 :escalation/reason "Human review needed"})
          msg (sub/translate-event ev)]
      (is (= :msg/pr-monitor-escalated (first msg)))
      (is (= 42 (:pr-id (second msg))))
      (is (= "Human review needed" (:reason (second msg)))))))

;------------------------------------------------------------------------------ subscription/translate-event — control-plane events

(deftest translate-control-plane-agent-discovered-test
  (testing "translates :control-plane/agent-discovered"
    (let [sid (random-uuid)
          ev  (event :control-plane/agent-discovered {:session/id sid :agent/name "coder"})
          msg (sub/translate-event ev)]
      (is (= :msg/control-plane-agent-discovered (first msg)))
      (is (= sid (:session-id (second msg)))))))

(deftest translate-control-plane-status-changed-test
  (testing "translates :control-plane/status-changed"
    (let [sid (random-uuid)
          ev  (event :control-plane/status-changed {:session/id sid :session/status :idle})
          msg (sub/translate-event ev)]
      (is (= :msg/control-plane-status-changed (first msg)))
      (is (= sid (:session-id (second msg))))
      (is (= :idle (:status (second msg)))))))

(deftest translate-unknown-event-returns-nil-test
  (testing "unknown event types return nil (not an error)"
    (is (nil? (sub/translate-event (event :something/unknown))))))

;------------------------------------------------------------------------------ update/events — PR monitor handlers

(deftest handle-pr-monitor-loop-started-test
  (testing "sets :pr/monitor-active? on matching PR"
    (let [model  (assoc (init) :pr-items [(make-pr "org/r" 1)])
          model' (events/handle-pr-monitor-loop-started model {:pr-id 1})]
      (is (true? (get-in model' [:pr-items 0 :pr/monitor-active?]))))))

(deftest handle-pr-monitor-loop-stopped-test
  (testing "clears :pr/monitor-active? and flashes"
    (let [model  (assoc (init) :pr-items [(make-pr "org/r" 1 {:pr/monitor-active? true})])
          model' (events/handle-pr-monitor-loop-stopped model {:pr-id 1 :reason "merged"})]
      (is (not (get-in model' [:pr-items 0 :pr/monitor-active?])))
      (is (some? (:flash-message model'))))))

(deftest handle-pr-monitor-budget-warning-test
  (testing "sets :pr/monitor-budget-warning? on matching PR"
    (let [model  (assoc (init) :pr-items [(make-pr "org/r" 1)])
          model' (events/handle-pr-monitor-budget-warning model {:pr-id 1 :remaining 2 :total 10})]
      (is (true? (get-in model' [:pr-items 0 :pr/monitor-budget-warning?])))
      (is (some? (:flash-message model'))))))

(deftest handle-pr-monitor-budget-exhausted-test
  (testing "sets exhausted flag, clears active flag, flashes"
    (let [model  (assoc (init) :pr-items [(make-pr "org/r" 1 {:pr/monitor-active? true})])
          model' (events/handle-pr-monitor-budget-exhausted model {:pr-id 1})]
      (is (true? (get-in model' [:pr-items 0 :pr/monitor-budget-exhausted?])))
      (is (not (get-in model' [:pr-items 0 :pr/monitor-active?])))
      (is (some? (:flash-message model'))))))

(deftest handle-pr-monitor-escalated-test
  (testing "sets escalated flag and adds attention item"
    (let [model  (assoc (init) :pr-items [(make-pr "org/r" 1)])
          model' (events/handle-pr-monitor-escalated model {:pr-id 1 :reason "needs human"})]
      (is (true? (get-in model' [:pr-items 0 :pr/monitor-escalated?])))
      (is (= 1 (count (:attention-items model'))))
      (is (= :critical (:attention/severity (first (:attention-items model'))))))))

;------------------------------------------------------------------------------ update/events — control-plane handlers

(deftest handle-control-plane-agent-discovered-insert-test
  (testing "inserts new agent session into :agent-sessions"
    (let [sid    (random-uuid)
          model' (events/handle-control-plane-agent-discovered
                  (init) {:session-id sid :agent-data {:agent/name "coder"}})]
      (is (= 1 (count (:agent-sessions model'))))
      (is (= sid (:session/id (first (:agent-sessions model'))))))))

(deftest handle-control-plane-agent-discovered-upsert-test
  (testing "merges into existing session with same session-id"
    (let [sid    (random-uuid)
          model  (assoc (init) :agent-sessions [{:session/id sid :agent/name "coder"}])
          model' (events/handle-control-plane-agent-discovered
                  model {:session-id sid :agent-data {:session/status :running}})]
      (is (= 1 (count (:agent-sessions model'))))
      (is (= :running (:session/status (first (:agent-sessions model'))))))))

(deftest handle-control-plane-status-changed-test
  (testing "updates status on matching session"
    (let [sid    (random-uuid)
          model  (assoc (init) :agent-sessions [{:session/id sid :session/status :idle}])
          model' (events/handle-control-plane-status-changed
                  model {:session-id sid :status :running})]
      (is (= :running (:session/status (first (:agent-sessions model'))))))))

(deftest handle-control-plane-decision-submitted-test
  (testing "adds warning attention item for pending decision"
    (let [did    (random-uuid)
          model' (events/handle-control-plane-decision-submitted
                  (init) {:decision-id did :data {:question "Proceed?"}})]
      (is (= 1 (count (:attention-items model'))))
      (is (= :warning (:attention/severity (first (:attention-items model'))))))))

(deftest handle-control-plane-decision-resolved-test
  (testing "removes attention item for resolved decision"
    (let [did    (random-uuid)
          item   {:attention/id          (random-uuid)
                  :attention/severity    :warning
                  :attention/summary     "Decision pending"
                  :attention/source-type :control-plane
                  :attention/source-id   did}
          model  (assoc (init) :attention-items [item])
          model' (events/handle-control-plane-decision-resolved
                  model {:decision-id did})]
      (is (= 0 (count (:attention-items model')))))))

;------------------------------------------------------------------------------ update/events — subscription status

(deftest handle-subscription-status-changed-test
  (testing "updates subscription status and last-event-at"
    (let [ts     (java.util.Date.)
          model' (events/handle-subscription-status-changed
                  (init) {:status :stale :last-event-at ts})]
      (is (= :stale (:subscription/status model')))
      (is (= ts (:subscription/last-event-at model'))))))

(deftest handle-subscription-status-defaults-connected-test
  (testing "nil status defaults to :connected"
    (let [model' (events/handle-subscription-status-changed
                  (init) {:status nil :last-event-at nil})]
      (is (= :connected (:subscription/status model'))))))
