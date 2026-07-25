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
(ns ai.miniforge.operator.mechanism-test
  "Unit coverage for the pure halves of the D-3b mechanisms: reading the
   requested phase off the wire, deriving a run's real phase history,
   shaping the resume plan, and turning an evaluation verdict into the
   gate event `supervisory-state` materializes. The lifecycle wiring
   these feed is covered in `application-test`."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.mechanism :as mechanism]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Requested phase
(deftest ^{:stratum 0} requested-phase-reads-both-detail-shapes
  (testing "the wire's string key (transit leaves details keys untagged)"
    (is (= :implement
           (mechanism/requested-phase
            {:intervention/details {"phase" "implement"}}))))

  (testing "the in-process keyword shape"
    (is (= :implement
           (mechanism/requested-phase
            {:intervention/details {:phase :implement}}))))

  (testing "absent, blank, and unusable values yield nil, not a phantom phase"
    (doseq [details [nil {} {"phase" ""} {"phase" "   "} {"phase" 7}]]
      (is (nil? (mechanism/requested-phase {:intervention/details details}))
          (str "details " (pr-str details)))))

  (testing "surrounding whitespace is trimmed so it resolves to the real phase"
    (doseq [padded ["implement " " implement" "  implement  "]]
      (let [resolved (mechanism/requested-phase
                      {:intervention/details {"phase" padded}})]
        (is (= :implement resolved)
            (str "padded " (pr-str padded)))
        (is (not (str/includes? (name resolved) " "))
            "the resolved phase keyword carries no stray whitespace")))))

;------------------------------------------------------------------------------ Phase history
(deftest ^{:stratum 0} known-phases-covers-completed-attempted-and-current
  (let [context {:completed-phases [:explore :plan]
                 :phase-results {:explore {} :plan {} :implement {}}
                 :machine-snapshot {:execution/current-phase :verify}}]
    (is (= #{:explore :plan :implement :verify}
           (mechanism/known-phases context))
        "a failed phase has a result without being completed — it still ran")))

(deftest ^{:stratum 0} known-phases-tolerates-a-bare-context
  (is (= #{} (mechanism/known-phases {}))))

(deftest ^{:stratum 0} phases-before-truncates-at-the-target
  (is (= [:explore] (mechanism/phases-before [:explore :plan :implement] :plan)))
  (is (= [] (mechanism/phases-before [:explore :plan] :explore)))
  (is (= [:explore :plan] (mechanism/phases-before [:explore :plan] :nowhere))
      "a phase outside the list truncates nothing — the caller validates first"))

;------------------------------------------------------------------------------ Resume plan
(def ^{:stratum 0} ^:private context
  {:completed-phases [:explore :plan]
   :phase-results {:explore {:outcome :success}}
   :machine-snapshot {:execution/current-phase :implement}
   :workspace-checkpoint {:branch "feat/x"}
   :completed-dag-tasks #{"task-1"}
   :completed-dag-artifacts [{:path "a.clj"}]})

(def ^{:stratum 0} ^:private workflow-identity
  {:workflow-type :canonical-sdlc :workflow-version "1.0.0"})

;------------------------------------------------------------------------------ Launcher result
(deftest ^{:stratum 0} launched-run-id-accepts-only-a-reported-run
  (testing "a scalar run id (string or uuid) is the report the lifecycle verifies"
    (is (= "run-1" (mechanism/launched-run-id {:resume/run-id "run-1"})))
    (let [u (random-uuid)]
      (is (= u (mechanism/launched-run-id {:resume/run-id u})))))
  (testing "no report, an anomaly, or a non-scalar run id all read as nil"
    (doseq [result [nil {} "run-1"
                    (anomaly/anomaly :fault "launcher blew up" {})
                    {:resume/run-id {:nested "map"}}
                    {:resume/run-id [:a :collection]}
                    {:resume/run-id 42}]]
      (is (nil? (mechanism/launched-run-id result))
          (str "result " (pr-str result))))))

;------------------------------------------------------------------------------ Policy re-evaluation
(def ^{:stratum 0} ^:private re-evaluate-intervention
  {:intervention/id (random-uuid)
   :intervention/type :re-evaluate
   :intervention/target-type :pr
   :intervention/target-id "golden/synthetic#1"
   :intervention/requested-by "op@example.invalid"
   :intervention/details {"packs" ["golden-pack"]}})

(defn- ^{:stratum 0} memory-stream [] (es/create-event-stream {:sinks []}))

(defn- ^{:stratum 0} materialized-evals
  [stream]
  (vals (:policy-evals (supervisory/apply-events supervisory/empty-table
                                                 (es/get-events stream)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} plain-retry-keeps-the-fsm-snapshot
  (let [plan (mechanism/resume-plan {:intervention/target-id "wf-1"
                                     :intervention/requested-by "op@example.invalid"}
                                    context workflow-identity nil)]
    (is (= [:explore :plan] (:resume/completed-phases plan)))
    (is (= {:execution/current-phase :implement} (:resume/machine-snapshot plan)))
    (is (not (contains? plan :resume/from-phase)))
    (is (= "wf-1" (:resume/workflow-id plan)))
    (is (= :canonical-sdlc (:resume/workflow-type plan)))
    (is (= #{"task-1"} (:resume/completed-dag-tasks plan)))))

(deftest ^{:stratum 1} retry-from-phase-rewinds-and-drops-the-snapshot
  (let [plan (mechanism/resume-plan {:intervention/target-id "wf-1"} context
                                    workflow-identity :plan)]
    (is (= :plan (:resume/from-phase plan)))
    (is (= [:explore] (:resume/completed-phases plan)))
    (is (nil? (:resume/machine-snapshot plan))
        "restoring a snapshot parked past the target would ignore the rewind")))

(deftest ^{:stratum 1} evaluation-request-carries-target-and-requester
  (let [request (mechanism/evaluation-request re-evaluate-intervention)]
    (is (= "golden/synthetic#1" (:policy/target-id request)))
    (is (= :pr (:policy/target-type request)))
    (is (= "op@example.invalid" (:policy/requested-by request)))
    (is (= {"packs" ["golden-pack"]} (:policy/details request))
        "scoping details pass through verbatim")))

(deftest ^{:stratum 1} gate-event-type-follows-the-verdict
  (let [stream (memory-stream)
        passed (mechanism/evaluation-gate-event stream re-evaluate-intervention
                                                {:evaluation/passed? true})
        failed (mechanism/evaluation-gate-event stream re-evaluate-intervention
                                                {:evaluation/passed? false})]
    (is (= :gate/passed (:event/type passed)))
    (is (= :gate/failed (:event/type failed)))
    (is (= :pr (:gate/target-type passed)))
    (is (= "golden/synthetic#1" (:gate/target-id passed)))
    (is (= mechanism/re-evaluation-gate-id (:gate/id passed)))))

(deftest ^{:stratum 1} gate-event-coerces-pack-ids-to-strings
  (let [event (mechanism/evaluation-gate-event
               (memory-stream) re-evaluate-intervention
               {:evaluation/passed? true
                :evaluation/packs-applied [:golden-pack "literal-pack"]})]
    (is (= ["golden-pack" "literal-pack"] (:gate/packs event))
        "PolicyEvaluation requires string pack ids; evaluators use either")))

(deftest ^{:stratum 1} recording-an-evaluation-reads-the-new-record-back
  (let [stream (memory-stream)
        readback (mechanism/record-policy-evaluation!
                  stream re-evaluate-intervention
                  {:evaluation/passed? false
                   :evaluation/packs-applied ["golden-pack"]
                   :evaluation/violations [{:rule-id :golden/rule-001
                                            :severity :medium
                                            :message "synthetic"}]})
        evals (materialized-evals stream)]
    (is (true? (:observed readback)))
    (is (= (:expected readback) (:observed readback)))
    (is (= 1 (count evals)))
    (is (= (:policy-eval/id readback) (:policy-eval/id (first evals)))
        "the readback names the record the accumulator materialized")
    (is (false? (:policy-eval/passed? (first evals))))))
