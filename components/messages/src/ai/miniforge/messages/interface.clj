(ns ai.miniforge.messages.interface
  "Shared message catalog — the one loader to rule them all.

   Components create a translator via `create-translator` and use it
   as their local `t` function. This eliminates duplicate message-loading
   implementations across the codebase."
  (:require [ai.miniforge.messages.core :as core]))

(defn load-catalog
  "Load a message catalog from an EDN classpath resource.
   Returns a delay wrapping the message map."
  [resource-path section-key]
  (core/load-catalog resource-path section-key))

(defn t
  "Look up a message by key from a catalog, with optional param substitution."
  ([catalog k] (core/t catalog k))
  ([catalog k params] (core/t catalog k params)))

(defn create-translator
  "Create a `t` function bound to a specific component's message catalog.

   Example:
     (def t (messages/create-translator
              \"config/workflow/messages/en-US.edn\"
              :workflow/messages))
     (t :some-key)
     (t :some-key {:name \"val\"})"
  [resource-path section-key]
  (core/create-translator resource-path section-key))
