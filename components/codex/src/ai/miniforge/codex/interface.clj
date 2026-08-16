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
(ns ai.miniforge.codex.interface
  "Thesium Codex consumption surface (Codex SPEC T1 §7).

   The codex stores what is dangerous, indexed by situation — not what is
   true. `consider` routes a situation to the problems worth attending to
   and returns them with a coverage statement, so a consumer's confidence
   is lowered in a targeted way rather than raised.

   Read-only by design: the codex is generated from data-as-source
   elsewhere (SPEC §8.1); this component never writes it."
  (:require [ai.miniforge.codex.core :as core]
            [ai.miniforge.codex.messages :as msg]
            [ai.miniforge.codex.node :as node]
            [ai.miniforge.codex.render :as render]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} load-graph
  "Read the codex node graph under `codex-dir`.
   Returns {:nodes {id node}} or {:codex/anomaly ...} as data."
  [codex-dir]
  (node/load-graph codex-dir))

(defn ^{:stratum 0} consider
  "consider(situation) -> {landings, coverage}  (SPEC §7.1)

   Landings are ordered strategic → operational → tactical (§7.6.1); the
   coverage statement carries landing count, unanchored count, horizon mix
   (including an explicit :no-strategic-coverage? flag, §7.6.2), the
   newest scar date (§7.5), and the retirement-trigger state (§7.7).
   :pegs carries the per-peg telemetry basis — every discriminator
   presented, with the landing set each answer routes to (§7.7/§4.4).
   Anomalies (unreadable codex, unmatched or ambiguous situation) come
   back as {:codex/anomaly ...} data, never as an empty success."
  [codex-dir situation-text]
  (core/consider codex-dir situation-text))

(defn ^{:stratum 0} render-response
  "Render a `consider` response (or anomaly) as the canonical consultation
   body text. The consider_situation MCP tool (pull) returns this body
   verbatim; the phase-start blackboard pin (push, SPEC §7.4) wraps the
   SAME body in a pin header comment — one renderer, so the two surfaces
   can never drift apart in content."
  [resp]
  (render/render-response resp))

(defn ^{:stratum 0} pin-entry
  "Build the per-run blackboard pin (SPEC §7.4): consult the codex for
   `situation` and return {:path pin-path :content rendered :pegs [..]} —
   :path/:content suitable as a `:task/existing-files` entry
   (prompt-visible AND cache-fetchable); :pegs is the consultation's §7.7
   telemetry basis riding along for provenance recording, never part of
   the pinned file itself.

   The pinned artifact is the consultation RESULT, ephemeral and per-run;
   the codex itself is durable and is never cached onto the blackboard
   (§7.4.1). On any anomaly the anomaly map comes back instead, so the
   caller decides whether to pin nothing or pin the anomaly — a silent
   default here would be false coverage."
  [codex-dir situation pin-path]
  (let [resp (core/consider codex-dir situation)]
    (if (:codex/anomaly resp)
      resp
      {:path pin-path
       :content (str (msg/t :pin/header)
                     "\n\n"
                     (render/render-response resp)
                     "\n")
       :pegs (:pegs resp)})))

(defn ^{:stratum 0} unconfigured-anomaly
  "The anomaly returned when no codex location is configured at all.
   Lives here so consumers (e.g. the MCP tool) fail closed with the
   catalog's wording rather than hardcoding their own."
  []
  {:codex/anomaly :codex-unconfigured
   :codex/reason (msg/t :anomaly/unconfigured)})
