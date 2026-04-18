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

(ns ai.miniforge.supervisory-state.attention-test
  "Tests for attention derivation (N5-delta-1 §5.1)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.supervisory-state.attention :as attention]
   [ai.miniforge.supervisory-state.schema :as schema]))

(defn- workflow
  ([id status] (workflow id status (java.util.Date.)))
  ([id status updated-at]
   {:workflow-run/id             id
    :workflow-run/workflow-key   (str "wf-" id)
    :workflow-run/intent         "test"
    :workflow-run/status         status
    :workflow-run/current-phase  :implement
    :workflow-run/started-at     updated-at
    :workflow-run/updated-at     updated-at
    :workflow-run/trigger-source :cli
    :workflow-run/correlation-id id}))

(defn- mk-agent
  [id status]
  {:agent/id id
   :agent/vendor "claude-code"
   :agent/external-id ""
   :agent/name "a"
   :agent/status status
   :agent/capabilities []
   :agent/heartbeat-interval-ms 30000
   :agent/metadata {}
   :agent/tags []
   :agent/registered-at (java.util.Date.)
   :agent/last-heartbeat (java.util.Date.)})

;------------------------------------------------------------------------------ Individual rules

(deftest failed-workflow-yields-critical
  (let [id (random-uuid)
        table (assoc-in schema/empty-table [:workflows id] (workflow id :failed))
        items (attention/derive-seq table)]
    (is (= 1 (count items)))
    (is (= :critical (:attention/severity (first items))))))

(deftest completed-workflow-yields-info
  (let [id (random-uuid)
        table (assoc-in schema/empty-table [:workflows id] (workflow id :completed))
        items (attention/derive-seq table)]
    (is (some #(= :info (:attention/severity %)) items))))

(deftest stale-running-workflow-yields-warning
  (let [id    (random-uuid)
        stale (java.util.Date. (long 0))  ; epoch → definitely stale
        table (assoc-in schema/empty-table [:workflows id]
                        (workflow id :running stale))
        items (attention/derive-seq table)]
    (is (some #(and (= :warning (:attention/severity %))
                    (= :workflow (:attention/source-type %)))
              items))))

(deftest blocked-agent-yields-warning
  (let [id (random-uuid)
        table (assoc-in schema/empty-table [:agents id] (mk-agent id :blocked))
        items (attention/derive-seq table)]
    (is (some #(and (= :warning (:attention/severity %))
                    (= :agent (:attention/source-type %)))
              items))))

(deftest failed-agent-yields-critical
  (let [id (random-uuid)
        table (assoc-in schema/empty-table [:agents id] (mk-agent id :failed))
        items (attention/derive-seq table)]
    (is (some #(and (= :critical (:attention/severity %))
                    (= :agent (:attention/source-type %)))
              items))))

;------------------------------------------------------------------------------ Determinism

(deftest attention-id-is-stable-across-derivations
  (let [id (random-uuid)
        table (assoc-in schema/empty-table [:workflows id] (workflow id :failed))
        derive-id #(-> table attention/derive-seq first :attention/id)]
    (is (= (derive-id) (derive-id))
        "same signal must yield same id to prevent flicker in consumers")))

;------------------------------------------------------------------------------ Ordering

(deftest items-sorted-critical-warning-info
  (let [table (-> schema/empty-table
                  (assoc-in [:workflows (random-uuid)] (workflow (random-uuid) :failed))
                  (assoc-in [:workflows (random-uuid)] (workflow (random-uuid) :completed))
                  (assoc-in [:agents (random-uuid)] (mk-agent (random-uuid) :blocked)))
        sevs  (map :attention/severity (attention/derive-seq table))]
    (is (= [:critical :warning :info] (distinct sevs))
        "critical before warning before info")))

(deftest empty-table-yields-no-items
  (is (empty? (attention/derive-seq schema/empty-table))))
