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
(ns ai.miniforge.phase-software-factory.codex-pin
  "The Thesium Codex blackboard pin — PUSH delivery (Codex SPEC T1 §7.3/§7.4).

   Agents are not relied on to pull: a confident agent does not ask what it
   is missing, and warnings are needed precisely when confidence is
   unwarranted. So at phase start the orchestrator consults the codex for
   the phase's situation and pins the result as a synthetic
   `:task/existing-files` entry — the one channel that is both
   prompt-visible and MCP-cache-fetchable. The pin is the consultation
   RESULT, ephemeral and per-run; the codex itself is never cached onto the
   blackboard (§7.4.1).

   No configured codex (MINIFORGE_CODEX_PATH unset) means the capability is
   off: no pin, no noise. A configured codex that fails to answer is logged
   as a warning — that is a gap the operator should see."
  (:require [ai.miniforge.codex.interface :as codex]
            [ai.miniforge.logging.interface :as log]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pin-path
  "Virtual path of the pinned artifact. It never exists on disk, which is
   what makes it stable in the MCP context cache: staleness invalidation
   only fires for paths with an on-disk file."
  ".miniforge/codex-consider.md")

(def ^{:stratum 0} phase->situation
  "Which codex situation each phase enters at. This map IS the push
   mechanism's routing: the orchestrator supplies the situation, the codex
   supplies the worries. Only phases whose task builders actually carry
   `:task/existing-files` are listed — review's channel is `:task/artifact`,
   so its pin (situation: quality-signal-might-be-lying) waits until the
   reviewer prompt assembly grows a slot for it."
  {:implement "changing-one-side-of-a-boundary"
   :plan      "about-to-commit-consequential"})

(defn- ^{:stratum 0} configured-codex-dir []
  (some-> (System/getenv "MINIFORGE_CODEX_PATH") str/trim not-empty))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pin-file
  "The pin for `phase` as an existing-files entry {:path :content}, or nil
   when the codex is unconfigured, the phase has no mapped situation, or
   the consultation failed. A configured codex that cannot answer is worth
   a warning, ALWAYS: through the logger when one is given, else stderr —
   a nil logger must not turn the failure silent (plan has no logger)."
  ([phase logger] (pin-file phase logger (configured-codex-dir)))
  ([phase logger codex-dir]
   (when codex-dir
     (when-let [situation (get phase->situation phase)]
       (let [entry (codex/pin-entry codex-dir situation pin-path)]
         (if (:codex/anomaly entry)
           (do (if logger
                 (log/warn logger phase :codex/pin-skipped
                           {:data {:anomaly (:codex/anomaly entry)
                                   :reason  (:codex/reason entry)}})
                 (binding [*out* *err*]
                   (println "WARN: codex pin skipped for" (name phase) "—"
                            (name (:codex/anomaly entry)) ":"
                            (:codex/reason entry))))
               nil)
           entry))))))
