;; Copyright 2025 miniforge.ai
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
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Event -> TUI message translation

(defn- translate-event
  "Translate a single event-stream event to a TUI message vector.
   Returns nil for events that should be ignored."
  [event]
  (case (:event/type event)
    :workflow/started
    [:msg/workflow-added {:workflow-id (:workflow/id event)
                          :name (get-in event [:workflow/spec :name])
                          :spec (:workflow/spec event)}]

    :workflow/phase-started
    [:msg/phase-changed {:workflow-id (:workflow/id event)
                          :phase (:workflow/phase event)}]

    :workflow/phase-completed
    [:msg/phase-done {:workflow-id (:workflow/id event)
                       :phase (:workflow/phase event)
                       :outcome (get-in event [:result :outcome])}]

    :agent/status
    [:msg/agent-status {:workflow-id (:workflow/id event)
                         :agent (:agent/id event)
                         :status (:status-type event)
                         :message (:message event)}]

    :agent/chunk
    [:msg/agent-output {:workflow-id (:workflow/id event)
                         :agent (:agent/id event)
                         :delta (:delta event)
                         :done? (:done? event)}]

    :workflow/completed
    [:msg/workflow-done {:workflow-id (:workflow/id event)
                          :status (:workflow/status event)}]

    :workflow/failed
    [:msg/workflow-failed {:workflow-id (:workflow/id event)
                            :error (:error event)}]

    :gate/passed
    [:msg/gate-result {:workflow-id (:workflow/id event)
                        :gate (:gate event)
                        :passed? true}]

    :gate/failed
    [:msg/gate-result {:workflow-id (:workflow/id event)
                        :gate (:gate event)
                        :passed? false}]

    ;; Unknown event types are ignored
    nil))

;------------------------------------------------------------------------------ Layer 1
;; Throttled subscription

(defn- create-chunk-aggregator
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
                         (dispatch-fn [:msg/agent-output
                                       {:workflow-id wf-id
                                        :agent agent
                                        :delta delta
                                        :done? false}])))))
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

(defn- buffer-chunk!
  "Buffer an agent chunk for throttled delivery."
  [aggregator workflow-id agent-id delta]
  (swap! (:buffer aggregator)
         update workflow-id
         (fn [existing]
           {:delta (str (:delta existing) delta)
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
                                      (:workflow/id event)
                                      (:agent/id event)
                                      (:delta event))
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
