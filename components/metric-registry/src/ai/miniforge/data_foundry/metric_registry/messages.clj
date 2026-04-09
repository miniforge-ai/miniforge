(ns ai.miniforge.data-foundry.metric-registry.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/metric-registry/messages/en-US.edn" :metric-registry/messages))
