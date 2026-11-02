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
(ns ai.miniforge.deliberation-lab.manifest
  "Development manifest validation. A manifest pins the conditions, tiers,
   replicates, model and normalized price weights before any provider call;
   an invalid one is refused before the first call."
  (:require
   [ai.miniforge.deliberation-harness.interface :as harness]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} schema-version
  "The manifest shape this lab reads; any other version is refused."
  1)

(def ^{:stratum 0} minimum-activations
  "A tier needs at least one working turn and the closing turn."
  2)

(defn- ^{:stratum 0} non-negative-number? [x]
  (and (number? x) (not (neg? x))))

(defn- ^{:stratum 0} positive-number? [x]
  (and (number? x) (pos? x)))

(defn- ^{:stratum 0} distinct-seq? [xs]
  (and (seq xs) (= (count xs) (count (set xs)))))

(defn- ^{:stratum 0} valid-tier? [tier]
  (and (keyword? (:id tier))
       (int? (:activations tier))
       (<= minimum-activations (:activations tier))
       (positive-number? (:max-normalized-usd tier))
       (positive-number? (:wall-seconds tier))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid-conditions? [conditions]
  (and (distinct-seq? conditions) (every? harness/conditions conditions)))

(defn- ^{:stratum 1} valid-tiers? [tiers]
  (and (distinct-seq? (map :id tiers)) (every? valid-tier? tiers)))

(defn- ^{:stratum 1} valid-prices? [{:keys [input-per-million output-per-million]}]
  (and (non-negative-number? input-per-million) (non-negative-number? output-per-million)))

(defn ^{:stratum 1} defects
  "The defect keys `manifest` fails, empty when it is valid."
  [manifest]
  (cond-> []
    (not= :development (:mode manifest)) (conj :mode)
    (not= schema-version (:schema-version manifest)) (conj :schema-version)
    (not (and (string? (:model manifest)) (seq (:model manifest)))) (conj :model)
    (not (pos-int? (:replicates manifest))) (conj :replicates)
    (not (pos-int? (:max-input-bytes manifest))) (conj :max-input-bytes)
    (not (valid-conditions? (:conditions manifest))) (conj :conditions)
    (not (valid-tiers? (:tiers manifest))) (conj :tiers)
    (not (valid-prices? (:price-table manifest))) (conj :price-table)))
