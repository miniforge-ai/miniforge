(ns ai.miniforge.release-executor.messages
  "Component-level message catalog for release-executor.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Message lookup

(def t
  "Look up a release-executor message by key, with optional param substitution."
  (messages/create-translator "config/release-executor/messages/en-US.edn"
                              :release-executor/messages))
