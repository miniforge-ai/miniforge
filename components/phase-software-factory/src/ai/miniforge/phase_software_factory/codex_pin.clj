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
            [ai.miniforge.phase-software-factory.messages :as messages]
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
   supplies the worries. implement/plan deliver via the pinned
   existing-files entry (pin-outcome); review has no existing-files
   channel and delivers via a rendered prompt section (landings-text)."
  {:implement "changing-one-side-of-a-boundary"
   :plan      "about-to-commit-consequential"
   :review    "quality-signal-might-be-lying"})

(defn- ^{:stratum 0} configured-codex-dir []
  (some-> (System/getenv "MINIFORGE_CODEX_PATH") str/trim not-empty))

(defn- ^{:stratum 0} warn-skip!
  "A CONFIGURED codex that fails to answer always warns: logger when given,
   else stderr — a nil logger must not turn the failure silent."
  [phase logger anomaly reason]
  (if logger
    (log/warn logger phase :codex/consultation-skipped
              {:data {:anomaly anomaly :reason reason}})
    (binding [*out* *err*]
      (println (messages/t :codex/consultation-skipped-warn
                           {:phase (name phase)
                            :anomaly (name anomaly)
                            :reason reason})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pin-outcome
  "The pin attempt for `phase`, fully described:
   {:entry {:path :content}-or-nil
    :status :pinned | :unconfigured | :unmapped | :skipped
    :anomaly keyword-or-nil}
   :unconfigured (no MINIFORGE_CODEX_PATH) and :unmapped (phase not in
   phase->situation) mean the capability is off — no noise. :skipped means a
   CONFIGURED codex failed to answer, which always warns: through the logger
   when one is given, else stderr — a nil logger must not turn the failure
   silent (plan has no logger)."
  ([phase logger] (pin-outcome phase logger (configured-codex-dir)))
  ([phase logger codex-dir]
   (cond
     (nil? codex-dir)
     {:entry nil :status :unconfigured :anomaly nil}

     (nil? (get phase->situation phase))
     {:entry nil :status :unmapped :anomaly nil}

     :else
     (let [entry (codex/pin-entry codex-dir (get phase->situation phase) pin-path)]
       (if-let [anomaly (:codex/anomaly entry)]
         (do (warn-skip! phase logger anomaly (:codex/reason entry))
             {:entry nil :status :skipped :anomaly anomaly})
         {:entry entry :status :pinned :anomaly nil})))))

(defn ^{:stratum 1} landings-text
  "Rendered consultation body for phases that deliver via a prompt section
   rather than a pinned file — the reviewer has no existing-files channel.
   Returns the rendered text, or nil when the capability is off
   (unconfigured/unmapped) or a configured codex failed to answer (which
   warns, same policy as pin-outcome)."
  ([phase logger] (landings-text phase logger (configured-codex-dir)))
  ([phase logger codex-dir]
   (when codex-dir
     (when-let [situation (get phase->situation phase)]
       (let [resp (codex/consider codex-dir situation)]
         (if-let [anomaly (:codex/anomaly resp)]
           (do (warn-skip! phase logger anomaly (:codex/reason resp))
               nil)
           (codex/render-response resp)))))))

(defn ^{:stratum 1} consultation-summary
  "The SPEC §7.4.3 distinct-object marker: a proposal from an agent that
   never saw its pinned landings must be distinguishable from one that did.
   `context-reads` is the session's recorded context_read log (§7.4.2), or
   nil when the session did not surface one — then :pin-read? is nil
   (unknown), not false; absence of the record is not evidence of absence
   of the read."
  [outcome context-reads]
  {:pinned?   (= :pinned (:status outcome))
   :status    (:status outcome)
   :anomaly   (:anomaly outcome)
   :pin-read? (when (some? context-reads)
                (boolean (some #(= pin-path (:path %)) context-reads)))})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pin-file
  "The pin for `phase` as an existing-files entry {:path :content}, or nil.
   Thin wrapper over pin-outcome for callers that only want the entry."
  ([phase logger] (:entry (pin-outcome phase logger)))
  ([phase logger codex-dir] (:entry (pin-outcome phase logger codex-dir))))
