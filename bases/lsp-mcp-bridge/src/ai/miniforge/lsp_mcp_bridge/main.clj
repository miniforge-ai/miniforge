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

(ns ai.miniforge.lsp-mcp-bridge.main
  "Entry point for the LSP-to-MCP bridge.

   Spawned by Claude Code (or any MCP client) as a subprocess.
   Reads MCP JSON-RPC from stdin, manages LSP servers, writes
   responses to stdout.

   Usage:
     bb --jar miniforge.jar -m ai.miniforge.lsp-mcp-bridge.main
     bb lsp-mcp-bridge  (dev mode)"
  (:require
   [ai.miniforge.lsp-mcp-bridge.config :as config]
   [ai.miniforge.lsp-mcp-bridge.lsp.manager :as manager]
   [ai.miniforge.lsp-mcp-bridge.mcp.server :as server]))

(defn -main
  "Main entry point."
  [& _args]
  (let [project-dir (or (System/getenv "MINIFORGE_PROJECT_DIR")
                        (System/getProperty "user.dir"))
        log-level   (or (System/getenv "MINIFORGE_LOG_LEVEL") "info")]

    (binding [*out* *err*]
      (println "miniforge-lsp: loading config from" project-dir)
      (println "miniforge-lsp: log level" log-level))

    ;; Load configuration
    (let [cfg (config/load-config project-dir)
          mgr (manager/create-manager cfg project-dir)]

      (binding [*out* *err*]
        (println "miniforge-lsp:" (count (:tools cfg)) "LSP tool configs loaded")
        (println "miniforge-lsp: languages supported:"
                 (vec (keys (:lang-index cfg)))))

      ;; Register shutdown hook
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (binding [*out* *err*]
                                     (println "miniforge-lsp: shutting down..."))
                                   (manager/stop-all-servers mgr)
                                   (binding [*out* *err*]
                                     (println "miniforge-lsp: shutdown complete")))))

      ;; Start MCP server (blocking)
      (server/start-server mgr))))
