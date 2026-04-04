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

(ns ai.miniforge.mcp-artifact-server.server
  "Main MCP JSON-RPC 2.0 stdin/stdout loop.
   Babashka-compatible."
  (:require [ai.miniforge.mcp-artifact-server.context-cache :as context-cache]
            [ai.miniforge.mcp-artifact-server.protocol :as protocol]
            [ai.miniforge.mcp-artifact-server.tools :as tools]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Artifact persistence

(defn write-artifact!
  "Write an artifact EDN map to the artifact directory. Returns the path."
  [artifact-dir artifact]
  (let [path (str artifact-dir "/artifact.edn")]
    (spit path (pr-str artifact))
    path))

;------------------------------------------------------------------------------ Layer 0
;; Logging (stderr only — stdout is the JSON-RPC transport)

(defn log-stderr
  "Print message to stderr (stdout is reserved for JSON-RPC)."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

;------------------------------------------------------------------------------ Layer 1
;; MCP dispatch

(defn handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "miniforge-artifact-server"
                :version "3.0.0"}})

(defn handle-tools-list [_params]
  {:tools (tools/tool-definitions)})

(defn handle-tools-call [params artifact-dir]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (tools/handle-tool-call tool-name arguments #(write-artifact! artifact-dir %))))

(defn dispatch
  "Route a JSON-RPC method to the appropriate handler."
  [method params artifact-dir]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params artifact-dir)
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

;------------------------------------------------------------------------------ Layer 1
;; Message processing — one extracted function per message shape

(defn handle-request
  "Handle a JSON-RPC request (has id, expects response)."
  [id method params artifact-dir]
  (try
    (when-let [result (dispatch method params artifact-dir)]
      (protocol/write-response id result))
    (catch Exception e
      (let [code (or (:code (ex-data e)) -32603)]
        (log-stderr "Error handling" method ":" (ex-message e))
        (protocol/write-error id code (ex-message e))))))

(defn handle-notification
  "Handle a JSON-RPC notification (no id, no response)."
  [method params artifact-dir]
  (try
    (dispatch method params artifact-dir)
    (catch Exception e
      (log-stderr "Notification error:" (ex-message e)))))

(defn process-message
  "Parse and dispatch a single JSON-RPC message."
  [line artifact-dir]
  (if-let [msg (protocol/parse-message line)]
    (let [id     (get msg "id")
          method (get msg "method")
          params (get msg "params" {})]
      (if id
        (handle-request id method params artifact-dir)
        (handle-notification method params artifact-dir)))
    (log-stderr "Parse error: invalid JSON")))

(defn process-line
  "Process a single line from stdin. Skips blank lines."
  [line artifact-dir]
  (when-not (str/blank? line)
    (try
      (process-message line artifact-dir)
      (catch Exception e
        (log-stderr "Parse error:" (ex-message e))))))

;------------------------------------------------------------------------------ Layer 2
;; Main loop

(defn- register-context-handlers!
  "Register context-cache tool handlers in the tool handler registry."
  []
  (tools/register-handler! :context-read  context-cache/handle-context-read)
  (tools/register-handler! :context-grep  context-cache/handle-context-grep)
  (tools/register-handler! :context-glob  context-cache/handle-context-glob))

(defn run-server
  "Run the MCP server loop, reading JSON-RPC messages from stdin.

   Lifecycle:
   1. Load context cache from artifact-dir (if present)
   2. Register context-cache tool handlers
   3. Process JSON-RPC messages until stdin closes
   4. Flush accumulated cache misses to artifact-dir

   Arguments:
   - artifact-dir — directory path for artifact persistence"
  [artifact-dir]
  (log-stderr "miniforge-artifact MCP server started, artifact-dir:" artifact-dir)
  (context-cache/load-cache! artifact-dir)
  (register-context-handlers!)
  (try
    (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
      (loop []
        (when-let [line (.readLine reader)]
          (process-line line artifact-dir)
          (recur))))
    (finally
      (context-cache/flush-misses! artifact-dir))))
