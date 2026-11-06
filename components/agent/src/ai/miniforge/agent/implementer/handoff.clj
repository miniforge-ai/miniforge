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
(ns ai.miniforge.agent.implementer.handoff
  "Implementer prompt formatting of the findings an earlier phase handed
   off."
  (:require
   [ai.miniforge.repo-index.interface :as messages]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} phase-handoff-findings
  [phase-handoff]
  (get-in phase-handoff [:frame/body :repair/findings]))

(defn- ^{:stratum 0} format-phase-handoff-detail
  [label-key value]
  (when value
    (let [label (messages/t label-key)]
      (messages/t :prompt/phase-handoff-detail-line
                  {:label label
                   :value value}))))

(defn- ^{:stratum 0} finding-location
  [{:finding/keys [file line]}]
  (cond
    (and file line)
    (messages/t :prompt/phase-handoff-location-value {:file file :line line})
    file
    file))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} format-phase-handoff-finding
  [{:finding/keys [summary group-id severity suggestion] :as finding}]
  (let [location (finding-location finding)
        detail-lines (keep identity
                           [(format-phase-handoff-detail
                             :prompt/phase-handoff-group-label group-id)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-severity-label severity)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-location-label location)
                            (format-phase-handoff-detail
                             :prompt/phase-handoff-suggestion-label suggestion)])
        summary-line (messages/t :prompt/phase-handoff-finding-line
                                 {:summary summary})]
    (str summary-line
         (when (seq detail-lines)
           (str "\n" (str/join "\n" detail-lines))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} format-phase-handoff-findings
  [findings]
  (if (seq findings)
    (str/join "\n" (map format-phase-handoff-finding findings))
    (messages/t :prompt/phase-handoff-no-findings)))
