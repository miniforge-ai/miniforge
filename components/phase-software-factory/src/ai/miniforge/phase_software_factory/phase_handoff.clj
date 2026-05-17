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

(ns ai.miniforge.phase-software-factory.phase-handoff
  "Typed envelopes for durable phase-to-phase handoffs."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Envelope constants

(def repair-request-schema
  "Schema id for review/verify repair redirects to implement."
  :miniforge.phase-handoff.repair-request/v1)

(def ^:private frame-version
  "Current phase handoff envelope version."
  1)

(def ^:private acceptance-group-pattern
  #"(?i)\bGROUP\s+\d+\b")

;------------------------------------------------------------------------------ Layer 1
;; Finding normalization

(defn- present-string
  [value]
  (when (string? value)
    (some-> value str/trim not-empty)))

(defn- extract-group-id
  [text]
  (some->> text
           present-string
           (re-find acceptance-group-pattern)
           str/upper-case))

(defn- finding-kind
  [summary]
  (if (extract-group-id summary)
    :missing-acceptance-group
    :review-finding))

(defn- normalize-map-finding
  [finding]
  (let [summary (or (present-string (:description finding))
                    (present-string (:summary finding))
                    (pr-str finding))
        suggestion (present-string (:suggestion finding))]
    (cond-> {:finding/kind (finding-kind summary)
             :finding/summary summary
             :finding/raw finding}
      (:severity finding)
      (assoc :finding/severity (:severity finding))
      (:file finding)
      (assoc :finding/file (:file finding))
      (:line finding)
      (assoc :finding/line (:line finding))
      suggestion
      (assoc :finding/suggestion suggestion)
      (extract-group-id (str summary " " suggestion))
      (assoc :finding/group-id (extract-group-id (str summary " " suggestion))))))

(defn normalize-findings
  "Normalize loose review feedback into repair findings.

   Keeps the original value under :finding/raw for migration compatibility."
  [feedback]
  (cond
    (nil? feedback)
    []

    (string? feedback)
    [(cond-> {:finding/kind (finding-kind feedback)
              :finding/summary feedback
              :finding/raw feedback}
       (extract-group-id feedback)
       (assoc :finding/group-id (extract-group-id feedback)))]

    (sequential? feedback)
    (vec (mapcat #(if (map? %) [(normalize-map-finding %)] (normalize-findings %))
                 feedback))

    (map? feedback)
    [(normalize-map-finding feedback)]

    :else
    [{:finding/kind :review-finding
      :finding/summary (pr-str feedback)
      :finding/raw feedback}]))

;------------------------------------------------------------------------------ Layer 2
;; Repair envelopes

(defn repair-request
  "Build a typed repair-request handoff envelope."
  [{:keys [workflow-id source-phase target-phase phase-attempt feedback refs]}]
  {:frame/version frame-version
   :frame/id (random-uuid)
   :workflow/id workflow-id
   :phase/id source-phase
   :phase/attempt phase-attempt
   :transition/from source-phase
   :transition/to target-phase
   :frame/kind :repair-request
   :frame/schema repair-request-schema
   :frame/refs (vec refs)
   :frame/body {:repair/source-phase source-phase
                :repair/attempt phase-attempt
                :repair/findings (normalize-findings feedback)
                :repair/raw-feedback feedback}})

(defn append-execution-handoff
  "Append a handoff envelope to execution state for checkpoint snapshots."
  [ctx handoff]
  (update ctx :execution/phase-handoffs (fnil conj []) handoff))

(defn latest-repair-request
  "Return the latest repair request targeting `target-phase`."
  [ctx target-phase]
  (->> (:execution/phase-handoffs ctx)
       (filter #(and (= :repair-request (:frame/kind %))
                     (= target-phase (:transition/to %))))
       last))
