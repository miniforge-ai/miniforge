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
(ns ai.miniforge.agent-environment.conformance
  "The contract suite every provider must pass. Each check drives a fresh
   controller through the public lifecycle with synthetic tenants and
   grants. A check with no fixture from the provider is unrun, never passed."
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.lifecycle :as lifecycle]
   [ai.miniforge.agent-environment.schema :as schema]
   [ai.miniforge.anomaly.interface :as anomaly]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} duplicate-acquire [w]
  (let [req (fx/request w (:a w))
        first-env (fx/acquire w (:a w) req)
        second-env (fx/acquire w (:a w) req)]
    (fx/result :check/duplicate-acquire-returns-one-environment
               (and (map? first-env) (= first-env second-env)
                    (= [:outcome/applied :outcome/duplicate] (fx/outcomes w :environment/acquire)))
               {:acquire (fx/outcomes w :environment/acquire)})))

(defn- ^{:stratum 0} conflicting-request [w]
  (let [req (fx/request w (:a w))
        env (fx/acquire w (:a w) req)
        changed (assoc req :environment/template-hash (second fx/template-hashes))
        refused (fx/acquire w (:a w) changed)
        intact (lifecycle/readiness (:c w) (:a w) (:environment/lease env))]
    (fx/result :check/conflicting-request-refused
               (and (fx/subtype? :anomalies.agent-environment/conflicting-request refused)
                    (map? intact)
                    (not (anomaly/anomaly? intact)))
               {:refused (anomaly/subtype refused)})))

(defn- ^{:stratum 0} forged-tenant [w]
  (let [forged (fx/acquire w (:a w) (fx/request w (:b w)))
        env (fx/acquire w (:b w) (fx/request w (:b w)))
        borrowed (lifecycle/exec! (:c w) (:a w) (:environment/lease env) fx/probe-command)]
    (fx/result :check/forged-tenant-refused
               (and (fx/subtype? :anomalies.agent-environment/forged-tenant forged)
                    (fx/subtype? :anomalies.agent-environment/forged-tenant borrowed))
               {:acquire (anomaly/subtype forged) :exec (anomaly/subtype borrowed)})))

(defn- ^{:stratum 0} grant-required [w]
  (let [req (fx/request w (:a w))
        absent (lifecycle/acquire! (:c w) (:a w) nil req)
        other-run (fx/provision-grant (assoc w :run-id (random-uuid)) (:a w))
        mismatched (lifecycle/acquire! (:c w) (:a w) other-run req)]
    (fx/result :check/grant-required
               (and (fx/subtype? :anomalies.agent-environment/grant-absent absent)
                    (fx/subtype? :anomalies.agent-environment/grant-scope-mismatch mismatched))
               {:absent (anomaly/subtype absent) :other-run (anomaly/subtype mismatched)})))

