(ns ai.miniforge.event-stream.interface.callbacks
  "Streaming callback helpers for the event-stream public API."
  (:require
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Convenience callbacks

(defn create-streaming-callback
  [stream-atom workflow-id agent-id & [opts]]
  (let [{:keys [print? quiet?]} opts]
    (fn [{:keys [delta done? tool-use heartbeat]}]
      (cond
        tool-use
        (when stream-atom
          (stream/publish! stream-atom
                           (events/agent-status stream-atom workflow-id agent-id
                                                :tool-calling "Agent calling tool")))

        heartbeat
        nil

        :else
        (do
          (when (and print? (not quiet?) delta (not (empty? delta)))
            (print delta)
            (flush))
          (when stream-atom
            (stream/publish! stream-atom
                             (events/agent-chunk stream-atom workflow-id agent-id
                                                 (or delta "") done?))))))))
