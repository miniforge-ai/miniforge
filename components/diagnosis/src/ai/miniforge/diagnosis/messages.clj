;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.messages
  "Component-level message catalog for diagnosis.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a diagnosis message by key, with optional param substitution."
  (messages/create-translator "config/diagnosis/messages/en-US.edn"
                              :diagnosis/messages))
