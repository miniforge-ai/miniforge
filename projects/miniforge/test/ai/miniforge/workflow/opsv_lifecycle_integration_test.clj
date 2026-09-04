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
   [ai.miniforge.workflow.isolation-support :as isolation]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.interface :as opsv]
   [ai.miniforge.workflow.checkpoint-root-support :as checkpoint-root-support]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.workflow.opsv-lifecycle-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-opsv-scenario
  [checkpoint-root]
  (let [workflow-config (:workflow
                         (workflow/load-workflow
                          :opsv "1.0.0" {:skip-cache? true}))
        stream (event-stream/create-event-stream)
        result (workflow/run-pipeline workflow-config
                                      (support/workflow-input)
                                      (support/workflow-options
                                       stream checkpoint-root))
        events (event-stream/get-events stream)
        lifecycle-events (filter
                          (partial support/event-type-in?
                                   support/lifecycle-event-types)
                          events)
        domain-events (filter support/opsv-domain-event? events)
        evidence-id (:opsv/evidence-bundle-id (first domain-events))]
    {:result result
     :lifecycle-events lifecycle-events
     :domain-events domain-events
     :domain-type-counts (frequencies (map :event/type domain-events))
     :planned-event (support/event-of-type
                     domain-events :opsv.experiment/planned)
     :evidence-id evidence-id
     :evidence-store (:opsv/evidence-assembly-store result)
     :checkpoint (workflow/load-checkpoint-data
                  (:execution/id result)
                  {:checkpoint/root checkpoint-root})}))

(deftest ^{:stratum 0} test-opsv-workflow-loads-exact-versioned-pipeline
  (let [loaded (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true})]
    (is (= :resource (:source loaded)))
    (is (true? (get-in loaded [:validation :valid?])))
    (is (= support/expected-pipeline
           (get-in loaded [:workflow :workflow/pipeline])))))

(deftest ^{:stratum 0} test-opsv-interceptor-isolates-runtime-adapter
  (let [legacy-adapter (support/test-adapter)
        runtime-adapter (support/test-adapter)
        interceptor (phase/get-phase-interceptor
                     {:phase :opsv/discover :agent :opsv-test-agent})
        enter (:enter interceptor)
        migrated (enter (support/legacy-adapter-context legacy-adapter))
        preferred (enter (assoc (support/legacy-adapter-context legacy-adapter)
                                :execution/opts
                                {:opsv/adapter runtime-adapter}))]
    (is (= :opsv-test-agent (get-in migrated [:phase :agent])))
    (is (identical? legacy-adapter
                    (get-in migrated [:execution/opts :opsv/adapter])))
    (is (identical? runtime-adapter
                    (get-in preferred [:execution/opts :opsv/adapter])))
    (is (not (contains? (:execution/input migrated) :opsv/adapter)))
    (is (not (contains? (:execution/input preferred) :opsv/adapter)))))

(deftest ^{:stratum 0} test-opsv-evidence-runtime-restores-from-durable-input
  (let [workflow-id (random-uuid)
        runtime-adapter (support/test-adapter)
        context (-> (support/legacy-adapter-context runtime-adapter)
                    (assoc :execution/id workflow-id)
                    (update :execution/input dissoc :opsv/adapter)
                    (assoc :execution/opts {:opsv/adapter runtime-adapter}))
        interceptor (phase/get-phase-interceptor {:phase :opsv/discover})
        entered ((:enter interceptor) context)
        checkpointed ((:leave interceptor) entered)
        resumed (dissoc checkpointed :opsv/evidence-assembly-store)
        restored ((:enter interceptor) resumed)
        bundle-id (get-in restored
                          [:execution/input :opsv/evidence-bundle-id])]
    (is (= (get-in checkpointed [:execution/input :opsv/evidence-assembly])
           (evidence/get-opsv-assembly
            (:opsv/evidence-assembly-store restored) bundle-id)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-opsv-run-emits-lifecycle-and-domain-events
  (let [{:keys [result lifecycle-events domain-events domain-type-counts
                planned-event evidence-id evidence-store checkpoint]}
        (checkpoint-root-support/call-with-temp-checkpoint-root run-opsv-scenario)
        assembly (when (and evidence-store evidence-id)
                   (evidence/get-opsv-assembly evidence-store evidence-id))
        checkpoint-input (get-in checkpoint [:machine-snapshot :execution/input])
        discovery (support/phase-output result :opsv/discover)
        policy (:opsv/operational-policy
                (support/phase-output result :opsv/synthesize))
        actuation (:opsv/actuation-record
                   (support/phase-output result :opsv/actuate))]
    (testing "the shared runner executes all registered phases"
      (is (= :completed (:execution/status result)))
      (is (= (set (conj opsv/phase-keys :done))
             (set (keys (:execution/phase-results result)))))
      (doseq [phase-key opsv/phase-keys]
        (is (= 1 (support/lifecycle-event-count
                  lifecycle-events phase-key :workflow/phase-started)))
        (is (= 1 (support/lifecycle-event-count
                  lifecycle-events phase-key :workflow/phase-completed)))))
    (testing "successful boundaries emit the required N3 OPSV events"
      (is (= support/expected-domain-type-counts domain-type-counts))
      (is (every? support/valid-domain-event? domain-events))
      (is (= (:opsv/risk-result (support/phase-output result :opsv/plan))
             (:opsv/risk-score planned-event)))
      (is (number? (get-in planned-event [:opsv/risk-score :score])))
      (is (uuid? evidence-id))
      (is (every? #(= evidence-id (:opsv/evidence-bundle-id %)) domain-events))
      (is (= :assembling (:opsv.assembly/status assembly)))
      (is (= (set (map :event/id domain-events)) (:opsv/event-refs assembly)))
      (is (= assembly (:opsv/evidence-assembly checkpoint-input)))
      (is (not (contains? checkpoint-input :opsv/evidence-assembly-store)))
      (is (not (contains? checkpoint-input :opsv/adapter))))
    (testing "the staging adapter yields interoperable scaling recommendations"
      (is (= #{:cpu :backlog}
             (set (map :driver (:opsv/candidate-drivers discovery)))))
      (is (= ["staging"] (:operational-policy/target-envs policy)))
      (is (= {:api-version "autoscaling/v2" :metric :cpu}
             (select-keys
              (get-in policy [:operational-policy/scaling :hpa])
              [:api-version :metric])))
      (is (= :backlog
             (get-in policy [:operational-policy/scaling :keda :trigger]))))
    (testing "the default posture remains side-effect free"
      (is (= :recommend-only (:effective-actuation-mode actuation)))
      (is (= [] (:governed-effects actuation))))))

;; Every pipeline this namespace runs acquires its worktree from a
;; throwaway host repository and checkpoints into a throwaway root — never
;; the checkout the test JVM was launched in, never `~/.miniforge`.
(clojure.test/use-fixtures :once isolation/with-isolated-host)
