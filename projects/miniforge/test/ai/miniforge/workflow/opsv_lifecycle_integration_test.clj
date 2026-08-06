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
(ns ai.miniforge.workflow.opsv-lifecycle-integration-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.protocol :as port]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private fixture
  (-> "opsv/application-fixture.edn" io/resource slurp edn/read-string))

(def ^{:stratum 0} expected-pipeline
  (conj (mapv #(hash-map :phase %) opsv/phase-keys) {:phase :done}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} test-adapter
  []
  (reify port/OPSVAdapter
    (discover-signals [_ _targets]
      [{:driver :cpu} {:driver :backlog}])
    (run-guarded-ramp [_ _pack]
      {:environment-fingerprint
       {:cluster "staging-1"
        :node-pools ["general"]
        :image-digests {"catalog" "sha256:abc"}
        :config-hash "config-1"}
       :steps (:ramp-steps fixture)})))

(deftest ^{:stratum 1} opsv-workflow-loads-exact-versioned-pipeline-test
  (let [loaded (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true})]
    (is (= :resource (:source loaded)))
    (is (true? (get-in loaded [:validation :valid?])))
    (is (= expected-pipeline (get-in loaded [:workflow :workflow/pipeline])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} opsv-interceptor-preserves-agent-override-test
  (let [adapter (test-adapter)
        interceptor (phase/get-phase-interceptor
                     {:phase :opsv/discover :agent :opsv-test-agent})
        entered ((:enter interceptor)
                 {:execution/id (random-uuid)
                  :execution/input
                  {:opsv/adapter adapter
                   :opsv/experiment-pack (:experiment-pack fixture)}})]
    (is (= :opsv-test-agent (get-in entered [:phase :agent])))
    (is (identical? adapter (get-in entered
                                    [:execution/opts :opsv/adapter])))
    (is (not (contains? (:execution/input entered) :opsv/adapter)))))

(deftest ^{:stratum 2} opsv-evidence-runtime-restores-from-durable-input-test
  (let [workflow-id (random-uuid)
        interceptor (phase/get-phase-interceptor {:phase :opsv/discover})
        entered ((:enter interceptor)
                 {:execution/id workflow-id
                  :execution/input
                  {:opsv/experiment-pack (:experiment-pack fixture)}
                  :execution/opts {:opsv/adapter (test-adapter)}})
        checkpointed ((:leave interceptor) entered)
        resumed (dissoc checkpointed :opsv/evidence-assembly-store)
        restored ((:enter interceptor) resumed)
        bundle-id (get-in restored
                          [:execution/input :opsv/evidence-bundle-id])]
    (is (= (get-in checkpointed
                   [:execution/input :opsv/evidence-assembly])
           (evidence/get-opsv-assembly
            (:opsv/evidence-assembly-store restored) bundle-id)))))
