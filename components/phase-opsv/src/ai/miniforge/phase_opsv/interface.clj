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
(ns ai.miniforge.phase-opsv.interface
  "Public OPSV application transformations and adapter port."
  (:require
   [ai.miniforge.phase-opsv.actuation :as actuation]
   [ai.miniforge.phase-opsv.model :as model]
   [ai.miniforge.phase-opsv.run-control :as run-control]
   [ai.miniforge.phase-opsv.run-control-state :as control-state]
   [ai.miniforge.phase-opsv.protocol :as protocol]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OPSVAdapter protocol/OPSVAdapter)

(def ^{:stratum 0} discover-signals protocol/discover-signals)

(def ^{:stratum 0} run-guarded-ramp protocol/run-guarded-ramp)

(defn ^{:stratum 0} create-run-supervisor
  "Create a host-owned OPSV stop domain. Stopping it cannot be reversed."
  [] (control-state/create))

(defn ^{:stratum 0} register-run-control!
  "Register a run before execution. Returns runtime control/fence handles or an anomaly.
   request-abort! must acknowledge scheduling a safe-boundary load abort with true."
  [supervisor workflow-id authority-directory request-abort!]
  (run-control/register! supervisor workflow-id authority-directory request-abort!))

(defn ^{:stratum 0} stop-supervised-runs!
  "Close shared mutation admission first, then request aborts and revoke tracked grants.
   Reports incomplete cleanup; admitted mutations may still settle or remain unknown."
  [supervisor now] (run-control/stop! supervisor now))

(defn ^{:stratum 0} retire-run-control!
  "Stop and clean up a completed run. Retain it while cleanup or admitted effects remain."
  [handle now] (run-control/retire! handle now))

(def ^{:stratum 0} phase-keys
  [:opsv/discover :opsv/plan :opsv/execute :opsv/converge
   :opsv/synthesize :opsv/verify :opsv/actuate])

(def ^{:stratum 0} discover model/discover)

(def ^{:stratum 0} plan model/plan)

(def ^{:stratum 0} execute model/execute)

(def ^{:stratum 0} converge model/converge)

(def ^{:stratum 0} synthesize model/synthesize)

(def ^{:stratum 0} verify model/verify)

(defn ^{:stratum 0} actuate
  [ctx]
  (actuation/actuate
   ctx (get-in ctx [:execution/phase-results :opsv/verify :result :output])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} functional-adapter
  "Build an OPSV adapter from pure discovery and guarded-ramp functions."
  [discover-fn guarded-ramp-fn]
  (reify protocol/OPSVAdapter
    (discover-signals [_ targets]
      (discover-fn targets))
    (run-guarded-ramp [_ experiment-pack]
      (guarded-ramp-fn experiment-pack))))
