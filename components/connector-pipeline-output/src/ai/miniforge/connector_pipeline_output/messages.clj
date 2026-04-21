(ns ai.miniforge.connector-pipeline-output.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/connector-pipeline-output/messages/en-US.edn" :connector-pipeline-output/messages))
