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

(ns ai.miniforge.progress-detector.schema
  "Malli specs for progress-detector anomaly and observation data shapes.

   Canonical schemas:
     ::anomaly          - a single detected anomaly
     ::observation      - a tool invocation event fed to detectors
     ::detector-config  - per-detector configuration overlay
     ::tool-profile     - registry entry for a known tool

   Validation helpers:
     valid-anomaly?       - boolean check
     explain-anomaly      - humanized error report
     coerce-anomaly       - parse+validate, returning anomaly or nil
     valid-observation?   - boolean check"
  (:require
   [malli.core    :as m]
   [malli.error   :as me]
   [malli.transform :as mt]))

;; ---------------------------------------------------------------------------
;; Enumeration schemas (keywords only; closed sets)

(def AnomalyCategory
  "Categories of detectable anomalies."
  [:enum
   :anomaly.category/stall           ; no forward progress observed
   :anomaly.category/loop            ; repeated identical tool calls detected
   :anomaly.category/regression      ; output quality declining
   :anomaly.category/error           ; tool returned error signal
   :anomaly.category/cost-spike      ; token/time budget exceeded threshold
   :anomaly.category/timeout])       ; tool exceeded expected duration

(def AnomalyClass
  "Structural class of the anomaly within its category."
  [:enum
   :anomaly.class/hard               ; definitive, high-confidence signal
   :anomaly.class/soft               ; probabilistic / heuristic signal
   :anomaly.class/advisory])         ; informational, does not require action

(def AnomalySeverity
  "Urgency level of the anomaly."
  [:enum
   :anomaly.severity/critical        ; workflow likely broken; escalate now
   :anomaly.severity/warning         ; degraded progress; monitor closely
   :anomaly.severity/info])          ; noteworthy but not actionable

(def DetectorKind
  "Identifies the detector implementation responsible for an anomaly."
  [:enum
   :detector.kind/shell
   :detector.kind/fs-mutation
   :detector.kind/fs-read
   :detector.kind/llm
   :detector.kind/network
   :detector.kind/state-mutation
   :detector.kind/composite])

(def Determinism
  "Describes output stability of a tool across identical inputs."
  [:enum
   :deterministic     ; always identical output for identical input
   :stable            ; same output for same environment state
   :volatile          ; output varies by time/environment
   :nondeterministic]) ; intentionally random or stochastic

;; ---------------------------------------------------------------------------
;; Anomaly schema

(def AnomalyEvidence
  "A single piece of supporting evidence for an anomaly."
  [:map
   [:evidence/kind  [:enum
                     :evidence.kind/observation  ; one tool call snapshot
                     :evidence.kind/window       ; slice of observation history
                     :evidence.kind/diff         ; delta between two states
                     :evidence.kind/metric]]     ; computed numeric metric
   [:evidence/data  :any]])                      ; kind-specific payload

(def Anomaly
  "A detected progress anomaly.

   Required fields:
     :anomaly/id         - unique string identifier (UUID recommended)
     :anomaly/category   - broad classification (AnomalyCategory)
     :anomaly/class      - structural class (AnomalyClass)
     :anomaly/severity   - urgency level (AnomalySeverity)
     :detector/kind      - which detector produced this (DetectorKind)
     :anomaly/evidence   - vector of supporting evidence maps
     :anomaly/detected-at - inst of detection

   Optional fields:
     :anomaly/description - human-readable summary
     :anomaly/context     - arbitrary map for debugging"
  [:map
   [:anomaly/id          :string]
   [:anomaly/category    AnomalyCategory]
   [:anomaly/class       AnomalyClass]
   [:anomaly/severity    AnomalySeverity]
   [:detector/kind       DetectorKind]
   [:anomaly/evidence    [:vector AnomalyEvidence]]
   [:anomaly/detected-at inst?]
   [:anomaly/description {:optional true} :string]
   [:anomaly/context     {:optional true} [:map-of :keyword :any]]])

