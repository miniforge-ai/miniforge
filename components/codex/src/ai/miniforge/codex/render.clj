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
   form (the phase-start blackboard pin, SPEC §7.4) share one renderer:
   the pin wraps this same body in a header comment, and the content can
   never drift between the two surfaces. All prose comes from the
   component message catalog (messages/t), never hardcoded.

   Rendering rules come from the spec, not taste: landings arrive ordered
   strategic → operational → tactical (§7.6.1); the coverage statement leads
   and says out loud when there is no strategic-horizon landing (§7.6.2);
   horizon escalations — a landing whose anchoring scar cost more than the
   landing's own horizon — are flagged inline (§7.6.3). Anomalies render as
   anomalies; an unmatched situation is an answer, never an empty list."
  (:require [ai.miniforge.codex.messages :as msg]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} render-landing [{:keys [id type title horizon confidence open scars escalations]}]
  ;; horizon, anchoring and escalation are problem-node concepts (SPEC §2.3,
  ;; §2.6); a resolution is a terminal outcome and renders as one.
  (let [problem? (= type "problem")]
    (str/join "\n"
      (concat
        [(msg/t :render/landing-line
                {:horizon (if problem?
                            (or horizon (msg/t :render/unknown-horizon))
                            (msg/t :render/resolution-tag))
                 :id id :title title :confidence confidence})]
        (when (seq escalations)
          [(msg/t :render/escalation-line {:scars (str/join ", " escalations)})])
        (for [{scar-id :id :keys [date cost cost-horizon]} scars]
          (msg/t :render/scar-line
                 {:id scar-id :date date :cost cost
                  :cost-horizon (if cost-horizon
                                  (msg/t :render/scar-cost-horizon {:value cost-horizon})
                                  "")}))
        (when (and problem? (empty? scars))
          [(msg/t :render/unanchored-line)])
        (map #(msg/t :render/open-line {:text %}) open)))))

(defn- ^{:stratum 0} render-coverage
  [{:keys [landing-count unanchored-count horizon-mix
           no-strategic-coverage? newest-scar-date]}]
  (str/join "\n"
    (concat
      ;; horizon mix rendered in the §7.6.1 order, not map order
      [(msg/t :render/coverage-line
              {:landing-count landing-count
               :unanchored-count unanchored-count
               :newest-scar-date (or newest-scar-date (msg/t :render/no-newest-scar))
               :horizon-mix (str/join " · "
                                     (for [h ["strategic" "operational" "tactical"]
                                           :let [n (get horizon-mix h)]
                                           :when n]
                                       (str h " " n)))})]
      (when no-strategic-coverage?
        [(msg/t :render/no-strategic-coverage)]))))

(defn ^{:stratum 0} render-anomaly [{:codex/keys [anomaly reason available matches]}]
  (str/join "\n"
    (concat
      [(msg/t :render/anomaly-line {:anomaly (name anomaly)})
       reason]
      (when (seq available)
        (cons (msg/t :render/available-header)
              (map (fn [[id title]] (msg/t :render/situation-item {:id id :title title}))
                   available)))
      (when (seq matches)
        (cons (msg/t :render/ambiguous-header)
              (map (fn [[id title]] (msg/t :render/situation-item {:id id :title title}))
                   matches))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render-response [resp]
  (if (:codex/anomaly resp)
    (render-anomaly resp)
    (str/join "\n"
      (concat
        [(msg/t :render/situation-line {:id (:situation resp)
                                        :title (:situation-title resp)})
         (render-coverage (:coverage resp))
         ""
         (msg/t :render/landings-header)]
        (map render-landing (:landings resp))))))
