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
(ns ai.miniforge.agent-worker.controller
  "The worker controller's state: the selected workers, the environment
   controller every worker command goes through, and the submission
   ledger, written before a worker starts so a retry reconciles instead of
   launching again."
  (:require
   [ai.miniforge.agent-worker.session :as session]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create
  [{:keys [workers environment clock]}]
  {:worker/workers workers
   :worker/environment environment
   :worker/clock clock
   :worker/ledger (atom {})})

(defn ^{:stratum 0} record
  [wc binding]
  (get @(:worker/ledger wc) (session/submission-key binding)))

(defn ^{:stratum 0} lease-of
  "The environment lease a binding names."
  [binding]
  {:lease/tenant-id (:worker/tenant-id binding)
   :lease/run-id (:worker/run-id binding)
   :lease/task-id (:worker/task-id binding)
   :lease/environment-uid (:worker/environment-uid binding)
   :lease/generation (:worker/lease-generation binding)})

(defn- ^{:stratum 0} claim-record
  [ledger k submission]
  (if (contains? ledger k)
    ledger
    (assoc ledger k {:record/submission submission :record/result nil :record/cancel-requested? false})))

(defn- ^{:stratum 0} reserved
  [record session]
  (if (and (= session (:record/result record)) (not (:record/turn-starting? record)))
    (assoc record :record/turn-starting? true)
    record))

(defn ^{:stratum 0} turn-settled
  "`record` with its turn reservation cleared and, when the turn started,
   `steered` as its session."
  [record steered]
  (cond-> (dissoc record :record/turn-starting?) steered (assoc :record/result steered)))

(defn ^{:stratum 0} settle!
  "Store the start result (session or anomaly) or a later session turn."
  [wc binding f]
  (swap! (:worker/ledger wc) update (session/submission-key binding) f))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} env-handle
  [wc acting binding]
  {:env/controller (:worker/environment wc)
   :env/acting acting
   :env/lease (lease-of binding)})

(defn ^{:stratum 1} reserve-turn!
  "Reserve `session`'s next turn; true only for the one call that did."
  [wc session]
  (let [k (session/submission-key (:session/binding session))
        [before after] (swap-vals! (:worker/ledger wc) update k reserved session)]
    (not= (get before k) (get after k))))

(defn ^{:stratum 1} claim!
  "Record `submission` before anything starts and return the decision the
   ledger held before this call."
  [wc submission]
  (let [k (session/submission-key (:submission/binding submission))
        [before _] (swap-vals! (:worker/ledger wc) claim-record k submission)]
    (session/submission-decision (get before k) submission)))
