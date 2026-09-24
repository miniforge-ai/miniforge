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
(ns ai.miniforge.cli.main.commands.resume-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.cli.main.display :as main-display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.main.commands.resume :as sut]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow-resume.interface :as wr]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} resolve-resume-workflow-test
  (testing "recorded workflow spec wins over configured fallback"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile]
                    (throw (ex-info "should not be called" {})))]
      (is (= {:workflow-type :financial-etl
              :workflow-version "1.2.3"}
             (sut/resolve-resume-workflow
              {:workflow-spec {:name "financial-etl"
                               :version "1.2.3"}})))))

  (testing "missing workflow spec falls back to app-configured default profile"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [profile]
                    (is (= :default profile))
                    :lean-sdlc-v1)]
      (is (= {:workflow-type :lean-sdlc-v1
              :workflow-version "latest"}
             (sut/resolve-resume-workflow {})))))

  (testing "missing configured fallback raises a clear error"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Could not resolve a workflow type for resume"
           (sut/resolve-resume-workflow {}))))))

(deftest ^{:stratum 0} throw-resume-anomaly-preserves-canonical-metadata-test
  (testing "CLI escalation keeps the original return-value anomaly fields"
    (let [source (anomaly/anomaly :invalid-input
                                  "Invalid resume request"
                                  {:workflow-id "bad"})
          thrown (try+
                  (#'sut/throw-resume-anomaly! source)
                  (catch [:anomaly/category :anomalies/incorrect] m m))]
      (is (= :anomalies/incorrect (:anomaly/category thrown)))
      (is (= :invalid-input (:anomaly/type thrown)))
      (is (= (:anomaly/at source) (:anomaly/at thrown)))
      (is (= "bad" (:workflow-id thrown))))))

;; read-event-file — bug fix coverage
(defn- ^{:stratum 0} with-temp-events-dir [body-fn]
  (let [base (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-resume-test-" (random-uuid)))
               .mkdirs)]
    (try
      (body-fn base)
      (finally
        (doseq [^java.io.File f (reverse (file-seq base))]
          (.delete f))))))

(defn- ^{:stratum 0} write-event! [^java.io.File dir filename event-map]
  (spit (io/file dir filename) (json/generate-string event-map)))

(deftest ^{:stratum 0} resume-workflow-passes-dag-recovery-data-test
  (let [workflow-id (random-uuid)
        run-pipeline-opts (atom nil)
        run-pipeline-workflow (atom nil)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :completed-dag-tasks #{:task-a}
                       :completed-dag-artifacts [{:artifact/id "art-1"}]
                       :workspace-checkpoint {:branch "task-a"
                                              :bundle-path "/tmp/task-a.bundle"}
                       :phase-results {:plan {:status :completed}}
                       :machine-snapshot {:execution/id workflow-id}
                       :workflow-spec {:name "canonical-sdlc"
                                       :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_events-dir _workflow-id] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_workflow _provider _quiet] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_event-stream] nil)
                  correlator/attach! (fn [_event-stream] nil)
                  control/register-workflow-control! (fn [_workflow-id
                                                         _control-state
                                                         _event-stream]
                                                       nil)
                  control/release-workflow-control! (fn [_workflow-id] nil)
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [_workflow-type _workflow-version _opts]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :verify}]}})
                  sut/run-pipeline (fn [workflow _input opts]
                                     (reset! run-pipeline-workflow workflow)
                                     (reset! run-pipeline-opts opts)
                                     {:execution/status :completed})]
      (let [result (sut/resume-workflow workflow-id {:quiet true})]
        (is (= :completed (:execution/status result)))
        (is (= [{:phase :verify}]
               (:workflow/pipeline @run-pipeline-workflow)))
        (is (nil? (:resume-reset-terminal? @run-pipeline-opts)))
        (is (= #{:task-a}
               (:pre-completed-dag-tasks @run-pipeline-opts)))
        (is (= [{:artifact/id "art-1"}]
               (:pre-completed-artifacts @run-pipeline-opts)))
        (is (= {:branch "task-a"
                :bundle-path "/tmp/task-a.bundle"}
               (:resume-workspace @run-pipeline-opts)))))))

