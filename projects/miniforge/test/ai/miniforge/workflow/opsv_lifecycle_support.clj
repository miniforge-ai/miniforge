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
(ns ai.miniforge.workflow.opsv-lifecycle-support
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]
   [ai.miniforge.event-stream.interface.opsv :as opsv-event]
   [ai.miniforge.opsv-adapter-simulated.interface :as simulated]
   [ai.miniforge.phase-opsv.interface :as opsv]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lifecycle-event-types
  #{:workflow/phase-started :workflow/phase-completed})

(def ^{:stratum 0} expected-domain-type-counts
  {:opsv.experiment/planned 1
   :opsv.experiment/started 1
   :opsv/load-step 2
   :opsv.convergence/iteration 2
   :opsv.policy/proposed 1
   :opsv.verification/result 1
   :opsv.actuation/emitted 1})

(def ^{:stratum 0} ^:private domain-event-schemas
  {:opsv.experiment/planned opsv-event/ExperimentPlanned
   :opsv.experiment/started opsv-event/ExperimentStarted
   :opsv/load-step opsv-event/LoadStep
   :opsv.guardrail/abort opsv-event/GuardrailAbort
   :opsv.convergence/iteration opsv-event/ConvergenceIteration
   :opsv.policy/proposed opsv-event/PolicyProposed
   :opsv.verification/result opsv-event/VerificationResult
   :opsv.actuation/emitted opsv-event/ActuationEmitted
   :opsv.drift/detected opsv-event/DriftDetected})

(def ^{:stratum 0} expected-pipeline
  (conj (mapv #(hash-map :phase %) opsv/phase-keys) {:phase :done}))

(def ^{:stratum 0} ^:private artifact-id
  #uuid "00000000-0000-0000-0000-000000000799")

;; The fixture ships in components/phase-opsv/test-resources, which the
;; workspace :test alias puts on the classpath. The project integration
;; runner (tasks/test_runner.clj) instead launches `clojure -Sdeps
;; projects/miniforge/deps.edn` from projects/miniforge, and that deps.edn
;; carries no component test-resources path — so io/resource comes back nil
;; there. Keep the working-directory-relative fallback alongside it; both
;; branches are load-bearing, one per runner.
(def ^{:stratum 0} ^:private fixture
  (let [resource-path "opsv/application-fixture.edn"
        project-relative (io/file "../../components/phase-opsv/test-resources"
                                  resource-path)
        source (or (io/resource resource-path)
                   (when (.isFile project-relative) project-relative))]
    (when-not source
      (throw (ex-info "OPSV application fixture not found"
                      {:fixture/path resource-path
                       :fixture/project-relative (.getPath project-relative)})))
    (-> source slurp edn/read-string)))

(defn ^{:stratum 0} event-type-in?
  [event-types event]
  (contains? event-types (:event/type event)))

(defn ^{:stratum 0} opsv-domain-event?
  [event]
  (some-> event :event/type namespace (str/starts-with? "opsv")))

(defn ^{:stratum 0} event-of-type
  [events event-type]
  (first (filter #(= event-type (:event/type %)) events)))

(defn ^{:stratum 0} phase-output
  [result phase-key]
  (get-in result [:execution/phase-results phase-key :result :output]))

(defn ^{:stratum 0} lifecycle-event-count
  [events phase-key event-type]
  (count (filter #(and (= event-type (:event/type %))
                       (= phase-key (:workflow/phase %)))
                 events)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-domain-event?
  [event]
  (when-let [schema (get domain-event-schemas (:event/type event))]
    (m/validate schema event)))

(defn ^{:stratum 1} test-adapter
  []
  (simulated/create-adapter fixture))

(defn ^{:stratum 1} workflow-input
  []
  {:opsv/experiment-pack
   (assoc (:experiment-pack fixture)
          :experiment-pack/actuation-intent
          :recommend-only)
   :opsv/evidence-refs [artifact-id]
   :opsv/risk-thresholds {:medium 0.3 :high 0.6 :critical 0.85}
   :opsv/metric-snapshot-artifact-refs [artifact-id]
   :opsv/policy-diff-artifact-refs [artifact-id]})

(defn ^{:stratum 1} legacy-adapter-context
  [adapter]
  {:execution/id (random-uuid)
   :execution/input
   {:opsv/adapter adapter
    :opsv/experiment-pack (:experiment-pack fixture)}})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} workflow-options
  [stream checkpoint-root]
  {:event-stream stream
   :opsv/adapter (test-adapter)
   :checkpoint/root checkpoint-root})
