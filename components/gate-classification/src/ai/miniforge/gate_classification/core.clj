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
   [ai.miniforge.gate-classification.messages :as msg]
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
  (get artifact :classified-violations []))

(defn- build-gate-error
  "Convert an unresolved violation to a gate error."
  [violation]
  (let [code (keyword (get violation :violation/rule-id))
        message (msg/t :gate/check-failed {:rule-id (get violation :violation/rule-id)
                                           :message (get violation :violation/message)})
        location (get violation :violation/location)]
    {:code code
     :message message
     :location location}))

(defn- build-gate-warnings
  "Build warnings for needs-investigation violations."
  [violations]
  (vec (keep (fn [v]
               (when (rules/needs-investigation? v)
                 (let [code (keyword (get v :violation/rule-id))
                       message (msg/t :gate/needs-investigation {:message (get v :violation/message)})]
                   {:code code
                    :message message})))
             violations)))

(defn- check-result
  [gate-id unresolved-errors warnings]
  {:gate/id gate-id
   :gate/type :classification
   :gate/passed? (empty? unresolved-errors)
   :gate/errors (mapv build-gate-error unresolved-errors)
   :gate/warnings warnings})

(defn- check-error-result
  [gate-id ex]
  (let [error-message (msg/t :gate/check-error {:error (ex-message ex)})]
    {:gate/id gate-id
     :gate/type :classification
     :gate/passed? false
     :gate/errors [{:code :check-error
                    :message error-message
                    :location {}}]
     :gate/warnings []}))

(defn- repair-change
  [index violation]
  {:index index
   :violation-id (get violation :violation/id)
   :action :reclassified-to-investigation})

(defn- repair-result
  [artifact reclassified changes remaining]
  {:repaired? (seq changes)
   :artifact (assoc artifact :classified-violations reclassified)
   :changes changes
   :remaining-violations remaining})

;; Check operation

(defn check-artifact
  "Run classification gate check on artifact."
  [this artifact _context]
  (try
    (let [violations (extract-violations artifact)
          rule-result (rules/apply-rules-to-all violations (:config this))
          classified-violations (:violations rule-result)
          unresolved-errors (rules/filter-unresolved-errors classified-violations (:config this))
          warnings (build-gate-warnings classified-violations)]
      (check-result (:gate-id this) unresolved-errors warnings))
    (catch Exception e
      (check-error-result (:gate-id this) e))))

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
                                       (repair-change idx v)))
                                   reclassified))
          remaining          (rules/filter-unresolved-errors reclassified (:config this))]
      (repair-result artifact reclassified changes remaining))
    (catch Exception _e
      (repair-result artifact (extract-violations artifact) [] []))))
