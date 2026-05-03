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

(ns ai.miniforge.agent.supervisory-bridge
  "Bridge direct workflow agent invocations into the control-plane event family.

   Live workflow phases invoke internal agents directly, bypassing the polling
   control-plane orchestrator that already emits `:control-plane/agent-*`
   events. Supervisory-state projects AgentSession entities from that event
   family, so this bridge emits equivalent events for direct in-process agents
  when a workflow event-stream is present."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.event-stream.interface :as events])
  (:import
   (java.nio ByteBuffer)
   (java.security MessageDigest)
   (java.util UUID)))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private direct-agent-vendor
  :miniforge)

(def ^:private default-heartbeat-interval-ms
  30000)

(def ^:private agent-session-namespace
  (UUID/fromString "4882f381-e57d-582f-95ab-3a9b36e11df8"))

;------------------------------------------------------------------------------ Layer 0
;; Core helpers

(defn- uuid-v5
  "Deterministically derive a UUID v5 from a namespace UUID and string key."
  [^UUID ns-uuid ^String key]
  (let [digest (MessageDigest/getInstance "SHA-1")
        ns-buf (ByteBuffer/wrap (byte-array 16))]
    (.putLong ns-buf (.getMostSignificantBits ns-uuid))
    (.putLong ns-buf (.getLeastSignificantBits ns-uuid))
    (.update digest (.array ns-buf))
    (.update digest (.getBytes key "UTF-8"))
    (let [hash (.digest digest)
          msb-raw (.getLong (ByteBuffer/wrap hash 0 8))
          lsb-raw (.getLong (ByteBuffer/wrap hash 8 8))
          hi (bit-or (bit-and msb-raw (unchecked-long -61441)) 0x5000)
          lo (bit-or (bit-and lsb-raw 0x3FFFFFFFFFFFFFFF) Long/MIN_VALUE)]
      (UUID. hi lo))))

(defn- workflow-id
  [context]
  (or (:execution/id context)
      (:workflow/id context)
      (:workflow-id context)))

(defn- event-stream
  [context]
  (or (:event-stream context)
      (:execution/event-stream context)))

(defn- direct-agent-role
  [agent]
  (or (:role agent) :agent))

(defn- workflow-phase
  [task context]
  (or (get-in task [:task/metadata :phase-id])
      (:task/type task)
      (:execution/current-phase context)
      (:workflow/phase context)))

(defn- task-identity-fragment
  [role task]
  (or (some-> (:task/id task) str)
      (some-> (get-in task [:task/metadata :dag-task-id]) str)
      (:task/title task)
      (:task/description task)
      (name role)))

(defn- session-id
  [agent task context]
  (when-let [wf-id (workflow-id context)]
    (let [role (direct-agent-role agent)
          phase (workflow-phase task context)
          task-fragment (task-identity-fragment role task)
          session-key (str wf-id "|" (name role) "|" (name (or phase :unknown)) "|" task-fragment)]
      (uuid-v5 agent-session-namespace session-key))))

(defn- task-summary
  [task]
  (let [task-type (some-> (:task/type task) name)
        title (:task/title task)]
    (cond
      (and task-type (seq title)) (str task-type ": " title)
      (seq title) title
      task-type task-type
      :else nil)))

(defn- phase-context
  [task]
  (or (:task/description task)
      (:task/title task)))

(defn- workflow-spec
  [context]
  (or (:workflow/spec context)
      (:workflow-spec context)
      (get-in context [:execution/workflow :workflow/spec])
      (get-in context [:execution/input :workflow-spec])
      (get-in context [:execution/input :workflow/spec])
      (get-in context [:execution/input :spec])
      (let [input (:execution/input context)
            title (:title input)
            description (:description input)]
        (when (or title description)
          (cond-> {}
            title (assoc :title title)
            description (assoc :description description))))))

(defn- registration-metadata
  [agent task context]
  (let [phase (workflow-phase task context)
        spec (workflow-spec context)
        agent-summary (task-summary task)
        phase-summary (phase-context task)]
    (cond-> {:workflow-id (workflow-id context)}
      spec (assoc :workflow-spec spec)
      phase (assoc :workflow-phase phase)
      agent-summary (assoc :agent-context agent-summary)
      phase-summary (assoc :phase-context phase-summary)
      (get-in agent [:config :model]) (assoc :model (get-in agent [:config :model])))))

(defn- registration-options
  [agent task context session-id-value]
  (let [role (direct-agent-role agent)
        capabilities (or (:capabilities agent)
                         (get core/role-capabilities role #{}))
        tags (cond-> [(name role) "workflow"]
               (workflow-phase task context) (conj (name (workflow-phase task context))))]
    {:name (name role)
     :external-id (str session-id-value)
     :capabilities (sort-by name capabilities)
     :metadata (registration-metadata agent task context)
     :tags tags
     :heartbeat-interval-ms default-heartbeat-interval-ms}))

(defn- executable-context?
  [context]
  (and (event-stream context)
       (workflow-id context)))

(defn- result-failed?
  [result]
  (boolean
   (or (contains? #{:error :failed :failure} (:status result))
       (false? (:success result))
       (false? (:success? result))
       (:error result))))

(defn- completion-status
  [result]
  (if (result-failed? result) :failed :completed))

(defn- heartbeat-options
  [task result]
  (cond-> {}
    (task-summary task) (assoc :task (task-summary task))
    (:metrics result) (assoc :metrics (:metrics result))))

(defn- emit-started!
  [agent task context session-id-value]
  (let [stream (event-stream context)
        wf-id (workflow-id context)]
    (events/publish! stream
                     (events/cp-agent-registered
                      stream
                      wf-id
                      session-id-value
                      direct-agent-vendor
                      (registration-options agent task context session-id-value)))
    (events/publish! stream
                     (events/cp-agent-state-changed
                      stream
                      wf-id
                      session-id-value
                      :idle
                      :executing))
    (events/publish! stream
                     (events/cp-agent-heartbeat
                      stream
                      wf-id
                      session-id-value
                      :executing
                      {:task (task-summary task)}))))

(defn- emit-finished!
  [task context session-id-value result]
  (let [stream (event-stream context)
        wf-id (workflow-id context)
        status (completion-status result)]
    (events/publish! stream
                     (events/cp-agent-heartbeat
                      stream
                      wf-id
                      session-id-value
                      status
                      (heartbeat-options task result)))
    (events/publish! stream
                     (events/cp-agent-state-changed
                      stream
                      wf-id
                      session-id-value
                      :executing
                      status))))

(defn- emit-failed!
  [task context session-id-value]
  (let [stream (event-stream context)
        wf-id (workflow-id context)]
    (events/publish! stream
                     (events/cp-agent-heartbeat
                      stream
                      wf-id
                      session-id-value
                      :failed
                      {:task (task-summary task)}))
    (events/publish! stream
                     (events/cp-agent-state-changed
                      stream
                      wf-id
                      session-id-value
                      :executing
                      :failed))))

;------------------------------------------------------------------------------ Layer 1
;; Public wrapper

(defn invoke-with-projection
  "Run `invoke-fn` while mirroring the invocation into `:control-plane/agent-*`
   events when the context is bound to a workflow event-stream."
  [agent task context invoke-fn]
  (if-not (executable-context? context)
    (invoke-fn)
    (let [session-id-value (session-id agent task context)]
      (if-not session-id-value
        (invoke-fn)
        (do
          (emit-started! agent task context session-id-value)
          (try
            (let [result (invoke-fn)]
              (emit-finished! task context session-id-value result)
              result)
            (catch Exception e
              (emit-failed! task context session-id-value)
              (throw e))))))))