(deftest ^{:stratum 0} terminal-status-predicate-test
  (testing "explicit terminal statuses are accepted"
    (doseq [s [:completed :completed-with-warnings :failed :aborted :cancelled]]
      (is (sut/terminal-status? s) (str s " must be terminal"))))
  (testing "non-terminal statuses are rejected"
    (doseq [s [:running :pending :paused nil :unknown :draining]]
      (is (not (sut/terminal-status? s)) (str s " must NOT be terminal")))))

(deftest ^{:stratum 0} resume-workflow-non-terminal-status-throws-test
  ;; Regression for the silent fast-fail blocker from the 2026-05-16
  ;; event-log-tool-visibility dogfood. Resume used to print
  ;; "Resumed workflow completed with status: :running" and exit 0,
  ;; losing the prior session's plan/explore/verify token spend.
  (let [workflow-id (random-uuid)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :machine-snapshot {:execution/id workflow-id
                                          :execution/current-phase :verify}
                       :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_ _] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_ _ _] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_] nil)
                  correlator/attach! (fn [_] nil)
                  control/register-workflow-control! (fn [_ _ _] nil)
                  control/release-workflow-control! (fn [_] nil)
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [& _]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :verify}]}})
                  sut/run-pipeline (fn [& _] {:execution/status :running})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"non-terminal status :running"
           (sut/resume-workflow workflow-id {:quiet true}))
          "Resume must throw, not silently return, when run-pipeline returns :running"))))

