(ns ai.miniforge.event-stream.interface.listeners
  "Listener management API for the event stream."
  (:require
   [ai.miniforge.event-stream.listeners :as listeners]))

;------------------------------------------------------------------------------ Layer 0
;; Listener management

(def register-listener! listeners/register-listener!)
(def deregister-listener! listeners/deregister-listener!)
(def list-listeners listeners/list-listeners)
(def submit-annotation! listeners/submit-annotation!)
