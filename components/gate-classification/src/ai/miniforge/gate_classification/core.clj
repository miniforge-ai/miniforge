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

(ns ai.miniforge.gate-classification.core
  "Classification gate implementation.

   Implements the Gate protocol for mixed deterministic + LLM-judgment
   classification in security compliance workflow."
  (:require
   [ai.miniforge.loop.interface.protocols.gate :as gate-protocol]
   [ai.miniforge.gate-classification.rules :as rules]))

;;------------------------------------------------------------------------------ Layer 0
;; Forward declarations

(declare check-artifact repair-artifact)

;; Record definition

(defrecord ClassificationGate [gate-id config]
  gate-protocol/Gate
  (check [this artifact context]
    (check-artifact this artifact context))
  (gate-id [_this]
    gate-id)
  (gate-type [_this]
    :classification)
  (repair [this artifact violations context]
    (repair-artifact this artifact violations context)))

;;------------------------------------------------------------------------------ Layer 1
;; Check helpers

(defn extract-violations
  "Extract classified violations from artifact."
  [artifact]
  (or (:classified-violations artifact) []))

(defn- build-gate-error
  "Convert an unresolved violation to a gate error."
  [violation]
  {:code     (keyword (:violation/rule-id violation))
   :message  (str (:violation/rule-id violation) ": " (:violation/message violation))
   :location (:violation/location violation)})

(defn- build-gate-warnings
  "Build warnings for needs-investigation violations."
  [violations]
  (vec (keep (fn [v]
               (when (rules/needs-investigation? v)
                 {:code    (keyword (:violation/rule-id v))
                  :message (str "Needs investigation: " (:violation/message v))}))
             violations)))

;; Check operation

(defn check-artifact
  "Run classification gate check on artifact."
  [this artifact _context]
  (try
    (let [violations      (extract-violations artifact)
          {:keys [violations]} (rules/apply-rules-to-all violations (:config this))
          unresolved-errors (rules/filter-unresolved-errors violations (:config this))
          warnings         (build-gate-warnings violations)]
      {:gate/id       (:gate-id this)
       :gate/type     :classification
       :gate/passed?  (empty? unresolved-errors)
       :gate/errors   (mapv build-gate-error unresolved-errors)
       :gate/warnings warnings})
    (catch Exception e
      {:gate/id       (:gate-id this)
       :gate/type     :classification
       :gate/passed?  false
       :gate/errors   [{:code    :check-error
                        :message (str "Classification gate check failed: " (ex-message e))
                        :location {}}]
       :gate/warnings []})))

;;------------------------------------------------------------------------------ Layer 2
;; Repair operation

(defn repair-artifact
  "Repair artifact by reclassifying low-confidence violations to needs-investigation."
  [this artifact _violations _context]
  (try
    (let [current-violations (extract-violations artifact)
          reclassified       (mapv (fn [v]
                                     (if (and (not (rules/needs-investigation? v))
                                              (< (rules/get-confidence v)
                                                 (get (:config this) :confidence-threshold 0.5)))
                                       (assoc v :classification/category :needs-investigation
                                                :classification/repair-action :reclassified)
                                       v))
                                   current-violations)
          changes            (vec (keep-indexed
                                   (fn [idx v]
                                     (when (:classification/repair-action v)
                                       {:index idx
                                        :violation-id (:violation/id v)
                                        :action :reclassified-to-investigation}))
                                   reclassified))
          remaining          (rules/filter-unresolved-errors reclassified (:config this))]
      {:repaired?             (seq changes)
       :artifact              (assoc artifact :classified-violations reclassified)
       :changes               changes
       :remaining-violations  remaining})
    (catch Exception _e
      {:repaired?            false
       :artifact             artifact
       :changes              []
       :remaining-violations []})))
