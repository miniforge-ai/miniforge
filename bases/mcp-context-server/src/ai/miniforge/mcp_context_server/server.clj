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
(ns ai.miniforge.mcp-context-server.server
  "Main MCP stdin/stdout loop and lifecycle. JSON-RPC method dispatch lives
   in `rpc`.
   Babashka-compatible."
  (:require [ai.miniforge.mcp-context-server.codex-tool :as codex-tool]
            [ai.miniforge.mcp-context-server.context-cache :as context-cache]
            [ai.miniforge.mcp-context-server.protocol :as protocol]
            [ai.miniforge.mcp-context-server.rpc :as rpc]
            [ai.miniforge.mcp-context-server.tools :as tools]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} register-context-handlers!
  "Register tool handlers in the tool handler registry."
  []
  (tools/register-handler! :context-read  context-cache/handle-context-read)
  (tools/register-handler! :context-grep  context-cache/handle-context-grep)
  (tools/register-handler! :context-glob  context-cache/handle-context-glob)
  (tools/register-handler! :context-write context-cache/handle-context-write)
  (tools/register-handler! :consider-situation codex-tool/handle-consider-situation))

(defn ^{:stratum 0} process-message
  "Parse and dispatch a single JSON-RPC message."
  [line]
  (if-let [msg (protocol/parse-message line)]
    (let [id     (get msg "id")
          method (get msg "method")
          params (get msg "params" {})]
      (if id
        (rpc/handle-request id method params)
        (rpc/handle-notification method params)))
    (rpc/log-stderr "Parse error: invalid JSON")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} process-line
  "Process a single line from stdin. Skips blank lines."
  [line]
  (when-not (str/blank? line)
    (try
      (process-message line)
      (catch Exception e
        (rpc/log-stderr "Parse error:" (ex-message e))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-server
  "Run the MCP context server loop, reading JSON-RPC messages from stdin.

   Lifecycle:
   1. Load context cache from artifact-dir (if present)
   2. Register tool handlers
   3. Process JSON-RPC messages until stdin closes
   4. Flush accumulated cache misses to artifact-dir

   Arguments:
   - artifact-dir — directory path for context cache and miss tracking
   - source-root — repo root for read-side filesystem fallback
   - workdir — write target for context_write (the agent's worktree); defaults
     to source-root when absent"
  ([artifact-dir source-root] (run-server artifact-dir source-root nil))
  ([artifact-dir source-root workdir]
   (rpc/log-stderr "miniforge-context MCP server started, artifact-dir:" artifact-dir
                   "source-root:" source-root "workdir:" workdir)
   (context-cache/load-cache! artifact-dir source-root)
   (context-cache/set-workdir! workdir)
   (register-context-handlers!)
   (try
     (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
       (loop []
         (when-let [line (.readLine reader)]
           (process-line line)
           (recur))))
     (finally
       (context-cache/flush-misses! artifact-dir)
       ;; Persist the accumulated cache (incl. read-through additions) to the
       ;; worktree so the next phase loads it — cross-phase write-through.
       (context-cache/save-cache!)))))
