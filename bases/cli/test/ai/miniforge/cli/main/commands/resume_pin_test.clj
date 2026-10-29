;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.resume-pin-test
  "`mf resume <id> --pin` pins the run's checkpoint and the checkpoints the
   resumed run writes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.automation-edge-correlator.interface :as correlator]
            [ai.miniforge.cli.main :as main]
            [ai.miniforge.cli.main.commands.resume :as sut]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.workflow-runner.context :as context]
            [ai.miniforge.cli.workflow-runner.control :as control]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow-resume.interface :as wr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resume-with
  "Resume with `opts` and the runtime stubbed; `pin-result` is what pinning
   returns. Returns {:pinned :run-opts :out}."
  [opts pin-result]
  (let [pinned (atom nil)
        run-opts (atom nil)
        out (with-redefs [wr/reconstruct-context
                          (fn [_ _] {:completed-phases [:plan] :phase-results {:plan {}} :event-count 1
                                     :completed-dag-tasks #{} :completed-dag-artifacts []
                                     :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}})
                          workflow/pin-checkpoint! (fn [id] (reset! pinned id) pin-result)
                          context/create-llm-client (fn [_ _ _] :llm-client)
                          es/create-event-stream (fn [] :event-stream)
                          supervisory/attach! (fn [_] nil)
                          correlator/attach! (fn [_] nil)
                          control/register-workflow-control! (fn [_ _ _] nil)
                          control/release-workflow-control! (fn [_] nil)
                          display/print-error (fn [& _] nil)
                          sut/load-workflow (fn [_ _ _]
                                              {:workflow {:workflow/id :canonical-sdlc
                                                          :workflow/version "1.0.0"
                                                          :workflow/pipeline [{:phase :plan}
                                                                              {:phase :implement}]}})
                          sut/run-pipeline (fn [_ _ o] (reset! run-opts o) {:execution/status :completed})]
              (with-out-str (sut/resume-workflow "wf-1" (assoc opts :quiet true))))]
    {:pinned @pinned :run-opts @run-opts :out out}))

(deftest ^{:stratum 0} resume-accepts-pin
  (let [seen (atom nil)]
    (with-redefs [sut/resume-workflow (fn [_ opts] (reset! seen opts) {:execution/status :completed})
                  shared/exit! (fn [_] nil)]
      (binding [*err* (java.io.StringWriter.)]
        (with-out-str (main/-main "resume" "wf-1" "--pin"))))
    (is (true? (:pin @seen)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} pin-pins-the-run-and-the-resumed-checkpoints
  (let [{:keys [pinned run-opts]} (resume-with {:pin true} "/tmp/manifest.edn")]
    (is (= "wf-1" pinned))
    (is (true? (:checkpoint/pin? run-opts))))
  (testing "without --pin nothing is pinned"
    (let [{:keys [pinned run-opts]} (resume-with {} "/tmp/manifest.edn")]
      (is (nil? pinned))
      (is (false? (:checkpoint/pin? run-opts))))))

(deftest ^{:stratum 1} a-run-with-nothing-to-pin-is-reported-and-resumed
  (let [{:keys [run-opts out]} (resume-with {:pin true}
                                            (anomaly/anomaly :not-found "Run wf-1 has no checkpoint manifest to pin." {}))]
    (is (some? run-opts))
    (is (str/includes? out "could not pin"))))

(comment ::resume-pin)
