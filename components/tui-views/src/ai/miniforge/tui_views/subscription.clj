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

(ns ai.miniforge.tui-views.subscription
  "Event stream -> TUI message bridge.

   Subscribes to the event stream and translates domain events into
   TUI messages for the Elm update loop. Handles throttling so rapid
   agent/chunk events are coalesced into 1 aggregate message per second."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.tui-views.msg :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Event -> TUI message translation

(defn workflow-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn workflow-phase
  [event]
  (or (:workflow/phase event) (:phase event)))

(defn agent-id
  [event]
  (or (:agent/id event) (:agent event)))

(defn status-type
  [event]
  (or (:status/type event) (:status-type event)))

(defn chunk-delta
  [event]
  (or (:chunk/delta event) (:delta event)))

(defn chunk-done?
  [event]
  (boolean (or (:chunk/done? event) (:done? event))))

(defn gate-id
  [event]
  (or (:gate/id event) (:gate event)))

(defn translate-event
  "Translate a single event-stream event to a TUI message vector.
   Returns nil for events that should be ignored."
  [event]
  (case (:event/type event)
    :workflow/started
    (msg/workflow-added (workflow-id event)
                        (or (get-in event [:workflow/spec :name])
                            (get-in event [:workflow-spec :name]))
                        (or (:workflow/spec event) (:workflow-spec event)))

    :workflow/phase-started
    (msg/phase-changed (workflow-id event) (workflow-phase event))

    :workflow/phase-completed
    (msg/phase-done (workflow-id event)
                    (workflow-phase event)
                    (or (:phase/outcome event)
                        (get-in event [:result :outcome]))
                    (cond-> {}
                      (:phase/artifacts event) (assoc :artifacts (:phase/artifacts event))
                      (:phase/duration-ms event) (assoc :duration-ms (:phase/duration-ms event))
                      (:phase/error event) (assoc :error (:phase/error event))
                      (:phase/tokens event) (assoc :tokens (:phase/tokens event))
                      (:phase/cost-usd event) (assoc :cost-usd (:phase/cost-usd event))))

    :agent/started
    (msg/agent-started (workflow-id event) (agent-id event)
                       (:agent/context event))

    :agent/completed
    (msg/agent-completed (workflow-id event) (agent-id event)
                         (:agent/result event))

    :agent/failed
    (msg/agent-failed (workflow-id event) (agent-id event)
                      (:agent/error event))

    :agent/status
    (msg/agent-status (workflow-id event) (agent-id event)
                      (status-type event) (:message event))

    :agent/chunk
    (msg/agent-output (workflow-id event) (agent-id event)
                      (chunk-delta event) (chunk-done? event))

    :workflow/completed
    (msg/workflow-done (workflow-id event)
                       (or (:workflow/status event) (:status event))
                       (cond-> {}
                         (:workflow/duration-ms event) (assoc :duration-ms (:workflow/duration-ms event))
                         (:workflow/evidence-bundle-id event) (assoc :evidence-bundle-id (:workflow/evidence-bundle-id event))
                         (:workflow/tokens event) (assoc :tokens (:workflow/tokens event))
                         (:workflow/cost-usd event) (assoc :cost-usd (:workflow/cost-usd event))
                         (:workflow/pr-info event) (assoc :pr-info (:workflow/pr-info event))))

    :workflow/failed
    (msg/workflow-failed (workflow-id event)
                         (or (:workflow/error-details event)
                             (:workflow/failure-reason event)
                             (:error event)))

    :gate/started
    (msg/gate-started (workflow-id event) (gate-id event))

    :gate/passed
    (msg/gate-result (workflow-id event) (gate-id event) true)

    :gate/failed
    (msg/gate-result (workflow-id event) (gate-id event) false)

    :tool/invoked
    (msg/tool-invoked (workflow-id event) (agent-id event) (:tool/id event))

    :tool/completed
    (msg/tool-completed (workflow-id event) (agent-id event) (:tool/id event))

    ;; Chain lifecycle events
    :chain/started
    (msg/chain-started (:chain/id event) (:chain/step-count event))

    :chain/step-started
    (msg/chain-step-started (:chain/id event) (:step/id event)
                            (:step/index event) (:step/workflow-id event))

    :chain/step-completed
    (msg/chain-step-completed (:chain/id event) (:step/id event)
                              (:step/index event))

    :chain/step-failed
    (msg/chain-step-failed (:chain/id event) (:step/id event)
                           (:step/index event) (:chain/error event))

    :chain/completed
    (msg/chain-completed (:chain/id event) (:chain/duration-ms event)
                         (:chain/step-count event))

    :chain/failed
    (msg/chain-failed (:chain/id event) (:chain/failed-step event)
                      (:chain/error event))

    ;; Unknown event types are ignored
    nil))

;------------------------------------------------------------------------------ Layer 1
;; Throttled subscription

(defn create-chunk-aggregator
  "Create a throttled aggregator for agent/chunk events.
   Accumulates deltas and flushes at configurable intervals."
  [dispatch-fn flush-interval-ms]
  (let [buffer (atom {})  ; {workflow-id {:delta str :agent kw}}
        running? (atom true)
        flush-fn (fn []
                   (let [buf @buffer]
                     (reset! buffer {})
                     (doseq [[wf-id {:keys [delta agent]}] buf]
                       (when (seq delta)
                         (dispatch-fn (msg/agent-output wf-id agent delta false))))))
        thread (Thread. (fn []
                          (try
                            (while @running?
                              (Thread/sleep flush-interval-ms)
                              (flush-fn))
                            (catch InterruptedException _))))]
    (.setDaemon thread true)
    (.setName thread "tui-chunk-aggregator")
    (.start thread)
    {:buffer buffer
     :running? running?
     :thread thread
     :stop! (fn []
              (reset! running? false)
              (.interrupt thread))}))

(defn buffer-chunk!
  "Buffer an agent chunk for throttled delivery."
  [aggregator workflow-id agent-id delta]
  (swap! (:buffer aggregator)
         update workflow-id
         (fn [existing]
           {:delta (str (:delta existing) (or delta ""))
            :agent agent-id})))

;------------------------------------------------------------------------------ Layer 2
;; Public subscription API

(defn subscribe-to-stream!
  "Subscribe to an event stream, translating events into TUI messages.

   Arguments:
   - stream      - Event stream atom from event-stream/create-event-stream
   - dispatch-fn - (fn [msg]) to send TUI messages into the Elm loop
   - opts        - {:throttle-ms 1000} chunk aggregation interval

   Returns: cleanup function (fn [] ...) to call on shutdown."
  [stream dispatch-fn & [{:keys [throttle-ms] :or {throttle-ms 1000}}]]
  (let [aggregator (create-chunk-aggregator dispatch-fn throttle-ms)
        sub-id :tui-subscription]
    (es/subscribe! stream sub-id
                   (fn [event]
                     (if (= :agent/chunk (:event/type event))
                       ;; Throttle chunk events
                       (buffer-chunk! aggregator
                                      (workflow-id event)
                                      (agent-id event)
                                      (chunk-delta event))
                       ;; All other events dispatch immediately
                       (when-let [msg (translate-event event)]
                         (dispatch-fn msg)))))
    ;; Return cleanup function
    (fn []
      ((:stop! aggregator))
      (es/unsubscribe! stream sub-id))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example usage
  (def stream (es/create-event-stream))
  (def messages (atom []))

  (def cleanup
    (subscribe-to-stream! stream
                          (fn [msg] (swap! messages conj msg))
                          {:throttle-ms 500}))

  ;; Publish some events
  (es/publish! stream (es/workflow-started stream (random-uuid) {:name "test"}))

  ;; Check received messages
  @messages

  ;; Cleanup
  (cleanup)

  :leave-this-here)
