;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.supervisory-state.messages
  "Component-level message catalog for supervisory-state.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a supervisory-state message by key, with optional param substitution."
  (messages/create-translator "config/supervisory-state/messages/en-US.edn"
                              :supervisory-state/messages))
