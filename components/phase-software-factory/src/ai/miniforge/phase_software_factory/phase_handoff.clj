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
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Envelope constants

(def repair-request-schema
  "Schema id for review/verify repair redirects to implement."
  :miniforge.phase-handoff.repair-request/v1)

(def ^:private frame-version
  "Current phase handoff envelope version."
  1)

(def ^:private handoff-config-resource
  "config/phase/handoff.edn")

(defn- load-handoff-config
  []
  (if-let [resource (io/resource handoff-config-resource)]
    (edn/read-string (slurp resource))
    {}))

(def ^:private handoff-config
  (delay (load-handoff-config)))

(defn- configured-group-patterns
  []
  (map #(update % :phase-handoff/pattern re-pattern)
       (:phase-handoff/group-patterns @handoff-config)))

(defn- configured-group-id
  [candidate group-pattern]
  (let [match (re-find (:phase-handoff/pattern group-pattern) candidate)
        group-number (second match)
        label (:phase-handoff/label group-pattern)]
    (when group-number
      (str/upper-case (str label " " group-number)))))

;------------------------------------------------------------------------------ Layer 1
;; Finding normalization

(defn- present-string
  [value]
  (when (string? value)
    (some-> value str/trim not-empty)))

(defn- extract-group-id
  [text]
  (when-let [candidate (present-string text)]
    (some (partial configured-group-id candidate)
          (configured-group-patterns))))

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
        suggestion (present-string (:suggestion finding))
        group-id (extract-group-id (str summary " " suggestion))]
    (cond-> {:finding/kind (finding-kind summary)
             :finding/summary summary}
      (:severity finding)
      (assoc :finding/severity (:severity finding))
      (:file finding)
      (assoc :finding/file (:file finding))
      (:line finding)
      (assoc :finding/line (:line finding))
      suggestion
      (assoc :finding/suggestion suggestion)
      group-id
      (assoc :finding/group-id group-id))))

(defn normalize-findings
  "Normalize loose review feedback into compact repair findings."
  [feedback]
  (cond
    (nil? feedback)
    []

    (string? feedback)
    (let [group-id (extract-group-id feedback)]
      [(cond-> {:finding/kind (finding-kind feedback)
                :finding/summary feedback}
         group-id
         (assoc :finding/group-id group-id))])

    (sequential? feedback)
    (vec (mapcat #(if (map? %) [(normalize-map-finding %)] (normalize-findings %))
                 feedback))

    (map? feedback)
    [(normalize-map-finding feedback)]

    :else
    [{:finding/kind :review-finding
      :finding/summary (pr-str feedback)}]))

;------------------------------------------------------------------------------ Layer 2
;; Repair envelopes

(defn repair-request
  "Build a typed repair-request handoff envelope."
  [{:keys [workflow-id source-phase target-phase phase-attempt feedback refs]}]
  (let [findings (normalize-findings feedback)
        frame-body (cond-> {:repair/source-phase source-phase
                            :repair/findings findings}
                     phase-attempt
                     (assoc :repair/attempt phase-attempt))]
    (cond-> {:frame/version frame-version
             :frame/id (random-uuid)
             :phase/id source-phase
             :transition/from source-phase
             :transition/to target-phase
             :frame/kind :repair-request
             :frame/schema repair-request-schema
             :frame/refs (vec refs)
             :frame/body frame-body}
      workflow-id
      (assoc :workflow/id workflow-id)
      phase-attempt
      (assoc :phase/attempt phase-attempt))))

(defn append-execution-handoff
  "Append a handoff envelope to execution state for checkpoint snapshots."
  [ctx handoff]
  (update ctx :execution/phase-handoffs (fnil conj []) handoff))

(defn- repair-request-targeted-to?
  [target-phase handoff]
  (and (= :repair-request (:frame/kind handoff))
       (= target-phase (:transition/to handoff))))

(defn latest-repair-request
  "Return the latest repair request targeting `target-phase`."
  [ctx target-phase]
  (->> (:execution/phase-handoffs ctx)
       (filter (partial repair-request-targeted-to? target-phase))
       last))
