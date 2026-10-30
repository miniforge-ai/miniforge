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
(ns ai.miniforge.effect-transaction.codec
  "Translate validated effect transactions across the EDN storage boundary."
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private instant-paths
  [[:effect/at] [:effect/updated-at] [:effect/envelope :envelope/at]])

(defn- ^{:stratum 0} ->iso
  ^String [v]
  (if (instance? Instant v)
    (.toString ^Instant v)
    (.toString (.toInstant ^Date v))))

(defn- ^{:stratum 0} decode-instant-path
  [record path]
  (if-let [v (get-in record path)]
    (assoc-in record path (Instant/parse v))
    record))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} encode-instant-path
  [record path]
  (if (some? (get-in record path))
    (assoc-in record path (->iso (get-in record path)))
    record))

(defn ^{:stratum 1} <-wire
  "EDN map -> transaction record."
  [record]
  (reduce decode-instant-path record instant-paths))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ->wire
  "Record -> EDN-safe map."
  [record]
  (reduce encode-instant-path record instant-paths))
