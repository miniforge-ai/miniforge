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

(ns ai.miniforge.mcp-artifact-server.protocol
  "JSON-RPC 2.0 protocol helpers for MCP communication.
   Babashka-compatible (cheshire only, no JVM-only deps)."
  (:require [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Response constructors

(defn write-response
  "Write a JSON-RPC 2.0 success response to stdout."
  [id result]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id :result result})]
    (println msg)
    (flush)))

(defn write-error
  "Write a JSON-RPC 2.0 error response to stdout."
  [id code message]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id
                                   :error {:code code :message message}})]
    (println msg)
    (flush)))

;------------------------------------------------------------------------------ Layer 0
;; Message parsing

(defn parse-message
  "Parse a JSON-RPC message string. Returns nil on parse failure."
  [line]
  (try
    (json/parse-string line)
    (catch Exception _
      nil)))
