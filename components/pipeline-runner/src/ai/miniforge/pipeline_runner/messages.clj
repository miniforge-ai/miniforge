(ns ai.miniforge.pipeline-runner.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/pipeline-runner/messages/en-US.edn" :pipeline-runner/messages))