(deftest ^{:stratum 0} resume-print-phase-prefers-fsm-snapshot-test
  (testing "machine snapshot's :execution/current-phase wins over pipeline head"
    (is (= "verify"
           (#'sut/resume-print-phase
             {:execution/current-phase :verify}
             [{:phase :review} {:phase :release}]))))
  (testing "no snapshot → falls back to first remaining pipeline entry"
    (is (= "review"
           (#'sut/resume-print-phase nil [{:phase :review} {:phase :release}]))))
  (testing "snapshot present but :execution/current-phase missing → pipeline head"
    (is (= "review"
           (#'sut/resume-print-phase {} [{:phase :review}]))))
  (testing "both empty → nil"
    (is (nil? (#'sut/resume-print-phase nil [])))))

(deftest ^{:stratum 0} resume-workflow-trims-failed-checkpoint-before-running-test
  (let [workflow-id (random-uuid)
        run-pipeline-opts (atom nil)
        run-pipeline-workflow (atom nil)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :failed? true
                       :completed-dag-tasks #{}
                       :completed-dag-artifacts []
                       :phase-results {:plan {:status :completed}}
                       :machine-snapshot {:execution/id workflow-id
                                          :execution/status :failed
                                          :execution/fsm-state {:_state :failed}}
                       :workflow-spec {:name "canonical-sdlc"
                                       :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_events-dir _workflow-id] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_workflow _provider _quiet] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_event-stream] nil)
                  correlator/attach! (fn [_event-stream] nil)
                  control/register-workflow-control! (fn [_workflow-id
                                                         _control-state
                                                         _event-stream]
                                                       nil)
                  control/release-workflow-control! (fn [_workflow-id] nil)
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [_workflow-type _workflow-version _opts]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :plan}
                                                                      {:phase :implement}
                                                                      {:phase :verify}]}})
                  sut/run-pipeline (fn [workflow _input opts]
                                     (reset! run-pipeline-workflow workflow)
                                     (reset! run-pipeline-opts opts)
                                     {:execution/status :completed})]
      (let [result (sut/resume-workflow workflow-id {:quiet true})]
        (is (= :completed (:execution/status result)))
        (is (= [{:phase :implement} {:phase :verify}]
               (:workflow/pipeline @run-pipeline-workflow)))
        (is (true? (:resume-reset-terminal? @run-pipeline-opts)))
        (is (= workflow-id
               (get-in @run-pipeline-opts [:resume-machine-snapshot :execution/id])))))))

(defn- ^{:stratum 0} resume-with
  "Run `resume-workflow` over `reconstructed` with every runtime seam
   stubbed; returns what reached `run-pipeline` and the control
   registration."
  [reconstructed opts]
  (let [captured (atom {})]
    (with-redefs [wr/reconstruct-context (fn [_ _] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_ _ _] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_] nil)
                  correlator/attach! (fn [_] nil)
                  control/register-workflow-control! (fn [id _ _] (swap! captured assoc :registered id))
                  control/release-workflow-control! (fn [_] nil)
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [& _]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :plan}
                                                                      {:phase :implement}
                                                                      {:phase :verify}]}})
                  sut/run-pipeline (fn [workflow input run-opts]
                                     (swap! captured assoc :workflow workflow :input input :opts run-opts)
                                     {:execution/status :completed})]
      (sut/resume-workflow (random-uuid) (assoc opts :quiet true))
      @captured)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} from-phase-rewinds-and-drops-the-snapshot-test
  (let [snapshot-id (random-uuid)
        reconstructed {:completed-phases [:plan :implement]
                       :phase-results {:plan {} :implement {}}
                       :machine-snapshot {:execution/id snapshot-id
                                          :execution/current-phase :verify}}
        run-id (random-uuid)
        {:keys [workflow opts registered]}
        (resume-with reconstructed {:from-phase :implement :run-id (str run-id)})]
    (testing "the requested phase and everything after it run again"
      (is (= [{:phase :implement} {:phase :verify}] (:workflow/pipeline workflow))))
    (testing "the snapshot is dropped, so the run adopts the caller's run id"
      (is (nil? (:resume-machine-snapshot opts)))
      (is (= run-id (:workflow-id opts) registered))))
  (testing "the re-run starts from the earlier phases' results and the run's input"
    (let [acting {:acting/principal "operator"}
          {:keys [input opts]}
          (resume-with {:completed-phases [:plan :implement :verify]
                        :phase-results {:plan {:summary "plan"} :implement {:summary "old"}
                                        :verify {:summary "old"}}
                        :machine-snapshot {:execution/id (random-uuid)
                                           :execution/input {:task "original"}
                                           :execution/acting acting
                                           :execution/current-phase :verify}}
                       {:from-phase :implement})]
      (is (= {:plan {:summary "plan"}} (:resume-phase-results opts))
          "implement sees the plan; implement and verify start clean")
      (is (= {:task "original"} input))
      (is (= acting (:acting opts)))))
  (testing "a rewind does not carry the old run's DAG work into the re-run"
    (let [{:keys [opts]} (resume-with {:completed-phases [:plan :implement]
                                       :phase-results {:plan {} :implement {}}
                                       :completed-dag-tasks #{:task-a}
                                       :completed-dag-artifacts [{:artifact/id "a"}]}
                                      {:from-phase :plan})]
      (is (= #{} (:pre-completed-dag-tasks opts)))
      (is (= [] (:pre-completed-artifacts opts)))))
  (testing "a rewind restores only a workspace checkpoint from a phase before it"
    (let [plan-checkpoint {:branch "after-plan" :commit-sha "p" :phase :plan}
          implement-checkpoint {:branch "after-implement" :commit-sha "i" :phase :implement}
          persisted (fn [{:keys [branch commit-sha phase]}]
                      {:event/type :workspace/persisted :workspace/branch branch
                       :workspace/commit-sha commit-sha :workflow/phase phase})
          history {:completed-phases [:plan :implement]
                   :phase-results {:plan {} :implement {}}
                   :workspace-checkpoint implement-checkpoint}
          workspace-after (fn [from-phase]
                            (with-redefs [sut/read-event-file
                                          (constantly (mapv persisted [plan-checkpoint implement-checkpoint]))]
                              (get-in (resume-with history {:from-phase from-phase})
                                      [:opts :resume-workspace])))]
      (is (= "after-plan" (:branch (workspace-after :implement)))
          "not the checkpoint the re-run phase itself produced")
      (is (nil? (workspace-after :plan))
          "no checkpoint before the rewind point: a fresh workspace")))
  (testing "a phase the run never recorded is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"never recorded"
                          (resume-with {:completed-phases [:plan]} {:from-phase :release})))))