(defn- ^{:stratum 0} stale-lease [w await-ready]
  (let [req (fx/request w (:a w))
        old-env (fx/acquire w (:a w) req)
        _ (lifecycle/release! (:c w) (:a w) (:environment/lease old-env))
        new-env (fx/acquire w (:a w) req)
        _ (await-ready #(lifecycle/readiness (:c w) (:a w) (:environment/lease new-env)))
        stale (lifecycle/exec! (:c w) (:a w) (:environment/lease old-env) fx/probe-command)
        current (lifecycle/exec! (:c w) (:a w) (:environment/lease new-env) fx/probe-command)]
    (fx/result :check/stale-lease-refused
               (and (fx/subtype? :anomalies.agent-environment/stale-lease stale)
                    (not (anomaly/anomaly? current))
                    (< (get-in old-env [:environment/lease :lease/generation])
                       (get-in new-env [:environment/lease :lease/generation])))
               {:stale (anomaly/subtype stale)})))

(defn- ^{:stratum 0} lifecycle-roundtrip [w await-ready]
  (let [c (:c w)
        a (:a w)
        lease (:environment/lease (fx/acquire w a (fx/request w a)))
        ready (await-ready #(lifecycle/readiness c a lease))
        ran (lifecycle/exec! c a lease fx/probe-command)
        written (lifecycle/write-file! c a lease fx/probe-path fx/probe-text)
        read-back (lifecycle/read-file c a lease fx/probe-path)
        artifacts (lifecycle/persist-artifacts! c a lease [fx/probe-path])
        cancelled (lifecycle/cancel! c a lease)
        after-cancel (lifecycle/exec! c a lease fx/probe-command)
        released (lifecycle/release! c a lease)
        after-release (lifecycle/readiness c a lease)]
    (fx/result :check/lifecycle-roundtrip
               (and (:readiness/ready? ready)
                    (= 0 (:exec/exit ran))
                    (map? written)
                    (not (anomaly/anomaly? written))
                    (= fx/probe-text read-back)
                    (vector? artifacts)
                    (= 1 (count artifacts))
                    (= :outcome/applied (:call/outcome cancelled))
                    (fx/subtype? :anomalies.agent-environment/environment-cancelled after-cancel)
                    (= :outcome/applied (:call/outcome released))
                    (fx/subtype? :anomalies.agent-environment/environment-released after-release))
               {:ready ready :exec (:exec/exit ran) :release released})))

(defn- ^{:stratum 0} partial-provisioning [w]
  (let [refused (fx/acquire w (:a w) (fx/request w (:a w)))]
    (fx/result :check/partial-provisioning-receipts
               (and (fx/subtype? :anomalies.agent-environment/provisioning-failed refused)
                    (= [:outcome/partial] (fx/outcomes w :environment/acquire))
                    (= 1 (count (fx/outcomes w :environment/release))))
               {:acquire (fx/outcomes w :environment/acquire) :release (fx/outcomes w :environment/release)})))

(defn- ^{:stratum 0} receipts-unqualified [worlds]
  (let [all (mapcat (comp deref :receipts) worlds)
        bad (remove #(and (m/validate schema/Receipt %) (= :profile/unqualified (:receipt/profile %))) all)]
    (fx/result :check/receipts-valid-and-unqualified (and (seq all) (empty? bad)) {:invalid (count bad)})))

(defn- ^{:stratum 0} cleanup-confirmed [cleaned]
  (fx/result :check/cleanup-confirmed (every? true? cleaned) {:unconfirmed (count (remove true? cleaned))}))

(defn- ^{:stratum 0} unrun [id]
  {:check/id id :check/outcome :check/unrun :check/detail {}})

(def ^{:stratum 0} default-isolation
  "Isolation the suite requests unless the provider's fixture says otherwise."
  :isolation/shared-kernel)

(defn- ^{:stratum 0} call-once [f] (f))

(defn- ^{:stratum 0} checked
  "Run `check` on `w`, then release everything it acquired, also when the
   check throws; returns the result and whether cleanup was confirmed."
  [check w]
  (let [cleaned (volatile! false)
        result (try (check w)
                    (finally (vreset! cleaned (fx/release-all! w))))]
    [result @cleaned]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run
  [{:keys [make-provider make-partial-provider] :as opts}]
  (let [isolation (get opts :isolation default-isolation)
        await-ready (get opts :await-ready call-once)
        fresh #(fx/world (make-provider) isolation)
        partial-world (when make-partial-provider (fx/world (make-partial-provider) isolation))
        runs [[duplicate-acquire (fresh)]
              [conflicting-request (fresh)]
              [forged-tenant (fresh)]
              [grant-required (fresh)]
              [#(stale-lease % await-ready) (fresh)]
              [#(lifecycle-roundtrip % await-ready) (fresh)]]
        outcomes (cond-> (mapv (fn [[check w]] (checked check w)) runs)
                   partial-world (conj (checked partial-provisioning partial-world)))
        worlds (cond-> (mapv second runs) partial-world (conj partial-world))]
    (-> (mapv first outcomes)
        (cond-> (nil? partial-world) (conj (unrun :check/partial-provisioning-receipts)))
        (conj (receipts-unqualified worlds))
        (conj (cleanup-confirmed (mapv second outcomes))))))
