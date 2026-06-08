(ns ai.miniforge.phase.messages
  "System-locale message translator for the phase component.

   Usage:
     (t :anomaly/graph-unresolved-target)
     (t :anomaly/graph-validation-failed {:count 3})

   Keys are defined in:
     resources/config/phase/messages/system.edn"
  (:require [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def t
  "Look up a phase system message by key, with optional param substitution.
   Delegates to the shared messages component."
  (messages/create-translator "config/phase/messages/system.edn"
                              :phase/system))
