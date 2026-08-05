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
(ns ai.miniforge.gate.opsv.core
  "Fail-closed evaluation for N4 section 5.1.5 OPSV gates."
  (:require
   [ai.miniforge.gate.messages :as msg]
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private gate-copy
  {:instrumentation
   {:description :opsv/instrumentation-description
    :failure :opsv/instrumentation-failed
    :remediation :opsv/instrumentation-remediation
    :action :configure-instrumentation}
   :environment
   {:description :opsv/environment-description
    :failure :opsv/environment-failed
    :remediation :opsv/environment-remediation
    :action :select-authorized-environment}
   :blast-radius
   {:description :opsv/blast-radius-description
    :failure :opsv/blast-radius-failed
    :remediation :opsv/blast-radius-remediation
    :action :reduce-blast-radius}
   :abort
   {:description :opsv/abort-description
    :failure :opsv/abort-failed
    :remediation :opsv/abort-remediation
    :action :configure-abort-thresholds}
   :actuation
   {:description :opsv/actuation-description
    :failure :opsv/actuation-failed
    :remediation :opsv/actuation-remediation
    :action :authorize-actuation}
   :evidence-completeness
   {:description :opsv/evidence-completeness-description
    :failure :opsv/evidence-completeness-failed
    :remediation :opsv/evidence-completeness-remediation
    :action :complete-evidence}})

(def ^{:stratum 0} ^:private abort-thresholds
  [:error-budget-burn :saturation :tail-latency])

(defn- ^{:stratum 0} value-set
  [value]
  (if (coll? value) (set value) #{}))

(defn- ^{:stratum 0} magnitude
  [value]
  (when (number? value) (Math/abs (double value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} gate-result
  [gate-id result]
  (if result
    (let [{:keys [failure remediation action]} (get gate-copy gate-id)]
      {:passed? false
       :errors [{:type :opsv/gate-failed
                 :gate/id gate-id
                 :reason-code (:reason-code result)
                 :message (msg/t failure)
                 :remediation {:action action
                               :summary (msg/t remediation)
                               :details (:details result)}}]})
    {:passed? true :errors []}))

(defn ^{:stratum 1} gate-definition
  [gate-id check]
  {:name gate-id
   :description (msg/t (get-in gate-copy [gate-id :description]))
   :check check
   :repair nil})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-instrumentation
  [pack ctx]
  (let [required (value-set (:experiment-pack/required-instrumentation pack))
        status (:opsv/instrumentation-status ctx)
        unhealthy (->> required
                       (remove #(let [signal (get status %)]
                                  (and (true? (:available? signal))
                                       (true? (:reliable? signal)))))
                       sort vec)]
    (gate-result :instrumentation
                 (when (seq unhealthy)
                   {:reason-code :signals-unavailable-or-unreliable
                    :details {:signals unhealthy}}))))

(defn ^{:stratum 2} check-environment
  [pack ctx]
  (let [targets (value-set (get-in pack [:experiment-pack/targets
                                         :environments]))
        allowed (value-set (:opsv/allowed-environments ctx))
        open (value-set (:opsv/time-window-open-environments ctx))
        production (value-set (or (:opsv/production-environments ctx)
                                  ["production"]))
        production-allowlist (value-set (:opsv/production-allowlist ctx))
        disallowed (set/difference targets allowed)
        outside-window (set/difference targets open)
        production-denied (set/difference (set/intersection targets production)
                                          production-allowlist)]
    (gate-result :environment
                 (when (or (empty? targets) (seq disallowed)
                           (seq outside-window) (seq production-denied))
                   {:reason-code :environment-policy-violation
                    :details {:disallowed (vec (sort disallowed))
                              :outside-time-window (vec (sort outside-window))
                              :production-not-allowlisted
                              (vec (sort production-denied))}}))))

(defn ^{:stratum 2} check-blast-radius
  [pack ctx]
  (let [proposed (get-in pack [:experiment-pack/guardrails :blast-radius])
        limits (:opsv/blast-radius-limits ctx)
        replica-delta (magnitude (:replica-delta proposed))
        node-delta (magnitude (:node-delta proposed))
        max-replicas (magnitude (:max-replica-delta limits))
        max-nodes (magnitude (:max-node-delta limits))
        namespaces (value-set (:namespaces proposed))
        allowed-namespaces (value-set (:allowed-namespaces limits))
        invalid? (some nil? [replica-delta node-delta max-replicas max-nodes])
        unauthorized (set/difference namespaces allowed-namespaces)]
    (gate-result :blast-radius
                 (when (or invalid? (and (not invalid?)
                                         (or (> replica-delta max-replicas)
                                             (> node-delta max-nodes)))
                           (seq unauthorized))
                   {:reason-code :blast-radius-limit-exceeded
                    :details {:proposed proposed :limits limits
                              :unauthorized-namespaces
                              (vec (sort unauthorized))}}))))

(defn ^{:stratum 2} check-abort
  [pack _ctx]
  (let [configured (get-in pack [:experiment-pack/guardrails
                                 :abort-thresholds])
        missing (->> abort-thresholds
                     (remove #(number? (get configured %))) vec)]
    (gate-result :abort
                 (when (seq missing)
                   {:reason-code :abort-thresholds-missing
                    :details {:missing missing}}))))

(defn ^{:stratum 2} check-actuation
  [pack ctx]
  (let [apply? (= :apply-allowed (:experiment-pack/actuation-intent pack))
        services (value-set (get-in pack [:experiment-pack/targets :services]))
        allowlist (value-set (:opsv/apply-service-allowlist ctx))
        unauthorized (set/difference services allowlist)]
    (gate-result :actuation
                 (when (and apply?
                            (or (not (true? (:opsv/apply-enabled? ctx)))
                                (empty? services)
                                (seq unauthorized)))
                   {:reason-code :apply-not-authorized
                    :details {:apply-enabled? (true? (:opsv/apply-enabled? ctx))
                              :unauthorized-services
                              (vec (sort unauthorized))}}))))

(defn ^{:stratum 2} check-evidence-completeness
  [artifact ctx]
  (let [evidence (or (:opsv/evidence ctx) artifact)
        missing (cond-> []
                  (not (and (string? (:opsv/experiment-pack-hash evidence))
                            (not (str/blank?
                                  (:opsv/experiment-pack-hash evidence)))))
                  (conj :opsv/experiment-pack-hash)
                  (not (map? (:opsv/environment-fingerprint evidence)))
                  (conj :opsv/environment-fingerprint)
                  (not (seq (:opsv/metric-snapshot-artifact-refs evidence)))
                  (conj :opsv/metric-snapshot-artifact-refs))]
    (gate-result :evidence-completeness
                 (when (seq missing)
                   {:reason-code :required-evidence-missing
                    :details {:missing missing}}))))
