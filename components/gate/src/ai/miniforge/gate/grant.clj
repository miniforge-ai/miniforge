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
(ns ai.miniforge.gate.grant
  "Translate ExecutionGrant authorization results into envelope reasons."
  (:require
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.reason :as reason]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} render-label
  "Render nominal identifiers without trusting boundary data."
  [value]
  (if (or (keyword? value) (symbol? value) (string? value))
    (name value)
    (pr-str value)))

(defn- ^{:stratum 0} unrecognized-reasons
  [result]
  (let [outcome (pr-str (:grant/outcome result))]
    [(reason/create :reason/grant-absent
                    (msg/system-t :decide/unrecognized-grant-outcome
                                  {:outcome outcome}))]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} inactive-reasons
  [result]
  (let [revocation-reason (:grant/revocation-reason result)
        detail (if revocation-reason
                 (msg/system-t :decide/grant-inactive-revoked
                               {:reason (render-label revocation-reason)})
                 (msg/system-t :decide/grant-inactive))]
    [(reason/create :reason/grant-absent detail)]))

(defn- ^{:stratum 1} breach-reason
  [{:constraint/keys [axis limit observed]}]
  (reason/create :reason/grant-exceeded
                 (msg/system-t :decide/breach
                               {:axis (render-label axis)
                                :observed observed
                                :limit limit})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reasons
  "Translate an authorization result into deny-class envelope reasons.
   Nil means no grant was required; every unrecognized outcome fails closed."
  [result]
  (case (:grant/outcome result)
    nil []
    :authorized []
    :absent [(reason/create :reason/grant-absent
                            (msg/system-t :decide/grant-absent))]
    :inactive (inactive-reasons result)
    :scope-mismatch [(reason/create :reason/grant-scope-mismatch
                                    (msg/system-t :decide/grant-scope-mismatch))]
    :exceeded (let [breaches (:constraint/breaches result)]
                (if (seq breaches)
                  (mapv breach-reason breaches)
                  [(reason/create :reason/grant-exceeded
                                  (msg/system-t :decide/grant-exceeded-no-detail))]))
    (unrecognized-reasons result)))
