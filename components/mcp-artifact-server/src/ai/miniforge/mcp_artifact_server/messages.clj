(ns ai.miniforge.mcp-artifact-server.messages
  "Component-level message catalog for mcp-artifact-server.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def t
  "Look up an mcp-artifact-server message by key, with optional param substitution."
  (messages/create-translator "config/mcp-artifact-server/messages/en-US.edn"
                              :mcp-artifact-server/messages))
