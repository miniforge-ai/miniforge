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
(ns ai.miniforge.mcp-context-server.codex-tool
  "The consider_situation MCP tool — the PULL side of the Thesium Codex
   consumption surface (Codex SPEC T1 §7.1). Rendering lives in the codex
   component (ai.miniforge.codex.render) so this tool's output and the
   phase-start blackboard pin (§7.4, the PUSH side) are byte-identical.

   Fails closed: no configured codex path, an unreadable codex, or an
   unmatched situation renders as an anomaly with a reason — never an
   empty success."
  (:require [ai.miniforge.codex.interface :as codex]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} codex-path
  "Codex directory: explicit param wins, else MINIFORGE_CODEX_PATH.
   A non-string param is treated as absent — this tool fails closed with a
   codex anomaly, never a JSON-RPC error from str/trim on a number."
  [params]
  (let [p (get params "codex_path")]
    (or (when (string? p) (not-empty (str/trim p)))
        (some-> (System/getenv "MINIFORGE_CODEX_PATH") str/trim not-empty))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} handle-consider-situation
  "MCP handler for consider_situation."
  [params]
  (let [text (if-let [path (codex-path params)]
               (codex/render-response (codex/consider path (get params "situation")))
               (codex/render-response (codex/unconfigured-anomaly)))]
    {:content [{:type "text" :text text}]}))
