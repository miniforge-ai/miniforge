(ns ai.miniforge.data-quality.messages
  "Message catalog — delegates to ai.miniforge.messages."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up a message by key, with optional param substitution."
  (messages/create-translator "config/data-quality/messages/en-US.edn" :data-quality/messages))
