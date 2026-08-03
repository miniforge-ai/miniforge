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
(ns ai.miniforge.gate.codex-consultation
  "Gate on Thesium Codex consultation provenance (Codex SPEC T1 §7.4.3):
   a proposal from an agent that never saw its pinned landings is a
   distinct object from one that did.

   Reads :codex/consultation off the phase artifact (attached by the phase
   at enter, after the agent runs). Verdicts:

   1. No consultation marker, or codex unconfigured/phase unmapped — PASS
     clean: the capability is off, and a gate that nags about an absent
     optional capability trains people to ignore gates.
   2. Codex CONFIGURED but the consultation was skipped (anomaly) — FAIL.
     The operator asked for codex governance; the phase silently running
     without it is the silent-downgrade disease this codebase treats as
     the enemy.
   3. Pinned but never fetched via context_read — PASS with a warning.
     The pin is prompt-injected, so the agent saw it by construction; the
     missing fetch is informational, and becomes meaningful only if the
     pin ever moves to cache-only delivery."
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} check-codex-consultation
  "Gate check fn: (artifact ctx) -> {:passed? bool :errors [..] :warnings [..]}."
  [artifact _ctx]
  (let [c (:codex/consultation artifact)]
    (cond
      (or (nil? c) (contains? #{:unconfigured :unmapped} (:status c)))
      {:passed? true}

      (= :skipped (:status c))
      {:passed? false
       :errors [{:type :codex-consultation-skipped
                 :message (str "Codex is configured but this phase's consultation "
                               "failed — the agent acted without its pinned "
                               "landings (Codex SPEC §7.4.3)")
                 :anomaly (:anomaly c)}]}

      (and (:pinned? c) (false? (:pin-read? c)))
      {:passed? true
       :warnings [{:type :codex-pin-not-fetched
                   :message (str "Pinned landings were prompt-injected but never "
                                 "fetched via context_read; informational while "
                                 "the pin is prompt-delivered")}]}

      :else
      {:passed? true})))

;------------------------------------------------------------------------------ Layer 1

(defmethod ^{:stratum 1} registry/get-gate :codex-consultation
  [_]
  {:name :codex-consultation
   :description "Validates the agent ran with its Thesium Codex consultation (SPEC §7.4.3)"
   :check check-codex-consultation
   :repair nil})

;; Registry
(registry/register-gate! :codex-consultation)
