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
(ns ai.miniforge.codex.render
  "Text rendering of a `consider` response. Lives in the component — not the
   MCP base — so the pulled form (consider_situation tool) and the pushed
   form (the phase-start blackboard pin, SPEC §7.4) are byte-identical.

   Rendering rules come from the spec, not taste: landings arrive ordered
   strategic → operational → tactical (§7.6.1); the coverage statement leads
   and says out loud when there is no strategic-horizon landing (§7.6.2);
   horizon escalations — a landing whose anchoring scar cost more than the
   landing's own horizon — are flagged inline (§7.6.3). Anomalies render as
   anomalies; an unmatched situation is an answer, never an empty list."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} render-landing [{:keys [id type title horizon confidence open scars escalations]}]
  ;; horizon, anchoring and escalation are problem-node concepts (SPEC §2.3,
  ;; §2.6); a resolution is a terminal outcome and renders as one.
  (let [problem? (= type "problem")]
    (str/join "\n"
      (concat
        [(str "- [" (if problem? (or horizon "?") "resolution") "] " id " — " title
              "  (confidence: " confidence ")")]
        (when (seq escalations)
          [(str "    ESCALATION: " (str/join ", " escalations)
                " — the cost landed above this problem's horizon")])
        (for [{scar-id :id :keys [date cost cost-horizon]} scars]
          (str "    scar: " scar-id " (" date ", cost: " cost
               (when cost-horizon (str ", cost-horizon: " cost-horizon)) ")"))
        (when (and problem? (empty? scars))
          ["    unanchored — reasoned, not survived"])
        (map #(str "    open: " %) open)))))

(defn- ^{:stratum 0} render-coverage
  [{:keys [landing-count unanchored-count horizon-mix
           no-strategic-coverage? newest-scar-date]}]
  (str/join "\n"
    (concat
      ;; horizon mix rendered in the §7.6.1 order, not map order
      [(str "coverage: " landing-count " landings, "
            unanchored-count " unanchored, newest scar " (or newest-scar-date "none")
            ", horizon mix "
            (str/join " · " (for [h ["strategic" "operational" "tactical"]
                                  :let [n (get horizon-mix h)]
                                  :when n]
                              (str h " " n))))]
      (when no-strategic-coverage?
        ["NO STRATEGIC COVERAGE: every landing is tactical/operational. The codex has nothing strategic-horizon for this situation — that absence is a gap in the codex, not evidence the situation carries no strategic risk."]))))

(defn ^{:stratum 0} render-anomaly [{:codex/keys [anomaly reason available matches]}]
  (str/join "\n"
    (concat
      [(str "codex anomaly: " (name anomaly))
       reason]
      (when (seq available)
        (cons "available situations:"
              (map (fn [[id title]] (str "- " id " — " title)) available)))
      (when (seq matches)
        (cons "ambiguous between:"
              (map (fn [[id title]] (str "- " id " — " title)) matches))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render-response [resp]
  (if (:codex/anomaly resp)
    (render-anomaly resp)
    (str/join "\n"
      (concat
        [(str "situation: " (:situation resp) " — " (:situation-title resp))
         (render-coverage (:coverage resp))
         ""
         "landings (strategic first — read top-down):"]
        (map render-landing (:landings resp))))))
