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

(ns ai.miniforge.mcp-artifact-server.main
  "Entry point for running the MCP artifact server via `bb -m`.

   Usage:
     bb -cp components/mcp-artifact-server/src -m ai.miniforge.mcp-artifact-server.main --artifact-dir /tmp/dir"
  (:require [ai.miniforge.mcp-artifact-server.server :as server]))

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (case (first args)
        "--artifact-dir" (recur (drop 2 args)
                                (assoc opts :artifact-dir (second args)))
        (recur (rest args) opts)))))

(defn -main [& args]
  (let [opts (parse-args args)
        artifact-dir (:artifact-dir opts)]
    (when-not artifact-dir
      (binding [*out* *err*]
        (println "ERROR: --artifact-dir is required"))
      (System/exit 1))
    (server/run-server artifact-dir)))
