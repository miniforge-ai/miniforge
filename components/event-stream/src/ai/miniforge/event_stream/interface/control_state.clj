(ns ai.miniforge.event-stream.interface.control-state
  "Control-state helpers for the event-stream public API."
  (:require
   [ai.miniforge.event-stream.control :as control]))

;------------------------------------------------------------------------------ Layer 0
;; Control state primitives

(def create-control-state control/create-control-state)
(def pause! control/pause!)
(def resume! control/resume!)
(def cancel! control/cancel!)
(def paused? control/paused?)
(def cancelled? control/cancelled?)
