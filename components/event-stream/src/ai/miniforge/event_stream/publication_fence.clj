;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-fence
  "Atomic workflow fencing and in-flight ownership around publication."
  (:require [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} quiesced-sentinel ::quiesced)

(defn ^{:stratum 0} workflow-quiesced? [stream event]
  (when-let [workflow-id (:workflow/id event)]
    (contains? (:quiesced-workflows @stream) workflow-id)))

(defn ^{:stratum 0} rejection-result [event reason]
  {:rejected? true
   :reason reason
   :workflow-id (:workflow/id event)
   :event-type (:event/type event)})

(defn- ^{:stratum 0} log-rejection! [logger event]
  (when logger
    (log/warn logger :event-stream :event/rejected-after-quiesce
              {:message (messages/system :delivery/quiesced)
               :data {:event-type (:event/type event)
                      :workflow-id (:workflow/id event)}})))

(defn- ^{:stratum 0} acquire-state [workflow-id state]
  (if (and workflow-id (contains? (:quiesced-workflows state) workflow-id))
    state
    (update state :in-flight inc)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} reject! [stream event]
  (log-rejection! (:logger @stream) event)
  (rejection-result event :workflow-quiesced))

(defn ^{:stratum 1} try-acquire-in-flight!
  "A refused acquire returns the identical state; a successful one increments it."
  [stream event]
  (let [[before after] (swap-vals! stream (partial acquire-state (:workflow/id event)))]
    (not (identical? before after))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-in-flight
  "Acquire atomically with the fence check; always release an acquired slot."
  [stream event body-fn]
  (if-not (try-acquire-in-flight! stream event)
    quiesced-sentinel
    (try
      (body-fn)
      (finally (swap! stream update :in-flight dec)))))

(defn ^{:stratum 2} rejection-if-quiesced [stream event]
  (when (workflow-quiesced? stream event) (reject! stream event)))

(comment
  ::workflow-fence)
