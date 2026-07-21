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

(ns ai.miniforge.workbench-orchestration-adapter.interface-test
  "Fixtures are NOT hand-rolled EntityTable shapes: every table is produced
   by folding a synthetic N2 §2.4 event stream through the real
   supervisory-state accumulator, and the legal transition map is the real
   one from the workflow component's interface."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.supervisory-state.interface :as supervisory-state]
   [ai.miniforge.workbench-orchestration-adapter.interface :as sut]
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

(def ^:private t0 #inst "2026-07-19T10:00:00Z")
(def ^:private t-end #inst "2026-07-19T12:00:00Z")

(def ^:private clean-run-id    #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private illegal-run-id  #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private dangling-run-id #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private gate-event-id   #uuid "00000000-0000-0000-0000-000000000010")
(def ^:private attention-id    #uuid "00000000-0000-0000-0000-000000000020")
(def ^:private decision-id     #uuid "00000000-0000-0000-0000-000000000030")
(def ^:private agent-id        #uuid "00000000-0000-0000-0000-000000000031")
(def ^:private intervention-id #uuid "00000000-0000-0000-0000-000000000040")

(def ^:private legal-phases
  [:spec :plan :implement :verify :review :release :observe :done])

(defn- started-event [run-id]
  {:event/type :workflow/started
   :workflow/id run-id
   :workflow/intent "fixture intent"
   :workflow/spec {:workflow/key "canonical-sdlc" :title "Fixture run"}
   :event/timestamp t0})

(defn- phase-events [run-id phases]
  (map (fn [phase]
         {:event/type :workflow/phase-started
          :workflow/id run-id
          :workflow/phase phase
          :event/timestamp t0})
       phases))

(defn- gate-event [event-type violations]
  {:event/type event-type
   :event/id gate-event-id
   :workflow/id clean-run-id
   :gate/id :verify-gate
   :gate/target-type :workflow-output
   :gate/packs ["core-security"]
   :gate/violations violations
   :event/timestamp t0})

(def ^:private critical-violation
  {:rule-id :no-secrets
   :severity :critical
   :category :security
   :message "secret leaked"
   :remediable? false})

(def ^:private supervisory-events
  [{:event/type :supervisory/attention-derived
    :supervisory/entity {:attention/id attention-id
                         :attention/severity :warning
                         :attention/source-type :workflow
                         :attention/source-id clean-run-id
                         :attention/summary "verify gate failed once"
                         :attention/derived-at t0
                         :attention/resolved? true}}
   {:event/type :control-plane/decision-created
    :cp/agent-id agent-id
    :cp/decision-id decision-id
    :cp/summary "Approve release"
    :workflow/id clean-run-id
    :event/timestamp t0}
   {:event/type :control-plane/decision-resolved
    :cp/decision-id decision-id
    :cp/resolution "approved"
    :event/timestamp t0}
   {:event/type :supervisory/intervention-requested
    :intervention/id intervention-id
    :intervention/type :pause-workflow
    :intervention/target-type :workflow-run
    :intervention/target-id clean-run-id
    :intervention/requested-by "operator"
    :intervention/request-source :cli
    :intervention/state :applied
    :event/timestamp t0}])

(defn- clean-run-events [gate-outcome-type]
  (concat [(started-event clean-run-id)]
          (phase-events clean-run-id legal-phases)
          [(gate-event gate-outcome-type [critical-violation])]
          supervisory-events
          [{:event/type :workflow/completed
            :workflow/id clean-run-id
            :event/timestamp t-end}]))

(def ^:private illegal-run-events
  (concat [(started-event illegal-run-id)]
          (phase-events illegal-run-id [:spec :implement])
          [{:event/type :workflow/completed
            :workflow/id illegal-run-id
            :event/timestamp t-end}]))

(def ^:private dangling-run-events
  (concat [(started-event dangling-run-id)]
          (phase-events dangling-run-id [:spec])))

(defn- table-for [events]
  (supervisory-state/apply-events supervisory-state/empty-table events))

(defn- opts-for [events run-id]
  {:experiment-id "miniforge.orchestration.fixture"
   :label "baseline"
   :transitions workflow/phase-transitions
   :phase-history (sut/phase-history events run-id)})

(defn- evaluation-by-id [snapshot state-var-id]
  (first (filter #(= state-var-id (:state_var_id %)) (:evaluations snapshot))))

;------------------------------------------------------------------------------ Tests

(deftest shipped-registry-is-contract-valid
  (is (sut/valid-registry?))
  (is (= 6 (count (:state_vars (sut/state-var-registry))))))

(deftest export-registry-rejects-missing-out
  (testing "a bb task called without :out fails loudly rather than writing to \"\""
    (is (thrown? clojure.lang.ExceptionInfo (sut/export-registry! {})))
    (is (thrown? clojure.lang.ExceptionInfo (sut/export-registry! {:out nil})))
    (is (thrown? clojure.lang.ExceptionInfo (sut/export-registry! {:out "  "})))))

(deftest export-registry-rejects-invalid-registry
  (testing "a missing or schema-invalid registry fails loudly before any
            write — a \"null\" or malformed fixture is never produced"
    (doseq [broken [nil {:not "a registry"}]]
      (with-redefs [sut/state-var-registry (fn [] broken)]
        (let [out (java.io.File/createTempFile "registry-export" ".json")]
          (.deleteOnExit out)
          (.delete out)
          (is (thrown? clojure.lang.ExceptionInfo
                       (sut/export-registry! {:out (.getPath out)}))
              (pr-str broken))
          (is (not (.exists out))
              "no fixture file may be written for an invalid registry"))))))

(deftest accumulator-projects-expected-table-shape
  (let [events (clean-run-events :gate/failed)
        table  (table-for events)
        run    (get-in table [:workflows clean-run-id])]
    (testing "the real accumulator produced the shapes the adapter reads"
      (is (= :completed (:workflow-run/status run)))
      (is (= :done (:workflow-run/current-phase run)))
      (is (= "canonical-sdlc" (:workflow-run/workflow-key run)))
      (is (false? (get-in table [:policy-evals gate-event-id :policy-eval/passed?])))
      (is (true? (get-in table [:attention attention-id :attention/resolved?])))
      (is (= :resolved (get-in table [:decisions decision-id :decision/status])))
      (is (= :applied (get-in table [:interventions intervention-id :intervention/state])))
      (is (= legal-phases (sut/phase-history events clean-run-id))))))

(deftest clean-resolved-run-passes-all-six-vars
  (let [events   (clean-run-events :gate/failed)
        table    (table-for events)
        result   (sut/project table clean-run-id (opts-for events clean-run-id))
        snapshot (:snapshot result)]
    (is (schema/succeeded? result))
    (is (sut/valid-snapshot? snapshot))
    (is (= 6 (count (:evaluations snapshot))))
    (is (every? #(= "pass" (:status %)) (:evaluations snapshot)))
    (is (every? #(seq (:evidence_refs %)) (:evaluations snapshot)))
    (is (= (str "wb-" clean-run-id) (:snapshot_id snapshot)))
    (is (= "2026-07-19T12:00:00Z" (:generated_at snapshot)))
    (is (= "miniforge-orchestration-state-vars"
           (get-in snapshot [:registry_ref :registry_id])))
    (testing "entities bag matches the contract fixture namespace and keys"
      (let [entity (get-in snapshot [:entities :miniforge.workflow_run])]
        (is (= (str clean-run-id) (:workflow/id entity)))
        (is (= "completed" (:workflow/status entity)))
        (is (= "done" (:workflow/current-phase entity)))))))

(deftest illegal-transition-fails-machine-authoritative
  (let [events   illegal-run-events
        table    (table-for events)
        result   (sut/project table illegal-run-id (opts-for events illegal-run-id))
        snapshot (:snapshot result)
        machine  (evaluation-by-id snapshot "miniforge.workflow.machine_authoritative")]
    (is (schema/succeeded? result))
    (is (sut/valid-snapshot? snapshot))
    (is (= "fail" (:status machine)))
    (is (false? (:value machine)))
    (is (= 0.0 (:score machine)))
    (is (= "blocks_transition" (:gate_effect machine)))
    (is (= 0.0 (get-in machine [:score_components :legal_transition_rate])))
    (is (= 0.0 (get-in machine [:score_components :terminal_phase_legal])))))

(deftest unblocked-critical-violation-fails-gate-var
  (let [events   (clean-run-events :gate/passed)
        table    (table-for events)
        snapshot (:snapshot (sut/project table clean-run-id
                                         (opts-for events clean-run-id)))
        gate-var (evaluation-by-id snapshot "miniforge.gate.critical_violations_block")]
    (is (true? (get-in table [:policy-evals gate-event-id :policy-eval/passed?])))
    (is (= "fail" (:status gate-var)))
    (is (false? (:value gate-var)))
    (is (= "blocks_transition" (:gate_effect gate-var)))
    (is (= 1.0 (get-in gate-var [:score_components :unblocked_evaluations])))))

(deftest unresolved-runs-are-excluded-at-the-boundary
  (let [events (concat (clean-run-events :gate/failed) dangling-run-events)
        table  (table-for events)]
    (testing "a dangling run cannot be projected"
      (let [result (sut/project table dangling-run-id
                                (opts-for events dangling-run-id))]
        (is (schema/failed? result))
        (is (= :running (:status result)))))
    (testing "resolved-runs excludes the dangling run"
      (is (= [clean-run-id]
             (mapv :workflow-run/id (sut/resolved-runs table)))))
    (testing "runs_resolved measures the boundary across the table"
      (let [snapshot (:snapshot (sut/project table clean-run-id
                                             (opts-for events clean-run-id)))
            resolved (evaluation-by-id snapshot "miniforge.workflow.runs_resolved")]
        (is (= "fail" (:status resolved)))
        (is (= 0.5 (:score resolved)))
        (is (= "marks_low_confidence" (:gate_effect resolved)))
        (is (= 1.0 (get-in resolved [:score_components :dangling_runs])))))))

(deftest verify-without-policy-evaluation-fails-and-empty-queues-not-applicable
  (let [events   (concat [(started-event illegal-run-id)]
                         (phase-events illegal-run-id legal-phases)
                         [{:event/type :workflow/completed
                           :workflow/id illegal-run-id
                           :event/timestamp t-end}])
        table    (table-for events)
        snapshot (:snapshot (sut/project table illegal-run-id
                                         (opts-for events illegal-run-id)))]
    (is (sut/valid-snapshot? snapshot))
    (let [recorded (evaluation-by-id snapshot "miniforge.policy.evaluations_recorded")]
      (is (= "fail" (:status recorded)))
      (is (= "marks_low_confidence" (:gate_effect recorded)))
      (is (= 1.0 (get-in recorded [:score_components :verify_traversed]))))
    (doseq [state-var-id ["miniforge.attention.items_actioned"
                          "miniforge.decision.queue_drained"]]
      (let [evaluation (evaluation-by-id snapshot state-var-id)]
        (is (= "not_applicable" (:status evaluation)) state-var-id)
        (is (= "none" (:gate_effect evaluation)) state-var-id)))))

(def ^:private failed-at-spec-events
  "A run that fails at :spec — lifecycle-terminal from any phase per N2
   §2.3, so it IS resolved and projectable, with a single observed phase
   and therefore zero transition pairs."
  (concat [(started-event illegal-run-id)]
          (phase-events illegal-run-id [:spec])
          [{:event/type :workflow/failed
            :workflow/id illegal-run-id
            :workflow/error "spec rejected"
            :event/timestamp t-end}]))

(defn- required-roles [state-var-id]
  (->> (:state_vars (sut/state-var-registry))
       (filter #(= state-var-id (:id %)))
       first
       :evidence_requirements
       :required_refs
       set))

(defn- evidence-requirement-violations
  "Mirror of the workbench kernel's evidence validator: every declared
   `required_refs` role must be matched by at least one evidence ref's
   `source_role`, and no evaluation may carry zero refs."
  [snapshot]
  (for [evaluation (:evaluations snapshot)
        :let [roles   (set (map :source_role (:evidence_refs evaluation)))
              missing (remove roles (required-roles (:state_var_id evaluation)))]
        :when (or (empty? (:evidence_refs evaluation)) (seq missing))]
    [(:state_var_id evaluation) (vec missing)]))

(deftest every-evaluation-satisfies-its-registry-evidence-requirements
  (testing "no evaluation may violate its own evidence_requirements — the
            adapter must never emit what the kernel's evidence validator
            would reject, in ANY reachable table shape"
    (doseq [[label events run-id]
            [["clean run"          (clean-run-events :gate/failed) clean-run-id]
             ["passed gate"        (clean-run-events :gate/passed) clean-run-id]
             ["illegal transition" illegal-run-events              illegal-run-id]
             ["failed at spec"     failed-at-spec-events           illegal-run-id]
             ["verify, no policy"  (concat [(started-event illegal-run-id)]
                                           (phase-events illegal-run-id legal-phases)
                                           [{:event/type :workflow/completed
                                             :workflow/id illegal-run-id
                                             :event/timestamp t-end}])
              illegal-run-id]]]
      (let [table    (table-for events)
            snapshot (:snapshot (sut/project table run-id (opts-for events run-id)))]
        (is (sut/valid-snapshot? snapshot) label)
        (is (empty? (evidence-requirement-violations snapshot))
            (str label ": " (pr-str (evidence-requirement-violations snapshot))))))))

(deftest resolved-run-without-transitions-still-carries-phase-history-evidence
  (testing "a run that fails at :spec has zero transition pairs, but
            machine_authoritative declares a workflow-phase-history
            required_ref — an honest absence ref satisfies it"
    (let [events   failed-at-spec-events
          table    (table-for events)
          run      (get-in table [:workflows illegal-run-id])
          result   (sut/project table illegal-run-id (opts-for events illegal-run-id))
          snapshot (:snapshot result)
          machine  (evaluation-by-id snapshot "miniforge.workflow.machine_authoritative")
          roles    (set (map :source_role (:evidence_refs machine)))]
      (is (= :failed (:workflow-run/status run)))
      (is (sut/resolved? run) "failed is lifecycle-terminal per N2 §2.3")
      (is (schema/succeeded? result))
      (is (contains? roles "workflow-phase-history"))
      (is (contains? roles "workflow-run"))
      (is (= 0.0 (get-in machine [:score_components :observed_transitions])))
      (testing "a failed run is not held to the :done terminal projection"
        (is (= "pass" (:status machine)))))))

(deftest projection-requires-variant-and-transitions
  (let [events (clean-run-events :gate/failed)
        table  (table-for events)
        opts   (opts-for events clean-run-id)]
    (is (schema/failed? (sut/project table clean-run-id
                                     (dissoc opts :experiment-id))))
    (is (schema/failed? (sut/project table clean-run-id
                                     (dissoc opts :transitions))))
    (is (schema/failed? (sut/project table (random-uuid) opts)))))
