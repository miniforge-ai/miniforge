;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.messages
  "Component-level message catalog for reliability.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a reliability message by key, with optional param substitution."
  (messages/create-translator "config/reliability/messages/en-US.edn"
                              :reliability/messages))
