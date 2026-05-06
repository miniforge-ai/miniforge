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
  "Malli schemas for progress-detector data shapes.

   The canonical anomaly shape lives in `ai.miniforge.anomaly.contract`
   and is re-exported here verbatim — progress-detector does NOT define
   its own parallel Anomaly schema. Detector-specific structure
   (severity, class, category, evidence, fingerprint) goes inside
   `:anomaly/data` as a `DetectorAnomalyData` map.

   Vocabularies (defined here, not in the anomaly contract):
     ::detector-class    - :mechanical | :heuristic
     ::detector-severity - :info | :warn | :error | :fatal
     ::anomaly-category  - :anomalies.agent/tool-loop | …
     ::determinism       - :stable-with-resource-version | … (per spec)

   Schemas defined here:
     DetectorAnomalyData - structure under :anomaly/data
     Observation         - tool invocation event fed to detectors
     DetectorConfig      - per-detector configuration overlay
     ToolProfile         - registry entry for a known tool

   Anomaly validation helpers live on `ai.miniforge.anomaly.interface`.
   Use those, not parallel ones here."
  (:require
   [malli.core    :as m]
   [malli.error   :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Detector vocabularies (mechanical/heuristic + severity ladder)

(def detector-classes
  "Detector classification per the Stage 1 spec — mechanical detectors
   produce hard signals (recommended action defaults to terminate);
   heuristic detectors produce probabilistic signals (default action
   :warn unless promoted by composite-rules in Stage 3)."
  #{:mechanical :heuristic})

(def DetectorClass
  "Schema enum for detector classification."
  (into [:enum] detector-classes))

(def severities
  "Severity ladder for detector-emitted anomalies. Matches the Stage 1
   spec's severity vocabulary (:info :warn :error :fatal)."
  #{:info :warn :error :fatal})

(def DetectorSeverity
  "Schema enum for detector severity."
  (into [:enum] severities))

(def determinisms
  "Tool determinism levels per the Stage 1 spec's tool capability
   registry. The level gates whether a fingerprint repeat counts as
   mechanical or heuristic.

     :stable-with-resource-version - same call, same resource hash, same
                                     result; mechanical-eligible
     :stable-ish                   - mostly-deterministic; mechanical with
                                     bounded false-positives
     :environment-dependent        - depends on env/state; mechanical only
                                     when failure fingerprint also matches
     :unstable                     - inherently variable (network, time);
                                     heuristic only"
  #{:stable-with-resource-version
    :stable-ish
    :environment-dependent
    :unstable})

(def Determinism
  "Schema enum for tool determinism."
  (into [:enum] determinisms))

;------------------------------------------------------------------------------ Layer 1
;; Detector-specific anomaly payload (lives under :anomaly/data)

(def AnomalyEvidence
  "Bounded evidence for a detector anomaly. Per the Stage 1 spec:
   summary + event-id pointers + fingerprint + threshold metadata
   + a pointer to the local event log via :raw-log-ref. Raw tool args,
   raw assistant text, and raw file content do NOT appear here."
  [:map
   [:summary       :string]
   [:event-ids     {:optional true} [:vector :any]]
   [:fingerprint   {:optional true} :string]
   [:threshold     {:optional true} [:map-of :any :any]]
   [:raw-log-ref   {:optional true} :string]
   [:redacted?     {:optional true} :boolean]])

(def DetectorAnomalyData
  "Structure that goes inside `:anomaly/data` when a detector emits an
   anomaly via `ai.miniforge.anomaly.interface/anomaly`. Detectors
   classify (the keys here); the supervisor disposes (lifecycle action
   policy lives elsewhere — Stage 3).

   Required:
     :detector/kind     - keyword identifying which detector fired
     :detector/version  - string version of the detector implementation
     :anomaly/class     - :mechanical | :heuristic
     :anomaly/severity  - :info | :warn | :error | :fatal
     :anomaly/category  - keyword like :anomalies.agent/tool-loop
     :anomaly/evidence  - bounded evidence map"
  [:map
   [:detector/kind       :keyword]
   [:detector/version    :string]
   [:anomaly/class       DetectorClass]
   [:anomaly/severity    DetectorSeverity]
   [:anomaly/category    :keyword]
   [:anomaly/evidence    AnomalyEvidence]])

;------------------------------------------------------------------------------ Layer 1
;; Observation schema (input to detectors)

(def Observation
  "A single tool-invocation event fed into the detector reduce loop.

   Stage 1 carries the minimum the tool-loop and repair-loop detectors
   need; Stage 2 will extend with :resource/version-hash, :semantic/epoch,
   and richer event kinds."
  [:map
   [:tool/id          :keyword]
   [:seq              :int]
   [:timestamp        inst?]
   [:tool/duration-ms {:optional true} [:maybe :int]]
   [:tool/input       {:optional true} :any]
   [:tool/output      {:optional true} :any]
   [:tool/error?      {:optional true} :boolean]])

;------------------------------------------------------------------------------ Layer 1
;; Detector-config overlay schema (per-layer in the merge stack)

(def ConfigDirective
  "A single overlay directive in a detector config merge."
  [:enum
   :inherit   ; adopt parent config value unchanged
   :disable   ; suppress this detector/rule entirely
   :enable    ; force-enable even if parent disables
   :tune])    ; merge user-supplied overrides onto parent defaults

(def DetectorConfig
  "Per-detector configuration overlay layer.

   :config/directive controls how this layer merges with parent:
     :inherit - use parent values; local map adds nothing
     :disable - detector is suppressed; all other keys ignored
     :enable  - force-enable detector; overrides parent :disable
     :tune    - deep-merge this map onto parent defaults

   :config/params holds detector-specific tuning knobs."
  [:map
   [:config/directive {:optional true} ConfigDirective]
   [:config/params    {:optional true} [:map-of :keyword :any]]])

;------------------------------------------------------------------------------ Layer 1
;; Tool-profile schema (entries the registration API accepts)

(def ToolProfile
  "Profile entry that components contribute via
   `ai.miniforge.progress-detector.tool-profile/register!` to describe
   the determinism + fingerprint shape of a tool the agent calls.

   Stage 1 keeps this shape minimal; Stage 2 will add per-tool
   :fingerprint/dimensions + :failure/fingerprint-extractor refs."
  [:map
   [:tool/id            :keyword]
   [:determinism        Determinism]
   [:anomaly/categories {:optional true} [:set :keyword]]
   [:timeout-ms         {:optional true} [:maybe :int]]
   [:config             {:optional true} [:map-of :keyword :any]]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers (delegate to anomaly component for top-level Anomaly)

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

(defn explain-tool-profile
  "Return humanized error map if m fails ToolProfile validation, else nil."
  [m]
  (when-let [exp (m/explain ToolProfile m)]
    (me/humanize exp)))

(defn valid-detector-config?
  "Return true if m satisfies the DetectorConfig schema."
  [m]
  (m/validate DetectorConfig m))

(defn explain-detector-config
  "Return humanized error map if m fails DetectorConfig validation, else nil."
  [m]
  (when-let [exp (m/explain DetectorConfig m)]
    (me/humanize exp)))

(defn valid-detector-anomaly-data?
  "Return true if m satisfies the DetectorAnomalyData schema (the shape
   that goes inside :anomaly/data on a detector-emitted anomaly)."
  [m]
  (m/validate DetectorAnomalyData m))

(defn explain-detector-anomaly-data
  "Return humanized error map if m fails DetectorAnomalyData validation."
  [m]
  (when-let [exp (m/explain DetectorAnomalyData m)]
    (me/humanize exp)))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Validate detector-specific anomaly data
  (valid-detector-anomaly-data?
   {:detector/kind     :detector/tool-loop
    :detector/version  "stage-1.0"
    :anomaly/class     :mechanical
    :anomaly/severity  :error
    :anomaly/category  :anomalies.agent/tool-loop
    :anomaly/evidence  {:summary "Read \"src/foo.clj\" 6 times"
                        :fingerprint "abc123"
                        :threshold {:n 5 :window 10}
                        :redacted? true}})
  ;; => true

  (valid-observation?
   {:tool/id   :tool/bash
    :seq       1
    :timestamp (java.time.Instant/now)
    :tool/duration-ms 150})
  ;; => true

  :leave-this-here)
