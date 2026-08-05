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
(ns ai.miniforge.opsv.actuation
  "Pure monotonic N7 actuation authority decisions.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} gates-passed?
  [gate-results]
  (every? :gate/passed? gate-results))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pr-allowed?
  [{:keys [gate-results pr-capability-valid?]}]
  (and (gates-passed? gate-results) pr-capability-valid?))

(defn ^{:stratum 1} apply-allowed?
  [{:keys [verification-passed? gate-results apply-capability-valid?
           rollback-verified? postconditions-configured?]}]
  (and verification-passed?
       (gates-passed? gate-results)
       apply-capability-valid?
       rollback-verified?
       postconditions-configured?))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} effective-actuation-impl
  "Decide from validated inputs. The interface owns validation."
  [{:keys [requested-actuation-mode safe-mode?] :as decision-input}]
  (if safe-mode?
    :none
    (case requested-actuation-mode
      :recommend-only :recommend-only
      :pr-only (if (pr-allowed? decision-input)
                 :pr-only
                 :recommend-only)
      :apply-allowed (cond
                       (apply-allowed? decision-input) :apply-allowed
                       (pr-allowed? decision-input) :pr-only
                       :else :recommend-only))))
