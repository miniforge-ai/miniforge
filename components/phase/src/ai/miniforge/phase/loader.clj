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

(ns ai.miniforge.phase.loader
  "Generic resource-driven loader for phase implementation namespaces."
  (:require
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def phase-loader-config-resource
  "Classpath resource path for phase implementation namespace declarations."
  "config/phase/namespaces.edn")

(defonce loaded-phase-namespaces (atom nil))

(defn- normalize-phase-namespace
  "Normalize configured phase namespace entries to symbols."
  [entry]
  (cond
    (symbol? entry) entry
    (string? entry) (symbol entry)
    :else nil))

(defn- read-phase-loader-config
  "Read a single phase loader config resource."
  [resource]
  (let [config (-> resource slurp edn/read-string)]
    (or (:phase/namespaces config) [])))

;------------------------------------------------------------------------------ Layer 1
;; Discovery

(defn load-phase-loader-configs
  "Load phase loader namespace declarations from all matching classpath resources."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       phase-loader-config-resource))
       (mapcat read-phase-loader-config)
       (map normalize-phase-namespace)
       (filter some?)
       distinct
       vec))

(defn configured-phase-namespaces
  "Return the currently configured phase implementation namespaces."
  []
  (load-phase-loader-configs))

;------------------------------------------------------------------------------ Layer 2
;; Loading

(defn load-phase-implementations!
  "Require all configured phase implementation namespaces."
  []
  (let [phase-namespaces (configured-phase-namespaces)]
    (doseq [phase-ns phase-namespaces]
      (require phase-ns))
    (reset! loaded-phase-namespaces phase-namespaces)
    phase-namespaces))

(defn ensure-phase-implementations-loaded!
  "Load configured phase implementation namespaces once per process."
  []
  (or @loaded-phase-namespaces
      (load-phase-implementations!)))

(defn reset-loader!
  "Reset loader state for tests."
  []
  (reset! loaded-phase-namespaces nil))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (configured-phase-namespaces)
  (load-phase-implementations!)
  (ensure-phase-implementations-loaded!)
  (reset-loader!)
  :leave-this-here)
