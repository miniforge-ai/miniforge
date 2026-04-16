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

(ns ai.miniforge.workflow-security-compliance.config
  "Resource-backed configuration for the security-compliance workflow."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def ^:private workflow-resource
  "workflows/security-compliance-v1.0.0.edn")

(defn- load-workflow-resource
  []
  (-> workflow-resource
      io/resource
      slurp
      edn/read-string))

(def ^:private workflow-definition
  (delay (load-workflow-resource)))

(defn- workflow-config
  []
  (get @workflow-definition :workflow/config {}))

;------------------------------------------------------------------------------ Layer 1
;; Config accessors

(defn phase-defaults
  [phase-kw]
  (get-in (workflow-config) [:phase-defaults phase-kw]))

(defn all-phase-defaults
  []
  (get (workflow-config) :phase-defaults {}))

(defn registered-phase-keys
  "Ordered phase keywords declared in the workflow pipeline, excluding the
   terminal `:done` marker. These are the phases this component registers
   defaults for on load."
  []
  (->> (get @workflow-definition :workflow/pipeline [])
       (map :phase)
       (remove #{:done})
       vec))

(defn known-apis
  []
  (get (workflow-config) :known-apis #{}))

(defn trace-config
  []
  (get (workflow-config) :trace {}))

(defn classification-config
  []
  (get (workflow-config) :classification {}))

(defn output-config
  []
  (get (workflow-config) :output {}))

(defn output-directory-name
  []
  (get (output-config) :directory ".security-exclusions"))

(defn output-file-name
  []
  (get (output-config) :filename "exclusions.edn"))

(defn trace-api-pattern
  []
  (re-pattern (get (trace-config) :actual-api-pattern "!([A-Za-z0-9_]+)")))

(defn dynamic-load-pattern
  []
  (re-pattern (get (trace-config) :dynamic-load-pattern "(?i)loadlibrary|getprocaddress|dynamic.load")))

(defn classification-confidence
  [scenario]
  (get-in (classification-config) [:confidence scenario] 0.5))

(defn classification-reason
  [scenario]
  (get-in (classification-config) [:reason scenario] :classification/manual-review))
