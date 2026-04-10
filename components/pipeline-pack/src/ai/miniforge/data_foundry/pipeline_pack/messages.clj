(ns ai.miniforge.data-foundry.pipeline-pack.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/pipeline-pack/messages/en-US.edn" :pipeline-pack/messages))
