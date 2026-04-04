;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

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
