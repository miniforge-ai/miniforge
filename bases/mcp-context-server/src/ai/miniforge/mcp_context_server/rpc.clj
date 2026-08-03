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
(ns ai.miniforge.mcp-context-server.rpc
  "JSON-RPC 2.0 method dispatch for the MCP context server. Split from
   `server` (which keeps the stdin loop and lifecycle) so each namespace
   stays within the stratified-design layer budget.
   Babashka-compatible."
  (:require [ai.miniforge.mcp-context-server.protocol :as protocol]
            [ai.miniforge.mcp-context-server.tools :as tools]))

;------------------------------------------------------------------------------ Layer 0

;; Logging (stderr only — stdout is the JSON-RPC transport)
(defn ^{:stratum 0} log-stderr
  "Print message to stderr (stdout is reserved for JSON-RPC)."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

;; MCP dispatch
(defn ^{:stratum 0} handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "miniforge-context-server"
                :version "1.0.0"}})

(defn ^{:stratum 0} handle-tools-list [_params]
  {:tools (tools/tool-definitions)})

(defn ^{:stratum 0} handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (tools/handle-tool-call tool-name arguments)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} dispatch
  "Route a JSON-RPC method to the appropriate handler."
  [method params]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params)
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

;------------------------------------------------------------------------------ Layer 2

;; Message processing — one extracted function per message shape
(defn ^{:stratum 2} handle-request
  "Handle a JSON-RPC request (has id, expects response)."
  [id method params]
  (try
    (when-let [result (dispatch method params)]
      (protocol/write-response id result))
    (catch Exception e
      (let [code (get (ex-data e) :code -32603)]
        (log-stderr "Error handling" method ":" (ex-message e))
        (protocol/write-error id code (ex-message e))))))

(defn ^{:stratum 2} handle-notification
  "Handle a JSON-RPC notification (no id, no response)."
  [method params]
  (try
    (dispatch method params)
    (catch Exception e
      (log-stderr "Notification error:" (ex-message e)))))
