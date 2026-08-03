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
(ns ai.miniforge.codex-gap.classify
  "Bucket classification of a miss entry (T2 §3.3): first leak wins, a
   later rung is never blamed when an earlier one leaked.

     :uncovered    no situation for the phase — the codex never entered
     :misrouted    problem confidently identified, NOT reachable from the
                   entered situation's board (even an undelivered warning
                   could not have reached it — earlier leak)
     :undelivered  routed, but the consultation was skipped, absent, or
                   the codex was unconfigured — the plumbing did not run
     :unheeded     the warning was in front of the consumer and the
                   failure matched a delivered landing anyway
     :review-queue NOT a rung bucket — attribution below threshold; held
                   out of every rate until resolved
     :novel        assigned only by review-queue resolution in v0; the
                   mechanical path cannot prove absence, only presence.

   :pin-read? stays tri-state throughout — nil is unknown, never false."
  (:require [ai.miniforge.codex-gap.attribute :as attribute]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} signal-text
  "The attributable text of a miss signal: review-issue descriptions,
   gate-reason details, anomaly messages — whatever the payload carries."
  [{:keys [payload]}]
  (->> [(:description payload) (:message payload) (:anomaly/message payload)
        (:reason/detail payload)
        (when (string? payload) payload)]
       (remove str/blank?)
       (str/join " ")))

(defn- ^{:stratum 0} undelivered?
  [consultation]
  (or (nil? consultation)
      (contains? #{:skipped :unconfigured} (:status consultation))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} classify
  "Classify one miss entry. Inputs:
   - entry: {:miss/situation :miss/consultation :miss/signal ...}
   - consider-resp: the codex consider response for the entry's situation
     ({:landings [...]} — or nil/anomaly when unavailable)
   - problems: seq of {:id :title :open} — the codex's FULL problem set
     (attribution corpus; landings alone cannot detect :misrouted)
   - opts: {:gate-reason-map .. :threshold ..}
   Returns {:bucket kw :attribution map-or-nil}."
  [entry consider-resp problems {:keys [gate-reason-map threshold]
                                 :or {threshold attribute/default-threshold}}]
  (let [situation (:miss/situation entry)
        consultation (:miss/consultation entry)
        signal (:miss/signal entry)]
    (if (nil? situation)
      {:bucket :uncovered :attribution nil}
      (let [attribution (or (when (= :gate-failure (:type signal))
                              (attribute/mechanical (:payload signal)
                                                    (or gate-reason-map
                                                        (attribute/load-gate-reason-map))))
                            (attribute/similarity (signal-text signal) problems))
            confident? (and attribution (>= (:confidence attribution) threshold))
            landing-ids (set (map :id (:landings consider-resp)))]
        (cond
          (not confident?)
          {:bucket :review-queue :attribution attribution}

          (not (contains? landing-ids (:problem attribution)))
          {:bucket :misrouted :attribution attribution}

          (undelivered? consultation)
          {:bucket :undelivered :attribution attribution}

          :else
          {:bucket :unheeded :attribution attribution})))))
