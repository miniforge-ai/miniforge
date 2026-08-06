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
   [malli.core :as m]
   [ai.miniforge.event-stream.interface.opsv :as opsv-event]
   [ai.miniforge.phase-opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.protocol :as port])
  (:import
   [org.apache.commons.io FileUtils]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lifecycle-event-types
  #{:workflow/phase-started :workflow/phase-completed})

(def ^{:stratum 0} domain-event-types
  #{:opsv.experiment/planned
    :opsv.experiment/started
    :opsv/load-step
    :opsv.convergence/iteration
    :opsv.policy/proposed
    :opsv.verification/result
    :opsv.actuation/emitted})

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
   :opsv.convergence/iteration opsv-event/ConvergenceIteration
   :opsv.policy/proposed opsv-event/PolicyProposed
   :opsv.verification/result opsv-event/VerificationResult
   :opsv.actuation/emitted opsv-event/ActuationEmitted})

(def ^{:stratum 0} expected-pipeline
  (conj (mapv #(hash-map :phase %) opsv/phase-keys) {:phase :done}))

(def ^{:stratum 0} ^:private artifact-id
  #uuid "00000000-0000-0000-0000-000000000799")

(def ^{:stratum 0} ^:private fixture
  (let [resource-path "opsv/application-fixture.edn"
        project-relative (io/file "../../components/phase-opsv/test-resources"
                                  resource-path)
        source (or (io/resource resource-path)
                   (when (.isFile project-relative) project-relative))]
    (when-not source
      (throw (ex-info "OPSV application fixture not found"
                      {:fixture/path resource-path})))
    (-> source slurp edn/read-string)))

(def ^{:stratum 0} ^:private environment-fingerprint
  {:cluster "staging-1"
   :node-pools ["general"]
   :image-digests {"catalog" "sha256:abc"}
   :config-hash "config-1"})

(defn ^{:stratum 0} event-type-in?
  [event-types event]
  (contains? event-types (:event/type event)))

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

(defn ^{:stratum 0} with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "opsv-lifecycle-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally
        (FileUtils/deleteDirectory root)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-domain-event?
  [event]
  (when-let [schema (get domain-event-schemas (:event/type event))]
    (m/validate schema event)))

(defn ^{:stratum 1} test-adapter
  []
  (reify port/OPSVAdapter
    (discover-signals [_ _targets]
      [{:driver :cpu} {:driver :backlog}])
    (run-guarded-ramp [_ _pack]
      {:environment-fingerprint environment-fingerprint
       :steps (:ramp-steps fixture)})))

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
