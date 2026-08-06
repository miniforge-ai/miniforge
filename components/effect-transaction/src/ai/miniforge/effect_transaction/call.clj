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
(ns ai.miniforge.effect-transaction.call
  "Convert effect and probe calls into honest outcome data.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} result
  "Call `thunk`; preserve an Exception as data and let JVM Errors propagate."
  [thunk]
  (try
    {:call/report (thunk)}
    (catch Exception e
      {:call/error (or (ex-message e) (str (class e)))})))

(defn- ^{:stratum 0} outcome-of
  "Return a definite reported outcome, or `:unknown-outcome`."
  [reported]
  (let [outcome (:effect/outcome reported)]
    (if (contains? #{:succeeded :failed} outcome)
      outcome
      :unknown-outcome)))

(defn ^{:stratum 0} probe-answered?
  "True when a wrapped probe produced the reconciliation shape."
  [called]
  (let [answer (:call/report called)]
    (and (not (contains? called :call/error))
         (map? answer)
         (contains? answer :effect/observed))))

(defn ^{:stratum 0} probe-error-data
  "Diagnostic data for a probe that did not answer."
  [t called]
  (cond-> {:effect/id (:effect/id t) :effect/state (:effect/state t)}
    (contains? called :call/error)
    (assoc :probe/error (:call/error called))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} effect-changes
  "Transaction changes justified by one effect report."
  [report]
  (cond-> {:effect/state (outcome-of report)}
    (contains? report :effect/observed)
    (assoc :effect/observed (:effect/observed report))
    (:effect/failure report)
    (assoc :effect/failure (:effect/failure report))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} changes
  "Transaction changes for a wrapped effect call."
  [called]
  (if-let [message (:call/error called)]
    {:effect/state :unknown-outcome
     :effect/failure message}
    (effect-changes (:call/report called))))
