(ns ai.miniforge.connector-edgar.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/connector-edgar/messages/en-US.edn" :connector-edgar/messages))
