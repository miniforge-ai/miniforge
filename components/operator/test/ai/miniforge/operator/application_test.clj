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

(ns ai.miniforge.operator.application-test
  "Intervention application layer (Phase D D-3) tests, including the
   Phase D verification-bar sequence: injected request file → consumer
   → applier → control-state flip → observable
   proposed→approved→dispatched→applied→verified event trail, and the
   no-live-runner failure path.

   D-3b adds the two mechanisms that reach past control-state: the
   resume launcher behind `:retry` / `:retry-from-phase` and the policy
   evaluator behind `:re-evaluate`. Both are verified by readback —
   reconstructing the launched run from its own event history, and
   finding a PolicyEvaluation in the materialized entity table that was
   not there before the publish."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.application :as application]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.reliability.interface :as reliability]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Helpers

(def ^:const golden-pause-target-id
  "The workflow target id stamped by the Rust golden-fixture generator."
  "00000000-0000-4000-8000-0000000000aa")

(defn- find-workspace-root
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops 8))
      (throw (ex-info "workspace.edn not found" {}))
      :else (recur (.getParentFile dir) (inc hops)))))

(defn- temp-events-dir
  ^java.io.File []
  (.toFile (Files/createTempDirectory "operator-application-test"
                                      (make-array FileAttribute 0))))

(defn- stage-golden!
  [events-dir fixture-name]
  (let [target (io/file events-dir "operator" fixture-name)]
    (io/make-parents target)
    (spit target
          (slurp (io/file (find-workspace-root)
                          "contracts" "operator-events" "golden"
                          fixture-name)
                 :encoding "UTF-8")
          :encoding "UTF-8")))

(defn- memory-stream []
  (es/create-event-stream {:sinks []}))

(defn- approved
  "Build an :approved intervention of `type` against `target-id`."
  [type target-id]
  (:intervention
   (intervention/approve
    (intervention/create-intervention
     {:intervention/type type
      :intervention/target-id target-id
      :intervention/requested-by "test@example.invalid"
      :intervention/request-source :tui}))))

