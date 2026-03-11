(ns ai.miniforge.event-stream.interface.stream
  "Event-stream lifecycle and query API."
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream lifecycle and queries

(def create-event-stream core/create-event-stream)
(def publish! core/publish!)
(def subscribe! core/subscribe!)
(def unsubscribe! core/unsubscribe!)
(def get-events core/get-events)
(def get-latest-status core/get-latest-status)
