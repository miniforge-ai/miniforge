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
(ns ai.miniforge.gate.result
  "Reads a gate check result's shape. The interface and the phase
   envelope both ask this namespace, so a gate that returns the
   canonical response shape is never read as a failure by one of them."
  (:require
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} passed?
  "Check if a gate result passed.

   Accepts either of two canonical result shapes:
   1. Hand-shaped gate result with explicit `:passed?` key
      (legacy gate convention — syntax, lint, etc.).
   2. Canonical response shape from `response/success` /
      `response/error` (what format and any new gate should return).
      `response/success?` recognizes the `:status :success` form.

   Bridging both lets gates return the project-wide success/anomaly
   factories without each one re-establishing its own ad-hoc shape.

   Returns: boolean"
  [result]
  (cond
    (contains? result :passed?) (boolean (:passed? result))
    (response/success? result)  true
    (response/error? result)    false
    :else                       false))

(defn ^{:stratum 0} errors
  "The errors a gate result reports, in either shape: the legacy
   `:errors` vector, or the one error a canonical `response/error`
   result carries under `:error` (`{:message :data}`). Empty when the
   result names none."
  [result]
  (vec (or (seq (:errors result))
           (when (response/error? result)
             [(select-keys (:error result) [:message :data])]))))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  (passed? {:passed? true})
  (passed? (response/success {:formattable-files []}))
  (passed? (response/error "boom"))
  (errors (response/error "boom"))

  :leave-this-here)