(defn- state-trail
  [stream]
  (->> (es/get-events stream)
       (filter #(= consumer/state-changed-event-type (:event/type %)))
       (mapv :intervention/state)))

(defn- events-of-type
  [stream event-type]
  (filterv #(= event-type (:event/type %)) (es/get-events stream)))

(defn- failure-code
  [interv]
  (get-in interv [:intervention/details :failure/code]))

(defn- with-runner
  "Register a control-state for a fresh workflow id, run `f`, always
   deregister. Returns [workflow-id control-state result]."
  [f]
  (let [wid (str (random-uuid))
        cs (es/create-control-state)]
    (application/register-runner! wid {:control-state cs})
    (try
      [wid cs (f wid cs)]
      (finally
        (application/deregister-runner! wid)))))

(defn- with-degradation-manager
  [manager f]
  (let [manager-state (var-get #'application/process-degradation-manager)
        original @manager-state]
    (reset! manager-state manager)
    (try
      (f)
      (finally
        (reset! manager-state original)))))

(defn- with-mechanism-handle
  "Bind one of the process-scoped mechanism handles for the duration of
   `f`, restoring whatever was there before."
  [handle-var handle f]
  (let [handle-state (var-get handle-var)
        original @handle-state]
    (reset! handle-state handle)
    (try
      (f)
      (finally
        (reset! handle-state original)))))

(defn- with-resume-launcher
  [launcher f]
  (with-mechanism-handle #'application/process-resume-launcher launcher f))

(defn- with-policy-evaluator
  [evaluate f]
  (with-mechanism-handle #'application/process-policy-evaluator evaluate f))

;; -- Staged workflow history -- what a resume reconstructs from ------------

(defn- workflow-event
  [workflow-id event-type attrs]
  (merge {:event/id (random-uuid)
          :event/type event-type
          :event/timestamp (java.util.Date.)
          :event/sequence-number 0
          :workflow/id workflow-id}
         attrs))

(defn- stage-workflow-history!
  "Write `events` into the live layout `read-workflow-events-by-id`
   probes, through the production transit encoder. Returns workflow-id."
  [events-dir workflow-id events]
  (let [dir (io/file events-dir "live" (str workflow-id))]
    (.mkdirs dir)
    (doseq [[i event] (map-indexed vector events)]
      (spit (io/file dir (format "%03d-event.json" i))
            (es/serialize-event event)
            :encoding "UTF-8"))
    workflow-id))

(defn- stage-two-phase-run!
  "A run that completed `:explore` then `:plan` and stopped. Its phase
   history is exactly #{:explore :plan} — anything else a
   `retry-from-phase` names must be rejected."
  [events-dir workflow-id]
  (stage-workflow-history!
   events-dir workflow-id
   [(workflow-event workflow-id :workflow/started
                    {:workflow/spec {:workflow-type :canonical-sdlc
                                     :version "1.0.0"}})
    (workflow-event workflow-id :workflow/phase-completed
                    {:workflow/phase :explore :phase/outcome :success})
    (workflow-event workflow-id :workflow/phase-completed
                    {:workflow/phase :plan :phase/outcome :success})]))

(defn- recording-launcher
  "A resume launcher that records the plan it was handed and reports
   `run-id` as the run it started."
  [captured run-id]
  {:launch! (fn [plan] (reset! captured plan) {:resume/run-id run-id})})

(defn- policy-evals
  "PolicyEvaluation entities materialized from `stream`'s events through
   the canonical supervisory accumulator."
  [stream]
  (vals (:policy-evals (supervisory/apply-events supervisory/empty-table
                                                 (es/get-events stream)))))

;------------------------------------------------------------------------------ Phase D verification bar — request file → paused workflow

(deftest injected-request-file-pauses-the-registered-runner
  (let [events-dir (temp-events-dir)
        stream (memory-stream)
        cs (es/create-control-state)]
    (application/register-runner! golden-pause-target-id {:control-state cs})
    (try
      (stage-golden! events-dir "pause.transit.json")
      (is (= {:routed 1 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir
                                      :stream stream
                                      :apply! application/apply-intervention!})))
      (is (true? (es/paused? cs)) "the runner's control-state actually flipped")
      (is (= [:approved :dispatched :applied :verified] (state-trail stream))
          "the full lifecycle trail is observable on the stream")
      (finally
        (application/deregister-runner! golden-pause-target-id)))))

(deftest no-live-runner-fails-typed
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-golden! events-dir "pause.transit.json")
    ;; Nothing registered for the fixture's target id.
    (consumer/consume-pass! {:events-dir events-dir
                             :stream stream
                             :apply! application/apply-intervention!})
    (is (= [:approved :dispatched :failed] (state-trail stream)))
    (let [failed (->> (es/get-events stream)
                      (filter #(= :failed (:intervention/state %)))
                      first)]
      (is (string? (:intervention/reason failed))
          "the schema-facing reason is localized text")
      (is (= :no-live-runner (failure-code failed))
          "the machine-readable failure code remains typed"))))

;------------------------------------------------------------------------------ Control-state verbs verify by readback

(deftest resume-flips-paused-off
  (with-runner
    (fn [wid cs]
      (es/pause! cs)
      (let [stream (memory-stream)
            result (application/apply-intervention! stream (approved :resume wid))]
        (is (false? (es/paused? cs)))
        (is (= :verified (:intervention/state result)))))))

(deftest cancel-sets-stopped
  (with-runner
    (fn [wid cs]
      (let [stream (memory-stream)
            result (application/apply-intervention! stream (approved :cancel wid))]
        (is (true? (es/cancelled? cs)))
        (is (= :verified (:intervention/state result)))))))

(deftest register-runner-rejects-invalid-control-state
  (testing "runner wiring fails early instead of becoming an application error"
    (doseq [handles [{} {:control-state :not-an-atom} :not-a-map]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires an atom :control-state"
           (application/register-runner! (random-uuid) handles))))))

;------------------------------------------------------------------------------ No-effect and unwired verbs

(deftest acknowledge-verifies-without-a-runner
  (let [stream (memory-stream)
        result (application/apply-intervention!
                stream (approved :acknowledge (str (random-uuid))))]
    (is (= :verified (:intervention/state result)))
    (is (= [:dispatched :applied :verified] (state-trail stream)))))

(deftest verbs-without-a-mechanism-fail-not-implemented
  (testing "waive has no Waiver store to write to; request-replan is D-7"
    (doseq [[verb target-id] [[:waive (str (random-uuid))]
                              [:request-replan (str (random-uuid))]]]
      (let [stream (memory-stream)
            result (application/apply-intervention! stream
                                                    (approved verb target-id))]
        (is (= :failed (:intervention/state result))
            (str verb " must fail rather than park"))
        (is (= :not-implemented (failure-code result)))))))

;------------------------------------------------------------------------------ Retry — resume machinery (D-3b)

(deftest retry-without-a-launcher-fails-typed
  (with-resume-launcher
    nil
    (fn []
      (let [stream (memory-stream)
            result (application/apply-intervention!
                    stream (approved :retry (str (random-uuid))))]
        (is (= :failed (:intervention/state result)))
        (is (= :no-resume-launcher (failure-code result)))))))

(deftest retry-from-phase-rewinds-and-verifies-by-readback
  (let [events-dir (temp-events-dir)
        workflow-id (random-uuid)
        resumed-id (random-uuid)
        captured (atom nil)]
    (stage-two-phase-run! events-dir workflow-id)
    ;; The launcher's run is observable in the event history the resume
    ;; machinery itself reads — that is what `verified` asserts.
    (stage-two-phase-run! events-dir resumed-id)
    (with-resume-launcher
      (assoc (recording-launcher captured resumed-id) :events-dir events-dir)
      (fn []
        (let [stream (memory-stream)
              interv (assoc (approved :retry-from-phase (str workflow-id))
                            :intervention/details {"phase" "plan"})
              result (application/apply-intervention! stream interv)]
          (is (= :verified (:intervention/state result)))
          (is (= [:dispatched :applied :verified] (state-trail stream)))
          (is (= :plan (:resume/from-phase @captured))
              "the wire's string-keyed `phase` detail resolves to a phase keyword")
          (is (= [:explore] (:resume/completed-phases @captured))
              "rewinding to :plan re-runs :plan and everything after it")
          (is (nil? (:resume/machine-snapshot @captured))
              "a rewind must not restore an FSM snapshot parked past the target")
          (is (= :canonical-sdlc (:resume/workflow-type @captured))))))))


(deftest safe-mode-without-manager-fails-typed
  (with-degradation-manager
    nil
    (fn []
      (let [stream (memory-stream)
            result (application/apply-intervention!
                    stream (approved :force-safe-mode "degradation"))]
        (is (= :failed (:intervention/state result)))
        (is (= :no-degradation-manager (failure-code result)))))))

(deftest safe-mode-verifies-manual-entry-and-exit-by-readback
  (let [stream (memory-stream)
        manager (reliability/create-degradation-manager stream)
        target-id "degradation"]
    (with-degradation-manager
      manager
      (fn []
        (let [entered (application/apply-intervention!
                       stream (approved :force-safe-mode target-id))
              safe-mode-events (events-of-type stream :safe-mode/entered)]
          (is (= :verified (:intervention/state entered)))
          (is (= :safe-mode (reliability/degradation-mode manager)))
          (is (= :manual (:safe-mode/trigger (first safe-mode-events))))
          (let [exited (application/apply-intervention!
                        stream (approved :exit-safe-mode target-id))]
            (is (= :verified (:intervention/state exited)))
            (is (= :nominal (reliability/degradation-mode manager)))
            (is (= {:verb :exit-safe-mode
                    :observed :nominal
                    :expected :nominal}
                   (:intervention/outcome exited)))))))))

(deftest injected-safe-mode-file-uses-the-process-degradation-manager
  (let [events-dir (temp-events-dir)
        stream (memory-stream)
        manager (reliability/create-degradation-manager stream)]
    (with-degradation-manager
      manager
      (fn []
        (stage-golden! events-dir "force-safe-mode.transit.json")
        (is (= {:routed 1 :skipped 0 :anomalies 0}
               (consumer/consume-pass! {:events-dir events-dir
                                        :stream stream
                                        :apply! application/apply-intervention!})))
        (is (= :safe-mode (reliability/degradation-mode manager)))
        (is (= :manual
               (:safe-mode/trigger
                (first (events-of-type stream :safe-mode/entered)))))
        (is (= [:approved :dispatched :applied :verified]
               (state-trail stream)))))))

(deftest retry-keeps-the-fsm-snapshot-and-the-full-completed-set
  (let [events-dir (temp-events-dir)
        workflow-id (random-uuid)
        captured (atom nil)]
    (stage-two-phase-run! events-dir workflow-id)
    (with-resume-launcher
      (assoc (recording-launcher captured workflow-id) :events-dir events-dir)
      (fn []
        (let [stream (memory-stream)
              result (application/apply-intervention!
                      stream (approved :retry (str workflow-id)))]
          (is (= :verified (:intervention/state result)))
          (is (nil? (:resume/from-phase @captured)))
          (is (= [:explore :plan] (:resume/completed-phases @captured))))))))

(deftest retry-without-reconstructable-history-fails-typed
  (let [events-dir (temp-events-dir)]
    (with-resume-launcher
      {:launch! (fn [_plan] {:resume/run-id (random-uuid)})
       :events-dir events-dir}
      (fn []
        (let [stream (memory-stream)
              result (application/apply-intervention!
                      stream (approved :retry (str (random-uuid))))]
          (is (= :failed (:intervention/state result)))
          (is (= :no-resume-context (failure-code result))
              "a target with no events must not be guessed into a plan"))))))

(deftest retry-from-phase-without-a-phase-detail-fails-typed
  (let [events-dir (temp-events-dir)
        workflow-id (random-uuid)]
    (stage-two-phase-run! events-dir workflow-id)
    (with-resume-launcher
      {:launch! (fn [_plan] {:resume/run-id (random-uuid)})
       :events-dir events-dir}
      (fn []
        (let [stream (memory-stream)
              result (application/apply-intervention!
                      stream (approved :retry-from-phase (str workflow-id)))]
          (is (= :failed (:intervention/state result)))
          (is (= :missing-phase (failure-code result))))))))

(deftest retry-from-phase-rejects-a-phase-the-run-never-reached
  (let [events-dir (temp-events-dir)
        workflow-id (random-uuid)
        launched (atom false)]
    (stage-two-phase-run! events-dir workflow-id)
    (with-resume-launcher
      {:launch! (fn [_plan] (reset! launched true) {:resume/run-id (random-uuid)})
       :events-dir events-dir}
      (fn []
        (let [stream (memory-stream)
              interv (assoc (approved :retry-from-phase (str workflow-id))
                            :intervention/details {"phase" "implement"})
              result (application/apply-intervention! stream interv)]
          (is (= :failed (:intervention/state result)))
          (is (= :unknown-phase (failure-code result)))
          (is (false? @launched)
              "an unvalidated phase must never reach the launcher"))))))

(deftest retry-launcher-reporting-no-run-fails-typed
  (let [events-dir (temp-events-dir)
        workflow-id (random-uuid)]
    (stage-two-phase-run! events-dir workflow-id)
    (with-resume-launcher
      {:launch! (fn [_plan] nil) :events-dir events-dir}
      (fn []
        (let [stream (memory-stream)
              result (application/apply-intervention!
                      stream (approved :retry (str workflow-id)))]
          (is (= :failed (:intervention/state result)))
          (is (= :resume-not-dispatched (failure-code result))))))))

(deftest retry-verification-is-a-readback-not-the-launchers-word
  (testing "a launcher reporting a run that never started fails the readback"
    (let [events-dir (temp-events-dir)
          workflow-id (random-uuid)]
      (stage-two-phase-run! events-dir workflow-id)
      (with-resume-launcher
        {:launch! (fn [_plan] {:resume/run-id (random-uuid)})
         :events-dir events-dir}
        (fn []
          (let [stream (memory-stream)
                result (application/apply-intervention!
                        stream (approved :retry (str workflow-id)))]
            (is (= :failed (:intervention/state result)))
            (is (= :resume-readback-mismatch (failure-code result)))
            (is (= [:dispatched :applied :failed] (state-trail stream))
                "the failure lands after :applied — the dispatch did happen")))))))

(deftest a-throwing-launcher-becomes-a-failed-intervention
  (testing "a mechanism that blows up must not abort the consumer's pass"
    (let [events-dir (temp-events-dir)
          workflow-id (random-uuid)]
      (stage-two-phase-run! events-dir workflow-id)
      (with-resume-launcher
        {:launch! (fn [_plan] (throw (ex-info "launcher exploded" {})))
         :events-dir events-dir}
        (fn []
          (let [stream (memory-stream)
                result (application/apply-intervention!
                        stream (approved :retry (str workflow-id)))]
            (is (= :failed (:intervention/state result)))
            (is (= :application-error (failure-code result)))))))))

(deftest injected-retry-from-phase-file-drives-the-resume-launcher
  (testing "the Rust-produced golden fixture reaches the resume mechanism"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)
          resumed-id (random-uuid)
          captured (atom nil)]
      (stage-golden! events-dir "retry-from-phase.transit.json")
      (stage-workflow-history!
       events-dir golden-pause-target-id
       [(workflow-event (parse-uuid golden-pause-target-id) :workflow/started
                        {:workflow/spec {:workflow-type :canonical-sdlc}})
        (workflow-event (parse-uuid golden-pause-target-id) :workflow/phase-completed
                        {:workflow/phase :explore :phase/outcome :success})
        (workflow-event (parse-uuid golden-pause-target-id) :workflow/phase-completed
                        {:workflow/phase :implement :phase/outcome :failure})])
      (stage-two-phase-run! events-dir resumed-id)
      (with-resume-launcher
        (assoc (recording-launcher captured resumed-id) :events-dir events-dir)
        (fn []
          (is (= {:routed 1 :skipped 0 :anomalies 0}
                 (consumer/consume-pass! {:events-dir events-dir
                                          :stream stream
                                          :apply! application/apply-intervention!})))
          (is (= :implement (:resume/from-phase @captured))
              "the fixture's `phase` detail survives the wire round-trip")
          (is (= [:approved :dispatched :applied :verified]
                 (state-trail stream))))))))

(deftest resume-launcher-registration-rejects-unusable-handles
  (testing "wiring fails early instead of becoming an application error"
    (doseq [handles [{} {:launch! "not-a-fn"} :not-a-map]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires a :launch! function"
           (application/register-resume-launcher! handles))))))

(def ^:const golden-pr-target-id
  "PR target the re-evaluation tests score."
  "golden/synthetic#1")

(defn- failing-evaluation []
  {:evaluation/passed? false
   :evaluation/packs-applied [:golden-pack]
   :evaluation/violations [{:rule-id :golden/rule-001
                            :severity :medium
                            :category :process
                            :message "Golden synthetic violation"}]})

(deftest re-evaluate-without-an-evaluator-fails-typed
  (with-policy-evaluator
    nil
    (fn []
      (let [stream (memory-stream)
            result (application/apply-intervention!
                    stream (approved :re-evaluate golden-pr-target-id))]
        (is (= :failed (:intervention/state result)))
        (is (= :no-policy-evaluator (failure-code result))
            "no verdict is invented when nothing can compute one")
        (is (empty? (events-of-type stream :gate/failed))
            "and nothing is published on the target's behalf")))))

(deftest re-evaluate-writes-a-new-policy-evaluation
  (let [captured (atom nil)]
    (with-policy-evaluator
      (fn [request] (reset! captured request) (failing-evaluation))
      (fn []
        (let [stream (memory-stream)
              result (application/apply-intervention!
                      stream (approved :re-evaluate golden-pr-target-id))
              evals (policy-evals stream)]
          (is (= :verified (:intervention/state result)))
          (is (= [:dispatched :applied :verified] (state-trail stream)))
          (is (= golden-pr-target-id (:policy/target-id @captured))
              "the evaluator is asked about the intervention's target")
          (is (= 1 (count evals)) "exactly one PolicyEvaluation materialized")
          (is (= (get-in result [:intervention/outcome :policy-eval/id])
                 (:policy-eval/id (first evals)))
              "the verified outcome names the record that was written"))))))

(deftest re-evaluate-with-an-invalid-verdict-fails-and-publishes-nothing
  (testing "a nil / non-map / verdict-less evaluator result is not a verdict"
    (doseq [bad [nil
                 "not-a-map"
                 {}                                  ; missing :evaluation/passed?
                 {:evaluation/passed? "true"}        ; non-boolean verdict
                 {:evaluation/violations []}]]       ; violations but no verdict
      (with-policy-evaluator
        (fn [_request] bad)
        (fn []
          (let [stream (memory-stream)
                result (application/apply-intervention!
                        stream (approved :re-evaluate golden-pr-target-id))]
            (is (= :failed (:intervention/state result))
                (str "invalid evaluator result must fail the intervention: "
                     (pr-str bad)))
            (is (= :invalid-policy-evaluation (failure-code result)))
            (is (empty? (events-of-type stream :gate/failed))
                "no bogus :gate/failed record is minted")
            (is (empty? (policy-evals stream))
                "no PolicyEvaluation is materialized from a non-verdict")))))))

(deftest re-evaluation-adds-a-record-rather-than-mutating-one
  (testing "N5-delta-1 §12.2: completed evaluations are immutable"
    (with-policy-evaluator
      (fn [_request] (failing-evaluation))
      (fn []
        (let [stream (memory-stream)
              first-result (application/apply-intervention!
                            stream (approved :re-evaluate golden-pr-target-id))
              second-result (application/apply-intervention!
                             stream (approved :re-evaluate golden-pr-target-id))
              eval-ids (mapv :policy-eval/id (policy-evals stream))]
          (is (= :verified (:intervention/state first-result)))
          (is (= :verified (:intervention/state second-result)))
          (is (= 2 (count (set eval-ids)))
              "the second re-evaluation is a second record with a fresh id"))))))

(deftest a-throwing-evaluator-becomes-a-failed-intervention
  (with-policy-evaluator
    (fn [_request] (throw (ex-info "evaluator exploded" {})))
    (fn []
      (let [stream (memory-stream)
            result (application/apply-intervention!
                    stream (approved :re-evaluate golden-pr-target-id))]
        (is (= :failed (:intervention/state result)))
        (is (= :application-error (failure-code result)))
        (is (empty? (policy-evals stream))
            "a throwing evaluator writes no PolicyEvaluation")))))

(deftest policy-evaluator-registration-rejects-a-non-function
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires a function"
       (application/register-policy-evaluator! "not-a-fn"))))

;------------------------------------------------------------------------------ Registry

(deftest registry-round-trip
  (let [wid (str (random-uuid))
        stream (memory-stream)]
    (is (false? (application/live-runner? wid)))
    (application/register-runner! wid {:control-state (es/create-control-state)
                                       :event-stream stream})
    (is (true? (application/live-runner? wid)))
    (is (identical? stream
                    (application/live-intervention-stream
                     {:intervention/type :pause
                      :intervention/target-id wid})))
    (application/deregister-runner! wid)
    (application/deregister-runner! wid)
    (is (false? (application/live-runner? wid)))))

(deftest ownership-filter-does-not-hide-invalid-request-types
  (is (true? (application/live-intervention-target?
              {:intervention/type :not-in-the-bounded-vocabulary
               :intervention/target-type :workflow
               :intervention/target-id (random-uuid)}))
      "unknown types must reach lifecycle validation and emit an anomaly")
  (is (true? (application/live-intervention-target?
              {:intervention/type :force-safe-mode
               :intervention/target-type :workflow
               :intervention/target-id (random-uuid)}))
      "mismatched known targets must not be deferred as unowned"))
