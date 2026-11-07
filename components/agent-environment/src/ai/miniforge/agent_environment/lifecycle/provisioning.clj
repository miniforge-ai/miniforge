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
(ns ai.miniforge.agent-environment.lifecycle.provisioning
  "Provisioning a claimed generation: a complete provision becomes the
   key's environment; one that stops part way is receipted, cleaned up at
   once, and left `cleanup-uncertain` when the cleanup is not confirmed. An
   object whose ownership is not shown is never deleted by name."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.refusal :as refusal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maybe-created
  "Step outcomes after which something may exist to recover."
  #{:outcome/applied :outcome/uncertain})

(defn- ^{:stratum 0} cleanup!
  [c subject target uid]
  (let [steps (:deprovision/steps (protocol/deprovision! (support/provider c) (assoc target :target/uid uid)))]
    (controller/record! c subject :environment/release (receipt/steps-outcome steps) steps
                        :anomalies.agent-environment/provisioning-failed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recovered-uid
  "A provider that applied (or may have applied) steps but reported no UID
   is asked for it, so the environment still gets a lease. The UID is
   adopted only from an object carrying this generation, so one recreated
   under the name is not ours; nil when nothing may exist or ownership is
   not shown."
  [c target steps]
  (when (some #(contains? maybe-created (:step/outcome %)) steps)
    (let [observed (protocol/observe (support/provider c) target)]
      (when (= (:target/generation target) (:observed/generation observed))
        (:observed/uid observed)))))

(defn- ^{:stratum 1} provision-failed
  [c acting request target environment steps]
  (let [subject (support/subject-of acting request environment)
        outcome (support/unconfirmed-if-anonymous (receipt/steps-outcome steps) environment)
        acquired (controller/record! c subject :environment/acquire outcome steps
                                     :anomalies.agent-environment/provisioning-failed)
        cleaned (when (and environment (not= :outcome/failed outcome))
                  (cleanup! c subject target (:environment/uid environment)))
        state (if (or (= :outcome/failed outcome) (= :outcome/applied (:receipt/outcome cleaned)))
                :state/released
                :state/cleanup-uncertain)]
    (controller/settle-provision! c request (:target/generation target) state environment)
    (refusal/reason-anomaly :anomalies.agent-environment/provisioning-failed
                            {:receipt/ids (filterv some? [(:receipt/id acquired) (:receipt/id cleaned)])
                             :environment/lease (:environment/lease environment)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} provision
  [c acting request generation]
  (let [target {:target/namespace (lease/namespace-name request)
                :target/uid nil
                :target/generation generation}
        provisioned (protocol/provision! (support/provider c) target request)
        steps (:provision/steps provisioned)
        env (support/environment request target (or (:provision/uid provisioned) (recovered-uid c target steps)))]
    (if (and env (= :outcome/applied (receipt/steps-outcome steps)))
      (do (controller/settle-provision! c request generation :state/provisioned env)
          (controller/record! c (support/subject-of acting request env) :environment/acquire :outcome/applied steps nil)
          env)
      (provision-failed c acting request target env steps))))
