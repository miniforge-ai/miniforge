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

(ns ai.miniforge.anomaly.contract
  "Malli contract for the canonical anomaly shape.

   Anomalies are the data-first error type shared across all
   miniforge-family Clojure repos. An anomaly is a plain map; it is
   not an exception. Functions that fail return an anomaly and let
   the caller decide what to do.

   This component defines a single shape so every repo agrees on:
   - the keyword namespace (`:anomaly/*`)
   - the type vocabulary (mirrors cognitect anomalies)
   - the timestamp at construction"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Type vocabulary

(def anomaly-types
  "Standard anomaly type keywords. Mirrors the cognitect anomalies
   vocabulary, with `:fatal` added for unrecoverable programmer errors.

   Each type encodes a category of failure:
   - :not-found      — referenced entity does not exist
   - :invalid-input  — caller-supplied data is malformed
   - :unauthorized   — caller lacks permission
   - :fault          — internal error; bug in this system
   - :unavailable    — downstream dependency is down
   - :conflict       — operation conflicts with existing state
   - :timeout        — operation exceeded its time budget
   - :unsupported    — operation not implemented for this case
   - :fatal          — unrecoverable; the process should stop"
  #{:not-found
    :invalid-input
    :unauthorized
    :fault
    :unavailable
    :conflict
    :timeout
    :unsupported
    :fatal})

;------------------------------------------------------------------------------ Layer 1
;; Schema

(def Anomaly
  "Malli schema for the canonical anomaly map.

   Every anomaly carries:
   - :anomaly/type    — keyword from the standard vocabulary
   - :anomaly/message — human-readable description
   - :anomaly/data    — caller-supplied context map (defaults to {})
   - :anomaly/at      — instant of construction"
  [:map {:closed true}
   [:anomaly/type    (into [:enum] anomaly-types)]
   [:anomaly/message :string]
   [:anomaly/data    [:map-of :any :any]]
   [:anomaly/at      inst?]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid?
  "Return true when `value` matches the Anomaly schema."
  [value]
  (m/validate Anomaly value))

(defn explain
  "Return a humanized explanation for an invalid anomaly, or nil
   when `value` is valid."
  [value]
  (some-> (m/explain Anomaly value)
          me/humanize))
