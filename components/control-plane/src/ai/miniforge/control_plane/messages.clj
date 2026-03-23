(ns ai.miniforge.control-plane.messages
  "Component-level message catalog for control-plane.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a control-plane message by key, with optional param substitution."
  (messages/create-translator "config/control-plane/messages/en-US.edn"
                              :control-plane/messages))
