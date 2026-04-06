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

(ns ai.miniforge.mcp-context-server.interface
  "Public API for the MCP context server component.

   Provides:
   - start-server: Run the MCP server (blocking stdin/stdout loop)
   - handle-tool-call: Direct tool invocation for in-process testing
   - tool-definitions: List of registered MCP tool definitions
   - register-tool! / register-handler!: Extensibility points"
  (:require [ai.miniforge.mcp-context-server.server :as server]
            [ai.miniforge.mcp-context-server.tools :as tools]))

(defn start-server
  "Start the MCP context server.

   Reads JSON-RPC 2.0 messages from stdin, writes responses to stdout.
   Blocks until stdin is closed.

   Arguments:
   - artifact-dir — directory path for context cache and miss tracking"
  [artifact-dir]
  (server/run-server artifact-dir))

(defn handle-tool-call
  "Invoke a tool handler directly (for testing without JSON-RPC).

   Arguments:
   - tool-name — string name of the tool (e.g. \"context_read\")
   - params — map of tool arguments (string keys)

   Returns {:content [{:type \"text\" :text message}]}
   Throws on unknown tool or validation failure."
  [tool-name params]
  (tools/handle-tool-call tool-name params))

(defn tool-definitions
  "List of MCP tool definitions registered by this component."
  []
  (tools/tool-definitions))

(defn tool-registry
  "Full tool registry map (tool-name → tool config)."
  []
  (tools/tool-registry))

(def register-tool!
  "Register a new tool at runtime. See tools/register-tool! for details."
  tools/register-tool!)

(def register-handler!
  "Register a direct handler function (no artifact). See tools/register-handler!."
  tools/register-handler!)
