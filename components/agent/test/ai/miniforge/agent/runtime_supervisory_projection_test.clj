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

(ns ai.miniforge.agent.runtime-supervisory-projection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.supervisory-state.interface :as supervisory]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(defn- create-stream
  []
  (event-stream/create-event-stream {:sinks []}))

(defn- invoke-task
  []
  {:task/id (random-uuid)
   :task/type :implement
   :task/title "Implement auth repair"
   :task/description "Update the auth flow to reject stale session tokens."
   :task/status :pending
   :task/inputs []
   :task/metadata {:phase-id :implement}})

(defn- invoke-context
  [stream workflow-id]
  {:event-stream stream
   :execution/id workflow-id
   :execution/current-phase :implement
   :execution/input {:title "Auth repair"
                     :description "Repair the stale-session failure in login."
                     :spec {:spec/id "auth-repair"
                            :title "Auth repair"
                            :description "Repair the stale-session failure in login."}}
   :llm-backend (agent/create-mock-llm {:content "(defn auth [] :ok)"
                                        :usage {:input-tokens 11 :output-tokens 7}
                                        :model "mock"})})

(defn- event-types
  [stream]
  (map :event/type (event-stream/get-events stream)))

(defn- first-event
  [stream event-type]
  (first (filter #(= event-type (:event/type %))
                 (event-stream/get-events stream))))

(defn- last-event
  [stream event-type]
  (last (filter #(= event-type (:event/type %))
                (event-stream/get-events stream))))

;------------------------------------------------------------------------------ Layer 1
;; Projection coverage

(deftest direct-agent-invoke-emits-control-plane-and-supervisory-events
  (testing "workflow-bound agent/invoke produces rich agent session snapshots"
    (let [stream (create-stream)
          _supervisor (supervisory/attach! stream)
          workflow-id (random-uuid)
          task (invoke-task)
          ctx (invoke-context stream workflow-id)
          impl-agent (agent/create-agent :implementer)
          result (agent/invoke impl-agent task ctx)
          types (event-types stream)
          registered (first-event stream :control-plane/agent-registered)
          heartbeat (last-event stream :control-plane/agent-heartbeat)
          agent-upserted (last-event stream :supervisory/agent-upserted)
          agent-entity (:supervisory/entity agent-upserted)]
      (is (:success result))
      (is (some #{:control-plane/agent-registered} types))
      (is (some #{:control-plane/agent-heartbeat} types))
      (is (some #{:control-plane/agent-state-changed} types))
      (is (some #{:supervisory/agent-upserted} types))
      (is (= :miniforge (:cp/vendor registered)))
      (is (= :implement (:workflow-phase (:cp/metadata registered))))
      (is (= "implement: Implement auth repair"
             (:agent-context (:cp/metadata registered))))
      (is (= "Update the auth flow to reject stale session tokens."
             (:phase-context (:cp/metadata registered))))
      (is (= :completed (:cp/status heartbeat)))
      (is (= "Auth repair"
             (get-in agent-entity [:agent/metadata :workflow-spec :title])))
      (is (= :implement (get-in agent-entity [:agent/metadata :workflow-phase])))
      (is (= :completed (:agent/status agent-entity)))
      (is (= "implement: Implement auth repair" (:agent/task agent-entity))))))
