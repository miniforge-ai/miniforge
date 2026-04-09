(ns ai.miniforge.data-foundry.connector-gitlab.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/connector-gitlab/messages/en-US.edn" :connector-gitlab/messages))