;; ---------------------------------------------------------------------------
;; Observation schema

(def Observation
  "A single tool-invocation event fed into the detector reduce loop."
  [:map
   [:tool/id          :keyword]
   [:seq              :int]
   [:timestamp        inst?]
   [:tool/duration-ms {:optional true} [:maybe :int]]
   [:tool/input       {:optional true} :any]
   [:tool/output      {:optional true} :any]
   [:tool/error?      {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Detector-config overlay schema

(def ConfigDirective
  "A single overlay directive in a detector config merge."
  [:enum
   :inherit   ; adopt parent config value unchanged
   :disable   ; suppress this detector/rule entirely
   :enable    ; force-enable even if parent disables
   :tune])    ; merge user-supplied overrides onto parent defaults

(def DetectorConfig
  "Per-detector configuration, supporting overlay resolution.

   :config/directive controls how this config merges with parent:
     :inherit - use parent values; local map is empty or additive
     :disable - detector is suppressed; all other keys are ignored
     :enable  - force-enable detector; overrides parent :disable
     :tune    - deep-merge this map onto parent defaults

   :config/params holds detector-specific tuning knobs."
  [:map
   [:config/directive {:optional true} ConfigDirective]
   [:config/params    {:optional true} [:map-of :keyword :any]]])

;; ---------------------------------------------------------------------------
;; Tool-profile schema

(def ToolProfile
  "Registry entry describing a known tool's anomaly-detection characteristics."
  [:map
   [:detector/kind          DetectorKind]
   [:determinism            Determinism]
   [:anomaly/categories     {:optional true} [:set AnomalyCategory]]
   [:timeout-ms             {:optional true} [:maybe :int]]
   [:config                 {:optional true} [:map-of :keyword :any]]])

;; ---------------------------------------------------------------------------
;; Validation helpers

(defn valid-anomaly?
  "Return true if m satisfies the Anomaly schema."
  [m]
  (m/validate Anomaly m))

(defn explain-anomaly
  "Return humanized error map if m fails Anomaly validation, else nil."
  [m]
  (when-let [exp (m/explain Anomaly m)]
    (me/humanize exp)))

(defn coerce-anomaly
  "Attempt to decode m into an Anomaly, returning the decoded value or nil."
  [m]
  (m/decode Anomaly m (mt/default-value-transformer)))

(defn valid-observation?
  "Return true if m satisfies the Observation schema."
  [m]
  (m/validate Observation m))

(defn explain-observation
  "Return humanized error map if m fails Observation validation, else nil."
  [m]
  (when-let [exp (m/explain Observation m)]
    (me/humanize exp)))

(defn valid-tool-profile?
  "Return true if m satisfies the ToolProfile schema."
  [m]
  (m/validate ToolProfile m))

(defn valid-detector-config?
  "Return true if m satisfies the DetectorConfig schema."
  [m]
  (m/validate DetectorConfig m))

;; ---------------------------------------------------------------------------
;; Rich comment

(comment
  (valid-anomaly?
   {:anomaly/id          "123e4567-e89b-12d3-a456-426614174000"
    :anomaly/category    :anomaly.category/loop
    :anomaly/class       :anomaly.class/hard
    :anomaly/severity    :anomaly.severity/warning
    :detector/kind       :detector.kind/fs-read
    :anomaly/evidence    [{:evidence/kind :evidence.kind/window
                           :evidence/data {:window-size 5 :unique-calls 1}}]
    :anomaly/detected-at (java.time.Instant/now)})
  ;; => true

  (explain-anomaly {:anomaly/id 42})
  ;; => {:anomaly/id ["should be a string"] ...}

  (valid-observation?
   {:tool/id   :tool/bash
    :seq       1
    :timestamp (java.time.Instant/now)
    :tool/duration-ms 150})
  ;; => true

  :leave-this-here)
