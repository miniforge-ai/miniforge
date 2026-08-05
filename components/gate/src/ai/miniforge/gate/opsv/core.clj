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

(def ^{:stratum 0} ^:private default-production-environment "production")

(defn- ^{:stratum 0} collection-value?
  [value]
  (or (sequential? value) (set? value)))

(defn- ^{:stratum 0} finite-number
  [value]
  (when (number? value)
    (let [number (double value)]
      (when (Double/isFinite number) number))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} value-set
  [value]
  (if (collection-value? value) (set value) #{}))

(defn- ^{:stratum 1} nonnegative-number
  [value]
  (let [number (finite-number value)]
    (when (and number (not (neg? number))) number)))

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
  [gate-key gate-id check]
  {:name gate-key
   :description (msg/t (get-in gate-copy [gate-id :description]))
   :check check
   :repair nil})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-instrumentation
  [pack ctx]
  (let [configured (:experiment-pack/required-instrumentation pack)
        required (value-set configured)
        status (:opsv/instrumentation-status ctx)
        unhealthy (->> required
                       (remove #(let [signal (get status %)]
                                  (and (true? (:available? signal))
                                       (true? (:reliable? signal)))))
                       sort vec)]
    (gate-result :instrumentation
                 (when (or (not (collection-value? configured))
                           (seq unhealthy))
                   {:reason-code :signals-unavailable-or-unreliable
                    :details {:signals unhealthy
                              :invalid-required-instrumentation?
                              (not (collection-value? configured))}}))))

(defn ^{:stratum 2} check-environment
  [pack ctx]
  (let [targets (value-set (get-in pack [:experiment-pack/targets
                                         :environments]))
        allowed (value-set (:opsv/allowed-environments ctx))
        open (value-set (:opsv/time-window-open-environments ctx))
        production (conj (value-set (:opsv/production-environments ctx))
                         default-production-environment)
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
        replica-delta (nonnegative-number (:replica-delta proposed))
        node-delta (nonnegative-number (:node-delta proposed))
        max-replicas (finite-number (:max-replica-delta limits))
        max-nodes (finite-number (:max-node-delta limits))
        configured-namespaces (:namespaces proposed)
        namespaces (value-set configured-namespaces)
        allowed-namespaces (value-set (:allowed-namespaces limits))
        invalid? (or (some nil? [replica-delta node-delta
                                 max-replicas max-nodes])
                     (not (collection-value? configured-namespaces))
                     (empty? namespaces)
                     (and max-replicas (neg? max-replicas))
                     (and max-nodes (neg? max-nodes)))
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
                     (remove #(let [threshold (finite-number (get configured %))]
                                (and threshold (not (neg? threshold)))))
                     vec)]
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
        snapshot-refs (:opsv/metric-snapshot-artifact-refs evidence)
        missing (cond-> []
                  (not (and (string? (:opsv/experiment-pack-hash evidence))
                            (not (str/blank?
                                  (:opsv/experiment-pack-hash evidence)))))
                  (conj :opsv/experiment-pack-hash)
                  (not (and (map? (:opsv/environment-fingerprint evidence))
                            (seq (:opsv/environment-fingerprint evidence))))
                  (conj :opsv/environment-fingerprint)
                  (not (and (collection-value? snapshot-refs)
                            (seq snapshot-refs)
                            (every? uuid? snapshot-refs)))
                  (conj :opsv/metric-snapshot-artifact-refs))]
    (gate-result :evidence-completeness
                 (when (seq missing)
                   {:reason-code :required-evidence-missing
                    :details {:missing missing}}))))
