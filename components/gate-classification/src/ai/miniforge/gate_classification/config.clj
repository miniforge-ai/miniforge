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

(ns ai.miniforge.gate-classification.config
  "Resource-backed defaults for the classification gate."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def ^:private config-resource
  "config/gate-classification/defaults.edn")

(def ^:private config-data
  (delay
    (-> config-resource
        io/resource
        slurp
        edn/read-string
        (get :gate-classification/config {}))))

;------------------------------------------------------------------------------ Layer 1
;; Config accessors

(defn default-config
  []
  (let [thresholds (get @config-data :thresholds {})]
    {:confidence-threshold (get thresholds :confidence-threshold 0.5)
     :doc-status-threshold (get thresholds :doc-status-threshold 0.7)
     :true-positive-threshold (get thresholds :true-positive-threshold 0.8)
     :max-error-severity :error}))

(defn gate-id
  []
  (get @config-data :gate-id :classification-gate))

(defn gate-type
  []
  (get @config-data :gate-type :classification))

(defn severity-rank
  []
  (get @config-data :severity-rank {}))
