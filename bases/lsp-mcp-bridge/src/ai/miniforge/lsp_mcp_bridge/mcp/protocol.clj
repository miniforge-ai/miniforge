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

(ns ai.miniforge.lsp-mcp-bridge.mcp.protocol
  "MCP (Model Context Protocol) JSON-RPC message builders.

   The MCP protocol uses JSON-RPC 2.0 over stdio with newline-delimited JSON.
   This is distinct from LSP which uses Content-Length headers.

   Layer 0: Constants and message IDs
   Layer 1: Message builders (request, response, notification)
   Layer 2: Message I/O (JSON lines — read/write)
   Layer 3: MCP-specific response builders"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and message IDs

(def jsonrpc-version "2.0")

(def mcp-protocol-version "2024-11-05")

(def mcp-server-info
  {:name "miniforge-lsp"
   :version "1.0.0"})

;------------------------------------------------------------------------------ Layer 1
;; Message builders

(defn response
  "Build a JSON-RPC response message.

   Arguments:
   - id     - Request ID to respond to
   - result - Result value"
  [id result]
  {:id id
   :jsonrpc jsonrpc-version
   :result result})

(defn error-response
  "Build a JSON-RPC error response message.

   Arguments:
   - id      - Request ID to respond to
   - code    - Error code (integer)
   - message - Error message
   - data    - Optional additional data"
  ([id code message]
   (error-response id code message nil))
  ([id code message data]
   (cond-> {:id id
            :jsonrpc jsonrpc-version
            :error {:code code
                    :message message}}
     data (assoc-in [:error :data] data))))

(defn notification
  "Build a JSON-RPC notification message (no response expected).

   Arguments:
   - method - Method name
   - params - Parameters map (optional)"
  ([method]
   (notification method nil))
  ([method params]
   (cond-> {:jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

;------------------------------------------------------------------------------ Layer 2
;; Message I/O — JSON lines (newline-delimited JSON)

(defn write-message
  "Write an MCP message as a JSON line to a writer.
   MCP stdio transport uses newline-delimited JSON (NOT Content-Length headers)."
  [^java.io.Writer writer msg]
  (let [json-str (json/generate-string msg)]
    (.write writer json-str)
    (.write writer "\n")
    (.flush writer)))

(defn read-message
  "Read an MCP message (JSON line) from a reader.
   Returns the parsed message map, or nil on EOF."
  [^java.io.BufferedReader reader]
  (try
    (when-let [line (.readLine reader)]
      (when-not (str/blank? line)
        (json/parse-string line true)))
    (catch Exception _e
      nil)))

(defn request?
  "Check if a message is a request (has id and method)."
  [msg]
  (and (contains? msg :id)
       (contains? msg :method)))

(defn response?
  "Check if a message is a response (has id, no method)."
  [msg]
  (and (contains? msg :id)
       (not (contains? msg :method))))

(defn notification?
  "Check if a message is a notification (no id, has method)."
  [msg]
  (and (not (contains? msg :id))
       (contains? msg :method)))

;------------------------------------------------------------------------------ Layer 3
;; MCP-specific response builders

(defn initialize-result
  "Build the result for an MCP initialize response."
  []
  {:protocolVersion mcp-protocol-version
   :capabilities {:tools {:listChanged false}}
   :serverInfo mcp-server-info})

(defn tools-list-result
  "Build the result for a tools/list response.

   Arguments:
   - tools - Vector of tool definition maps with :name, :description, :inputSchema"
  [tools]
  {:tools tools})

(defn tool-call-result
  "Build a successful tool call result.

   Arguments:
   - content - String content to return"
  [content]
  {:content [{:type "text"
              :text content}]})

(defn tool-call-error
  "Build an error tool call result.

   Arguments:
   - message - Error message string"
  [message]
  {:content [{:type "text"
              :text message}]
   :isError true})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Build responses
  (response 1 (initialize-result))
  (response 2 (tools-list-result [{:name "lsp_hover" :description "..." :inputSchema {}}]))
  (response 3 (tool-call-result "hover info here"))
  (error-response 4 -32600 "Invalid request")

  :leave-this-here)
