;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-checkpoint-warning-test
  "`miniforge resume` with checkpoint files it could not read: it tells
   the user, even with --quiet, and does not run past a phase whose
   checkpointed result is lost."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.automation-edge-correlator.interface :as correlator]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.control :as control]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow-resume.interface :as wr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private unreadable-plan
  (anomaly/anomaly :unavailable
                   "Checkpoint file /tmp/run/phases/plan.edn could not be read: EOF while reading"
                   {:checkpoint/path "/tmp/run/phases/plan.edn" :phase/name :plan}))

(defn- ^{:stratum 0} resume-with
  "Run `resume-workflow` over `reconstructed` with the runtime stubbed;
   returns what reached `run-pipeline`."
  [reconstructed opts]
  (let [captured (atom {})]
    (with-redefs [wr/reconstruct-context (fn [_ _] reconstructed)
                  context/create-llm-client (fn [_ _ _] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_] nil)
                  correlator/attach! (fn [_] nil)
                  control/register-workflow-control! (fn [_ _ _] nil)
                  control/release-workflow-control! (fn [_] nil)
                  display/print-info (fn [& _] nil)
                  display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [_ _ _]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :plan}
                                                                      {:phase :implement}
                                                                      {:phase :verify}]}})
                  sut/run-pipeline (fn [workflow input run-opts]
                                     (reset! captured {:workflow workflow :input input :opts run-opts})
                                     {:execution/status :completed})]
      (sut/resume-workflow (str (random-uuid)) (assoc opts :quiet true))
      @captured)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parked-at-implement
  "A running run parked at :implement whose :plan result did not read."
  []
  {:completed-phases []
   :phase-results {}
   :event-count 0
   :completed-dag-tasks #{}
   :completed-dag-artifacts []
   :checkpoint-manifest {:workflow/phases-completed [:plan]}
   :checkpoint-read-failures [unreadable-plan]
   :machine-snapshot {:execution/id (random-uuid)
                      :execution/status :running
                      :execution/current-phase :implement
                      :execution/input {:task "original"}
                      :execution/fsm-state {:_state :implement}}
   :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}})

(deftest ^{:stratum 1} unreadable-checkpoint-files-are-printed-even-when-quiet
  (let [output (with-redefs [wr/reconstruct-context
                             (fn [_events-dir _workflow-id]
                               {:completed? true
                                :checkpoint-read-failures [unreadable-plan]})]
                 (with-out-str (sut/resume-workflow (str (random-uuid)) {:quiet true})))]
    (is (str/includes? output "/tmp/run/phases/plan.edn could not be read"))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-lost-phase-result-rewinds-instead-of-restoring-the-snapshot
  (let [{:keys [workflow input opts]} (resume-with (parked-at-implement) {})]
    (testing "the snapshot parked after the lost phase is not restored"
      (is (nil? (:resume-machine-snapshot opts))))
    (testing "the lost phase and everything after it run again"
      (is (= [{:phase :plan} {:phase :implement} {:phase :verify}]
             (:workflow/pipeline workflow))))
    (testing "the run's own input is kept"
      (is (= {:task "original"} input)))))

(deftest ^{:stratum 2} a-rewind-past-a-lost-phase-result-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no checkpointed result for plan"
                        (resume-with (parked-at-implement) {:from-phase :implement}))))

(comment ::resume-checkpoint-warning)