(deftest ^{:stratum 1} run-id-option-test
  (let [snapshot-id (random-uuid)
        snapshotted {:completed-phases [:plan]
                     :phase-results {:plan {}}
                     :machine-snapshot {:execution/id snapshot-id}}]
    (testing "a snapshot resumes under its own id, which --run-id may repeat"
      (is (= snapshot-id (:workflow-id (:opts (resume-with snapshotted {})))))
      (is (= snapshot-id (:registered (resume-with snapshotted {:run-id (str snapshot-id)})))))
    (testing "a --run-id that disagrees with the snapshot, or is not a UUID, is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checkpoint restores run"
                            (resume-with snapshotted {:run-id (str (random-uuid))})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--run-id must be a UUID"
                            (resume-with {:completed-phases []} {:run-id "not-a-uuid"}))))
    (testing "--correlation-id reaches the run; without it none is imposed"
      (let [correlation-id (random-uuid)]
        (is (= correlation-id
               (get-in (resume-with snapshotted {:correlation-id (str correlation-id)})
                       [:opts :workflow-run/correlation-id])))
        (is (nil? (get-in (resume-with (assoc-in snapshotted [:machine-snapshot :workflow-run/correlation-id]
                                                 (random-uuid))
                                       {})
                          [:opts :workflow-run/correlation-id]))
            "the runner's default applies: the run's own id")))
    (testing "the options are checked before a completed run is reported done"
      (let [completed (assoc snapshotted :completed? true)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--run-id must be a UUID"
                              (resume-with completed {:run-id "not-a-uuid"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checkpoint restores run"
                              (resume-with completed {:run-id (str (random-uuid))})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--correlation-id must be a UUID"
                              (resume-with completed {:correlation-id "nope"})))))))

(deftest ^{:stratum 1} read-event-file-reads-per-event-json-from-workflow-dir-test
  ;; Regression guard for the iter-20 resume bug. Before this fix,
  ;; read-event-file looked for a single {workflow-id}.edn file that
  ;; was never written (the sink writes one .json per event to a dir),
  ;; so `mf run --resume <id>` always threw :anomalies/not-found.
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        (write-event! wf-dir "20260420T000001Z-a.json"
                      {"~:event/type" "~:workflow/started"
                       "~:workflow/id" (str "~u" wf-id)})
        (write-event! wf-dir "20260420T000002Z-b.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:plan"
                       "~:phase/outcome" "~:success"})
        (testing "reads events in timestamp order with transit prefixes stripped"
          (with-redefs [sut/events-dir (.getPath base-dir)]
            (let [events (sut/read-event-file wf-id)]
              (is (= 2 (count events)))
              (is (= :workflow/started (:event/type (first events))))
              (is (= :workflow/phase-completed (:event/type (second events))))
              (is (= :plan (:workflow/phase (second events))))
              (is (= :success (:phase/outcome (second events)))))))))))

(deftest ^{:stratum 1} read-event-file-missing-workflow-returns-nil-test
  ;; The CLI wrapper now returns nil for missing workflows; the
  ;; user-facing :anomalies/not-found comes from the workflow-resume
  ;; component's `reconstruct-context` when callers use that path.
  (with-temp-events-dir
    (fn [base-dir]
      (with-redefs [sut/events-dir (.getPath base-dir)]
        (is (nil? (sut/read-event-file (str (random-uuid)))))))))
