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

(ns ai.miniforge.anomaly.interface
  "Public API for the anomaly component.

   An anomaly is a plain map describing a failure. Functions return
   anomalies instead of throwing — exceptions are reserved for
   programmer errors at the boundary (e.g. an unknown anomaly type).

   Standard usage:

     (require '[ai.miniforge.anomaly.interface :as anomaly])

     (defn lookup [id]
       (or (db/find id)
           (anomaly/anomaly :not-found
                            (str \"No record for id \" id)
                            {:id id})))

     (let [result (lookup 42)]
       (if (anomaly/anomaly? result)
         (handle-failure result)
         (process result)))"
  (:require
   [ai.miniforge.anomaly.contract :as contract]))

;------------------------------------------------------------------------------ Layer 0
;; Schema and vocabulary re-exports

(def Anomaly
  "Malli schema for the canonical anomaly map. See
   `ai.miniforge.anomaly.contract/Anomaly`."
  contract/Anomaly)

(def anomaly-types
  "Set of standard anomaly type keywords. See
   `ai.miniforge.anomaly.contract/anomaly-types`."
  contract/anomaly-types)

;------------------------------------------------------------------------------ Layer 1
;; Construction

(defn- now
  "Return the current instant. Wrapped so tests can redef."
  []
  (java.time.Instant/now))

(defn anomaly
  "Construct a canonical anomaly map.

   - `type`    — keyword from `anomaly-types`; an unknown type throws
                 IllegalArgumentException because that is a programmer
                 error, not a runtime condition.
   - `message` — human-readable description string.
   - `data`    — caller-supplied context map; nil is normalized to {}.

   The :anomaly/at timestamp is populated at construction with the
   current instant. The returned map satisfies the `Anomaly` schema."
  [type message data]
  (when-not (contains? contract/anomaly-types type)
    (throw (IllegalArgumentException.
            (str "Unknown anomaly type: " (pr-str type)
                 ". Must be one of " (pr-str contract/anomaly-types)))))
  {:anomaly/type    type
   :anomaly/message message
   :anomaly/data    (or data {})
   :anomaly/at      (now)})

;------------------------------------------------------------------------------ Layer 2
;; Predicate

(defn anomaly?
  "True when `x` is a map matching the canonical anomaly shape.

   Returns false for nil, non-maps, and maps missing required keys
   or holding values that do not match the schema."
  [x]
  (and (map? x)
       (contract/valid? x)))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (def boom (anomaly :not-found "missing user" {:id 7}))

  (anomaly? boom)
  ;; => true

  (anomaly? {:anomaly/type :bogus
             :anomaly/message "x"
             :anomaly/data {}
             :anomaly/at (java.time.Instant/now)})
  ;; => false (type not in vocabulary)

  ;; Programmer error — wrong type at construction throws
  (try (anomaly :no-such-type "x" {})
       (catch IllegalArgumentException e (.getMessage e)))

  :leave-this-here)
