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
(ns ai.miniforge.phase-opsv.test-support
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.phase-opsv.interface :as opsv-phase]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} handlers
  [[:opsv/discover opsv-phase/discover]
   [:opsv/plan opsv-phase/plan]
   [:opsv/execute opsv-phase/execute]
   [:opsv/converge opsv-phase/converge]
   [:opsv/synthesize opsv-phase/synthesize]
   [:opsv/verify opsv-phase/verify]
   [:opsv/actuate opsv-phase/actuate]])

(defn ^{:stratum 0} test-adapter
  [steps]
  (reify opsv-phase/OPSVAdapter
    (discover-signals [_ _targets]
      [{:driver :cpu} {:driver :backlog}])
    (run-guarded-ramp [_ _pack]
      {:environment-fingerprint
       {:cluster "staging-1"
        :node-pools ["general"]
        :image-digests {"catalog" "sha256:abc"}
        :config-hash "config-1"}
       :steps steps})))

(defn ^{:stratum 0} run-transformation
  [ctx [phase-key handler]]
  (assoc-in ctx [:execution/phase-results phase-key :result :output]
            (handler ctx)))

(defn ^{:stratum 0} phase-output
  [ctx phase-key]
  (get-in ctx [:execution/phase-results phase-key :result :output]))

(def ^{:stratum 0} ^:private evidence-id
  #uuid "00000000-0000-0000-0000-000000000701")

(def ^{:stratum 0} ^:private artifact-id
  #uuid "00000000-0000-0000-0000-000000000702")

(def ^{:stratum 0} ^:private fixture
  (-> "opsv/application-fixture.edn" io/resource slurp edn/read-string))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ramp-steps
  (:ramp-steps fixture))

(defn ^{:stratum 1} execution-context
  [adapter]
  {:execution/id #uuid "00000000-0000-0000-0000-000000000700"
   :execution/input
   {:opsv/adapter adapter
    :opsv/experiment-pack (:experiment-pack fixture)
    :opsv/evidence-bundle-id evidence-id
    :opsv/evidence-refs [artifact-id]
    :opsv/risk-thresholds {:medium 0.3 :high 0.6 :critical 0.85}
    :opsv/metric-snapshot-artifact-refs [artifact-id]
    :opsv/policy-diff-artifact-refs [artifact-id]}
   :execution/phase-results {}})
