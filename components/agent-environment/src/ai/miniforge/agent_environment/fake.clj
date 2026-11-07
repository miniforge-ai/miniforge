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
(ns ai.miniforge.agent-environment.fake
  "An in-memory provider for contract tests and fake-backed pilots. Options
   inject faults: `:fake/fail-step` stops provisioning at that step after
   the earlier ones apply, `:fake/uncertain-deletes?` leaves deletes
   unconfirmed, `:fake/probe-failures` fails the live probe,
   `:fake/omit-uid?` creates the namespace but never reports its UID,
   `:fake/uncertain-steps?` reports the steps it applied as uncertain, and
   `:fake/recreated-after-provision?` has an outside actor recreate it at once."
  (:require
   [ai.miniforge.agent-environment.fake.world :as world]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]))

;------------------------------------------------------------------------------ Layer 0

(defrecord ^{:stratum 0} FakeProvider [state opts]
  protocol/EnvironmentProvider
  (capabilities [_] (get opts :fake/capabilities world/default-capabilities))
  (probe [_ _request] (get opts :fake/probe-failures []))
  (provision! [_ target _request]
    (let [{:keys [applied failed]} (world/walk-steps (:fake/fail-step opts))
          uid (when (seq applied) (str (random-uuid)))
          reported (if (:fake/uncertain-steps? opts) :outcome/uncertain :outcome/applied)]
      (when uid (swap! state world/provisioned (:target/namespace target) uid (:target/generation target) applied))
      (when (and uid (:fake/recreated-after-provision? opts)) (swap! state world/recreated (:target/namespace target)))
      {:provision/uid (when-not (:fake/omit-uid? opts) uid)
       :provision/steps (cond-> (mapv #(world/step % reported) applied)
                          failed (conj (world/step failed :outcome/failed)))}))
  (observe [_ target]
    (let [space (get @state (:target/namespace target))]
      {:observed/uid (:uid space) :observed/generation (:generation space) :observed/ready? (boolean (:ready? space))}))
  (deprovision! [_ target]
    (let [uncertain? (:fake/uncertain-deletes? opts)
          [before _] (swap-vals! state world/delete target uncertain?)]
      {:deprovision/steps [(world/step :step/namespace-deleted (world/delete-outcome before target uncertain?))]})))

(defn ^{:stratum 0} replace-uid!
  "Recreate `namespace` under a new UID and without this controller's
   generation, as an outside actor reusing the name would."
  [provider namespace]
  (swap! (:state provider) world/recreated namespace))

(defn ^{:stratum 0} forget-namespace!
  "Delete `namespace` behind the controller's back, as an outside actor would."
  [provider namespace]
  (swap! (:state provider) dissoc namespace))

(defn ^{:stratum 0} live-namespaces
  [provider]
  (set (keys @(:state provider))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create
  [opts]
  (->FakeProvider (atom {}) opts))
