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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.protocol :as port]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private artifact-id
  #uuid "00000000-0000-0000-0000-000000000722")

(def ^{:stratum 0} ^:private fixture
  (-> "opsv/application-fixture.edn" io/resource slurp edn/read-string))

(def ^{:stratum 0} expected-pipeline
  (conj (mapv #(hash-map :phase %) opsv/phase-keys) {:phase :done}))

(defn- ^{:stratum 0} with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-opsv-lifecycle-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete ^java.io.File file))))))

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

(deftest ^{:stratum 2} opsv-run-emits-lifecycle-and-domain-events-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow-config (:workflow
                             (workflow/load-workflow
                              :opsv "1.0.0" {:skip-cache? true}))
            stream (event-stream/create-event-stream)
            result (workflow/run-pipeline
                    workflow-config
                    {:opsv/experiment-pack
                     (assoc (:experiment-pack fixture)
                            :experiment-pack/actuation-intent
                            :recommend-only)
                     :opsv/evidence-refs [artifact-id]
                     :opsv/risk-thresholds
                     {:medium 0.3 :high 0.6 :critical 0.85}
                     :opsv/metric-snapshot-artifact-refs [artifact-id]
                     :opsv/policy-diff-artifact-refs [artifact-id]}
                    {:event-stream stream
                     :opsv/adapter (test-adapter)
                     :checkpoint/root checkpoint-root})
            events (event-stream/get-events stream)
            lifecycle-events (filter #(contains? #{:workflow/phase-started
                                                   :workflow/phase-completed}
                                                 (:event/type %))
                                     events)
            domain-types (frequencies
                          (keep #(when (contains? #{:opsv.experiment/planned
                                                  :opsv.experiment/started
                                                  :opsv/load-step
                                                  :opsv.convergence/iteration
                                                  :opsv.policy/proposed
                                                  :opsv.verification/result
                                                  :opsv.actuation/emitted}
                                                (:event/type %))
                                   (:event/type %))
                                events))
            domain-events (filter #(contains? domain-types (:event/type %))
                                  events)
            evidence-id (:opsv/evidence-bundle-id (first domain-events))
            evidence-store (:opsv/evidence-assembly-store result)
            checkpoint (workflow/load-checkpoint-data
                        (:execution/id result)
                        {:checkpoint/root checkpoint-root})]
        (testing "the shared runner executes all registered phases"
          (is (= :completed (:execution/status result)))
          (is (= (set (conj opsv/phase-keys :done))
                 (set (keys (:execution/phase-results result)))))
          (doseq [phase-key opsv/phase-keys]
            (is (= 1 (count (filter #(and (= :workflow/phase-started
                                             (:event/type %))
                                         (= phase-key (:workflow/phase %)))
                                   lifecycle-events))))
            (is (= 1 (count (filter #(and (= :workflow/phase-completed
                                             (:event/type %))
                                         (= phase-key (:workflow/phase %)))
                                   lifecycle-events))))))
        (testing "successful boundaries emit the required N3 OPSV events"
          (is (= {:opsv.experiment/planned 1
                  :opsv.experiment/started 1
                  :opsv/load-step 2
                  :opsv.convergence/iteration 2
                  :opsv.policy/proposed 1
                  :opsv.verification/result 1
                  :opsv.actuation/emitted 1}
                 domain-types))
          (is (uuid? evidence-id))
          (is (every? #(= evidence-id (:opsv/evidence-bundle-id %))
                      domain-events))
          (let [assembly (evidence/get-opsv-assembly evidence-store evidence-id)]
            (is (= :assembling (:opsv.assembly/status assembly)))
            (is (= (set (map :event/id domain-events))
                   (:opsv/event-refs assembly)))
            (is (= assembly
                   (get-in checkpoint
                           [:machine-snapshot :execution/input
                            :opsv/evidence-assembly])))
            (is (not (contains?
                      (get-in checkpoint [:machine-snapshot :execution/input])
                      :opsv/evidence-assembly-store)))
            (is (not (contains?
                      (get-in checkpoint [:machine-snapshot :execution/input])
                      :opsv/adapter)))))
        (testing "the default posture remains side-effect free"
          (let [actuation (get-in result
                                  [:execution/phase-results :opsv/actuate
                                   :result :output :opsv/actuation-record])]
            (is (= :recommend-only (:effective-actuation-mode actuation)))
            (is (= [] (:governed-effects actuation)))))))))
