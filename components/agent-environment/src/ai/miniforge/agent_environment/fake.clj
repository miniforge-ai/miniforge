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
   unconfirmed, `:fake/probe-failures` fails the live probe, `:fake/exec-fn`
   scripts exec results, and `:fake/omit-uid?` creates the namespace but
   never reports its UID."
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
          uid (when (seq applied) (str (random-uuid)))]
      (when uid (swap! state world/provisioned (:target/namespace target) uid applied))
      {:provision/uid (when-not (:fake/omit-uid? opts) uid)
       :provision/steps (cond-> (mapv #(world/step % :outcome/applied) applied)
                          failed (conj (world/step failed :outcome/failed)))}))
  (observe [_ target]
    (let [space (get @state (:target/namespace target))]
      {:observed/uid (:uid space) :observed/ready? (boolean (:ready? space))}))
  (exec! [_ target command]
    (if (world/live @state target)
      (do (swap! state update-in [(:target/namespace target) :commands] (fnil conj []) command)
          ((get opts :fake/exec-fn world/echo) command))
      (world/missing target)))
  (write-file! [_ target path text]
    (if (world/live @state target)
      (do (swap! state assoc-in [(:target/namespace target) :files path] text) {:file/path path})
      (world/missing target)))
  (read-file [_ target path]
    (if-let [space (world/live @state target)]
      (get-in space [:files path] (world/file-missing path))
      (world/missing target)))
  (cancel! [_ target]
    (if (world/live @state target)
      (do (swap! state assoc-in [(:target/namespace target) :cancelled?] true)
          {:cancel/steps [(world/step :step/workload-stopped :outcome/applied)]})
      {:cancel/steps [(world/step :step/workload-stopped :outcome/failed)]}))
  (deprovision! [_ target]
    (let [uncertain? (:fake/uncertain-deletes? opts)
          [before _] (swap-vals! state world/delete target uncertain?)]
      {:deprovision/steps [(world/step :step/namespace-deleted (world/delete-outcome before target uncertain?))]})))

(defn ^{:stratum 0} replace-uid!
  "Recreate `namespace` under a new UID, as an outside actor reusing the name would."
  [provider namespace]
  (swap! (:state provider) assoc-in [namespace :uid] (str (random-uuid))))

(defn ^{:stratum 0} commands
  "Every command exec'd in `namespace`, in order."
  [provider namespace]
  (get-in @(:state provider) [namespace :commands] []))

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
