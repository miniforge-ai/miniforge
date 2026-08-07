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
(ns ai.miniforge.execution-grant.eligibility
  "Whether breach history permits a principal's requested ceilings."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.eligibility.ceiling :as ceiling]
   [ai.miniforge.execution-grant.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} permitted-ceiling
  "The highest ceiling breach history permits for one authority axis."
  ceiling/permitted-ceiling)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} axis-eligible?
  [dir principal effect-class constraints axis]
  (let [cap (permitted-ceiling dir principal effect-class axis)
        requested (get constraints axis)]
    (cond
      (anomaly/anomaly? cap) false
      (nil? cap) true
      :else (and (some? requested) (number? requested) (< requested cap)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} eligible?
  "May this principal be granted `effect-class` at `constraints`?

   False when a request reaches or exceeds a previously breached
   ceiling. An absent ceiling is the widest request and is refused
   once history establishes a bound. Unreadable history also refuses
   issuance rather than presenting a principal as clean."
  [dir principal effect-class constraints]
  (every? #(axis-eligible? dir principal effect-class constraints %)
          schema/constraint-axes))
