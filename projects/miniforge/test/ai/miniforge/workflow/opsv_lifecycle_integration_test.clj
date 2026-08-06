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
   [ai.miniforge.workflow.interface :as workflow])
  (:import
   [org.apache.commons.io FileUtils]))

;------------------------------------------------------------------------------ Layer 0

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

(def ^{:stratum 0} ^:private artifact-id
  #uuid "00000000-0000-0000-0000-000000000799")

(def ^{:stratum 0} ^:private expected-pipeline
  (conj (mapv #(hash-map :phase %) opsv/phase-keys) {:phase :done}))

(defn- ^{:stratum 0} with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "opsv-lifecycle-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally (FileUtils/deleteDirectory root)))))

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

(defn- ^{:stratum 1} workflow-input
  []
  {:opsv/experiment-pack (:experiment-pack fixture)
   :opsv/evidence-refs [artifact-id]
   :opsv/risk-thresholds {:medium 0.3 :high 0.6 :critical 0.85}
   :opsv/metric-snapshot-artifact-refs [artifact-id]
   :opsv/policy-diff-artifact-refs [artifact-id]})

(deftest ^{:stratum 1} test-exact-opsv-workflow-resource
  (let [loaded (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true})]
    (is (= :resource (:source loaded)))
    (is (true? (get-in loaded [:validation :valid?])))
    (is (= expected-pipeline (get-in loaded [:workflow :workflow/pipeline])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-runtime-adapter-authority
  (let [legacy-adapter (test-adapter)
        runtime-adapter (test-adapter)
        interceptor (phase/get-phase-interceptor
                     {:phase :opsv/discover :agent :opsv-test-agent})
        enter (:enter interceptor)
        context {:execution/id (random-uuid)
                 :execution/input (assoc (workflow-input)
                                         :opsv/adapter legacy-adapter)}
        migrated (enter context)
        preferred (enter (assoc context :execution/opts
                                {:opsv/adapter runtime-adapter}))]
    (is (= :opsv-test-agent (get-in migrated [:phase :agent])))
    (is (identical? legacy-adapter
                    (get-in migrated [:execution/opts :opsv/adapter])))
    (is (identical? runtime-adapter
                    (get-in preferred [:execution/opts :opsv/adapter])))
    (is (not (contains? (:execution/input migrated) :opsv/adapter)))
    (is (not (contains? (:execution/input preferred) :opsv/adapter)))))

(deftest ^{:stratum 2} test-evidence-runtime-restores-from-durable-input
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

(deftest ^{:stratum 2} test-opsv-lifecycle-checkpoint
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [config (:workflow
                    (workflow/load-workflow
                     :opsv "1.0.0" {:skip-cache? true}))
            result (workflow/run-pipeline
                    config (workflow-input)
                    {:opsv/adapter (test-adapter)
                     :checkpoint/root checkpoint-root})
            checkpoint (workflow/load-checkpoint-data
                        (:execution/id result)
                        {:checkpoint/root checkpoint-root})
            checkpoint-input (get-in checkpoint
                                     [:machine-snapshot :execution/input])]
        (is (= :completed (:execution/status result)))
        (is (= (set (conj opsv/phase-keys :done))
               (set (keys (:execution/phase-results result)))))
        (is (map? (:opsv/evidence-assembly checkpoint-input)))
        (is (not (contains? checkpoint-input :opsv/adapter)))
        (is (not (contains? checkpoint-input
                            :opsv/evidence-assembly-store)))))))
