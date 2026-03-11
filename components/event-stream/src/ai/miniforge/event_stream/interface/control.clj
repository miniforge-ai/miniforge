(ns ai.miniforge.event-stream.interface.control
  "Control-action API for the event stream."
  (:require
   [ai.miniforge.event-stream.control :as control]))

;------------------------------------------------------------------------------ Layer 0
;; Control actions

(def create-control-action control/create-control-action)
(def authorize-action control/authorize-action)
(def execute-control-action! control/execute-control-action!)
(def requires-approval? control/requires-approval?)
(def execute-control-action-with-approval! control/execute-control-action-with-approval!)
