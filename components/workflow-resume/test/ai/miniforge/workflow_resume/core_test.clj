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

(ns ai.miniforge.workflow-resume.core-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.interface.checkpoints :as workflow-checkpoints]
   [ai.miniforge.workflow-resume.core :as core]
   [ai.miniforge.workflow-resume.interface :as wr]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors

(deftest missing-resume-config-resource-carries-invalid-config-marker
  (testing "Missing classpath resume config is an invalid configuration fault"
    (with-redefs [io/resource (constantly nil)]
      (let [result (@#'core/read-resume-config)]
        (is (anomaly/anomaly? result))
        (is (= :not-found (:anomaly/type result)))
        (is (= "config/workflow-resume/resume.edn"
               (get-in result [:anomaly/data :resource])))
        (is (= :invalid-config
               (get-in result [:anomaly/data :config/error])))))))

(deftest reconstruct-context-propagates-resume-config-anomaly-test
  (testing "configuration faults short-circuit before disk reads"
    (let [config-anomaly (anomaly/anomaly
                          :not-found
                          "Missing resume configuration"
                          {:resource "config/workflow-resume/resume.edn"})]
      (with-redefs [core/resume-config (delay config-anomaly)
                    workflow-checkpoints/load-checkpoint-data
                    (fn [_workflow-id]
                      (throw (ex-info "checkpoint should not be read" {})))
                    es/read-workflow-events-by-id
                    (fn [_events-dir _workflow-id]
                      (throw (ex-info "events should not be read" {})))]
        (is (= config-anomaly
               (core/reconstruct-context "/tmp/unused-events" (str (random-uuid)))))))))

(deftest reconstructed-status-predicates-test
  (testing "predicates read the reconstructed status flags"
    (let [completed {:completed? true}
          failed {:failed? true}
          paused {:dag-paused? true}
          running {}]
      (is (true? (core/completed? completed)))
      (is (false? (core/completed? running)))
      (is (true? (core/failed? failed)))
      (is (false? (core/failed? running)))
      (is (true? (core/paused? paused)))
      (is (false? (core/paused? running))))))

(deftest extract-completed-phases-test
  (testing ":success and :skipped count as completed; :failure does not"
    (let [events [{:event/type :workflow/started}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :explore :phase/outcome :success}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :plan :phase/outcome :success}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :release :phase/outcome :skipped}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :implement :phase/outcome :failure}]]
      (is (= [:explore :plan :release] (core/extract-completed-phases events))
          "a :skipped phase is completed (not re-run); a :failure is omitted"))))

(deftest extract-phase-results-test
  (testing "builds {phase → {:outcome :duration-ms :timestamp}}"
    (let [events [{:event/type :workflow/phase-completed
                   :workflow/phase :plan
                   :phase/outcome :success
                   :phase/duration-ms 42
                   :event/timestamp "2026-04-21T00:00:00Z"}]
          result (core/extract-phase-results events)]
      (is (= :success (get-in result [:plan :outcome])))
      (is (= 42 (get-in result [:plan :duration-ms])))
      (is (= "2026-04-21T00:00:00Z" (get-in result [:plan :timestamp])))))

  (testing "review verdict is reconstructed into the canonical
            [:result :output :review/decision] location — lossless from events,
            so a blocked review is detectable on event-only resume"
    (let [events [{:event/type :workflow/phase-completed
                   :workflow/phase :review
                   :phase/outcome :success
                   :phase/review-decision :changes-requested
                   :event/timestamp "2026-04-21T00:00:00Z"}]
          result (core/extract-phase-results events)]
      (is (= :changes-requested
             (get-in result [:review :result :output :review/decision])))))

  (testing "non-review phases carry no review decision (key absent, not nil)"
    (let [events [{:event/type :workflow/phase-completed
                   :workflow/phase :plan :phase/outcome :success}]
          result (core/extract-phase-results events)]
      (is (not (contains? (get-in result [:plan :result] {}) :output))))))

(deftest extract-completed-dag-tasks-test
  (testing "collects :dag/task-id values from :dag/task-completed events"
    (let [events [{:event/type :dag/task-completed :dag/task-id "t1"}
                  {:event/type :dag/task-completed :dag/task-id "t2"}
                  {:event/type :dag/task-failed    :dag/task-id "t3"}]]
      (is (= #{"t1" "t2"} (core/extract-completed-dag-tasks events))))))

(deftest extract-completed-dag-artifacts-test
  (testing "collects artifacts from :dag/task-completed events"
    (let [events [{:event/type :dag/task-completed
                   :dag/result {:data {:artifacts [{:artifact/id "a"}]}}}
                  {:event/type :dag/task-completed
                   :dag/result {:data {:artifacts [{:artifact/id "b"}
                                                   {:artifact/id "c"}]}}}
                  {:event/type :dag/task-failed
                   :dag/result {:data {:artifacts [{:artifact/id "ignored"}]}}}]]
      (is (= [{:artifact/id "a"} {:artifact/id "b"} {:artifact/id "c"}]
             (core/extract-completed-dag-artifacts events))))))

(deftest extract-workspace-checkpoints-test
  (testing "collects persisted workspace provenance for failed-task resume"
    (let [events [{:event/type :workspace/persisted
                   :workspace/branch "task-a"
                   :workspace/bundle-path "/tmp/task-a.bundle"
                   :workspace/commit-sha "abc"
                   :workspace/env-id "task-a"
                   :workflow/phase :review
                   :event/timestamp "2026-05-16T00:00:00Z"}
                  {:event/type :workspace/persisted
                   :workspace/branch "task-b"
                   :workspace/bundle-path "/tmp/task-b.bundle"
                   :workspace/commit-sha "def"}
                  {:event/type :workspace/persisted
                   :workspace/branch nil
                   :workspace/bundle-path "/tmp/ignored.bundle"}]
          checkpoints (core/extract-workspace-checkpoints events)]
      (is (= 2 (count checkpoints)))
      (is (= "task-a" (:branch (first checkpoints))))
      (is (= "/tmp/task-b.bundle" (:bundle-path (second checkpoints)))))))

(deftest extract-dag-pause-info-last-pause-wins-test
  (testing "multiple pause events — latest one wins"
    (let [events [{:event/type :dag/paused
                   :dag/completed-task-ids ["a"]
                   :dag/pause-reason :rate-limit}
                  {:event/type :dag/paused
                   :dag/completed-task-ids ["a" "b"]
                   :dag/pause-reason :operator}]
          info (core/extract-dag-pause-info events)]
      (is (= #{"a" "b"} (:completed-task-ids info)))
      (is (= :operator (:pause-reason info)))))

  (testing "no :dag/paused events → nil"
    (is (nil? (core/extract-dag-pause-info
                [{:event/type :workflow/started}])))))

(deftest find-workflow-spec-test
  (testing "returns :workflow/spec from the first :workflow/started event"
    (let [events [{:event/type :workflow/started
                   :workflow/spec {:name "planner-convergence" :version "1.0"}}
                  {:event/type :workflow/phase-started}]]
      (is (= {:name "planner-convergence" :version "1.0"}
             (core/find-workflow-spec events)))))

  (testing "nil when no :workflow/started event"
    (is (nil? (core/find-workflow-spec [{:event/type :workflow/phase-started}])))))

;------------------------------------------------------------------------------ Layer 1
;; trim-pipeline

(deftest trim-pipeline-test
  (testing "already-completed phases are removed; remaining order preserved"
    (let [workflow {:workflow/pipeline [{:phase :explore}
                                        {:phase :plan}
                                        {:phase :implement}
                                        {:phase :verify}
                                        {:phase :release}]}
          trimmed (core/trim-pipeline workflow [:explore :plan])]
      (is (= [{:phase :implement} {:phase :verify} {:phase :release}]
             (:workflow/pipeline trimmed)))))

  (testing "empty completed → unchanged pipeline"
    (let [wf {:workflow/pipeline [{:phase :a} {:phase :b}]}]
      (is (= [{:phase :a} {:phase :b}]
             (:workflow/pipeline (core/trim-pipeline wf []))))))

  (testing "all phases completed → empty pipeline"
    (let [wf {:workflow/pipeline [{:phase :a} {:phase :b}]}]
      (is (= [] (:workflow/pipeline (core/trim-pipeline wf [:a :b]))))))

  (testing "non-contiguous completed phases do not skip phases after the first gap"
    (let [wf {:workflow/pipeline [{:phase :explore}
                                  {:phase :plan}
                                  {:phase :implement}
                                  {:phase :verify}
                                  {:phase :review}
                                  {:phase :release}]}
          trimmed (core/trim-pipeline wf [:explore :plan :verify])]
      (is (= [{:phase :implement}
              {:phase :verify}
              {:phase :review}
              {:phase :release}]
             (:workflow/pipeline trimmed))))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-workflow-identity

(deftest resolve-workflow-identity-from-spec-test
  (testing "recorded workflow spec wins — fallback-fn not called"
    (is (= {:workflow-type :financial-etl :workflow-version "1.2.3"}
           (core/resolve-workflow-identity
             {:workflow-spec {:name "financial-etl" :version "1.2.3"}}
             (fn [] (throw (ex-info "should not be called" {})))))))

  (testing "no version → \"latest\""
    (is (= {:workflow-type :lean-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
             {:workflow-spec {:name "lean-sdlc"}}
             (constantly nil))))))

(deftest resolve-workflow-identity-from-machine-snapshot-test
  (testing "machine snapshot identity wins when no workflow spec is present"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "2.0.0"}
           (core/resolve-workflow-identity
            {:machine-snapshot {:execution/workflow-id :canonical-sdlc
                                :execution/workflow-version "2.0.0"}}
            (constantly nil))))))

(deftest resolve-workflow-identity-ignores-synthetic-dag-task-workflow-id-test
  (testing "synthetic DAG task workflow ids fall back to a loadable top-level workflow type"
    (is (= {:workflow-type :default-sdlc :workflow-version "2.0.0"}
           (core/resolve-workflow-identity
            {:machine-snapshot {:execution/workflow-id :dag-task-a0000001-0001-4000-8000-000000000001
                                :execution/workflow-version "2.0.0"}}
            (constantly :default-sdlc))))))

(deftest resolve-workflow-identity-fallback-test
  (testing "no spec → fallback-fn result used as :workflow-type"
    (is (= {:workflow-type :default-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
             {}
             (fn [] :default-sdlc)))))

  (testing "neither spec nor fallback → :anomalies/not-found"
    (let [result (core/resolve-workflow-identity {} (constantly nil))]
      (is (anomaly/anomaly? result))
      (is (= :not-found (:anomaly/type result)))
      (is (= :resume-workflow
             (get-in result [:anomaly/data :operation]))))))

(deftest resolve-workflow-identity-prefers-workflow-type-key-test
  (testing ":workflow-type wins over :name when both are present"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
             {:workflow-spec {:workflow-type :canonical-sdlc
                              :name "human-readable-title"
                              :version "latest"}}
             (fn [] (throw (ex-info "fallback should not be called" {})))))))

  (testing ":workflow/id (config-key shape) is accepted"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "2.0.0"}
           (core/resolve-workflow-identity
             {:workflow-spec {:workflow/id :canonical-sdlc :version "2.0.0"}}
             (constantly nil))))))

(deftest resolve-workflow-identity-rejects-non-identifier-name-test
  ;; Regression for the 2026-05-22 dogfood of
  ;; work/in-flight-pr-registry.spec.edn (workflow a92b2c97), where the
  ;; recorded spec was {:name "In-flight PR / branch / task-claim
  ;; registry" :version "latest"} and the prior unconditional
  ;; `(some-> workflow-spec :name keyword)` produced an unloadable
  ;; lookup key with spaces and slashes.
  (testing "spec :name that is not a valid keyword name falls through to fallback"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
             {:workflow-spec {:name "In-flight PR / branch / task-claim registry"
                              :version "latest"}}
             (fn [] :canonical-sdlc)))))

  (testing "machine snapshot identity is consulted when :name is rejected and present"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "2.0.0"}
           (core/resolve-workflow-identity
            {:workflow-spec    {:name "Some Human Title" :version "2.0.0"}
             :machine-snapshot {:execution/workflow-id      :canonical-sdlc
                                :execution/workflow-version "2.0.0"}}
            (fn [] (throw (ex-info "fallback should not be called" {})))))))

  (testing "spec :name keyword with an invalid workflow-type name falls through to fallback"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
            {:workflow-spec {:name (keyword "Some Human Title")
                             :version "latest"}}
            (fn [] :canonical-sdlc))))))

(deftest resolve-workflow-identity-rejects-qualified-workflow-types-test
  (testing "slash-containing string identifiers are rejected before keywordization"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
            {:workflow-spec {:workflow-type "foo/bar"
                             :name "canonical-sdlc"
                             :version "latest"}}
            (fn [] :fallback-sdlc)))))

  (testing "qualified keyword identifiers are rejected"
    (is (= {:workflow-type :canonical-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
            {:workflow-spec {:workflow-type :foo/bar
                             :workflow/id :canonical-sdlc
                             :version "latest"}}
            (constantly nil)))))

  (testing "qualified :name keyword falls through to fallback"
    (is (= {:workflow-type :fallback-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
            {:workflow-spec {:name :foo/bar}}
            (fn [] :fallback-sdlc))))))

;------------------------------------------------------------------------------ Layer 2
;; reconstruct-context — integration with the event-stream reader

(defn- with-temp-events-dir [body-fn]
  (let [base (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-wr-test-" (random-uuid)))
               .mkdirs)]
    (try
      (body-fn base)
      (finally
        (doseq [^java.io.File f (reverse (file-seq base))]
          (.delete f))))))

(defn- write-event! [^java.io.File dir filename event-map]
  (spit (io/file dir filename) (json/generate-string event-map)))

(def resume-checkpoint-config
  {:base-machine-snapshot {:execution/workflow-id :canonical-sdlc
                           :execution/workflow-version "1.0.0"
                           :execution/status :failed}
   :blocked-review {:manifest {:workflow/phases-completed [:explore
                                                           :plan
                                                           :implement
                                                           :review
                                                           :release]}
                    :phase-results {:explore {:status :completed}
                                    :plan {:status :completed}
                                    :implement {:status :completed}
                                    :review {:status :completed
                                             :result {:output {:review/decision :changes-requested}}}
                                    :release {:status :retrying}}}
   :damaged-manifest {:manifest {:workflow/phases-completed [:release :plan]}
                      :phase-results {:release {:status :retrying}
                                      :plan {:status :completed}}}})

(defn- configured-checkpoint-data
  [config-key workflow-id]
  (let [checkpoint (get resume-checkpoint-config config-key)]
    (assoc checkpoint :machine-snapshot
           (assoc (:base-machine-snapshot resume-checkpoint-config)
                  :execution/id workflow-id))))

(deftest reconstruct-context-integration-test
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        ;; workflow/started (with spec)
        (write-event! wf-dir "20260421T000000Z-start.json"
                      {"~:event/type" "~:workflow/started"
                       "~:workflow/id" (str "~u" wf-id)
                       "~:workflow/spec" {"name" "resumable"
                                          "version" "2.0"}})
        ;; explore completed
        (write-event! wf-dir "20260421T000001Z-e.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:explore"
                       "~:phase/outcome" "~:success"
                       "~:phase/duration-ms" 123})
        ;; plan completed
        (write-event! wf-dir "20260421T000002Z-p.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:plan"
                       "~:phase/outcome" "~:success"
                       "~:phase/duration-ms" 456})
        ;; release skipped; should appear in completed-phases
        (write-event! wf-dir "20260421T000003Z-r.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:release"
                       "~:phase/outcome" "~:skipped"})
        ;; implement failed — should NOT appear in completed-phases
        (write-event! wf-dir "20260421T000004Z-i.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:implement"
                       "~:phase/outcome" "~:failure"})
        (write-event! wf-dir "20260421T000005Z-w.json"
                      {"~:event/type" "~:workspace/persisted"
                       "~:workspace/branch" "task-resume"
                       "~:workspace/bundle-path" "/tmp/task-resume.bundle"
                       "~:workspace/commit-sha" "abc123"
                       "~:workspace/env-id" "task-resume"
                       "~:workflow/phase" "~:review"})
        (testing "reconstructs context from per-event JSON files"
          (let [ctx (core/reconstruct-context base-dir wf-id)]
            (is (= [:explore :plan :release] (:completed-phases ctx)))
            (is (= 6 (:event-count ctx)))
            (is (false? (:completed? ctx)))
            (is (false? (:failed? ctx)))
            (is (= wf-id (:workflow-id ctx)))
            (is (= {"name" "resumable" "version" "2.0"}
                   (:workflow-spec ctx)))
            (is (= 123 (get-in ctx [:phase-results :explore :duration-ms])))
            (is (= :success (get-in ctx [:phase-results :plan :outcome])))
            (is (= :skipped (get-in ctx [:phase-results :release :outcome])))
            (is (= {:branch "task-resume"
                    :bundle-path "/tmp/task-resume.bundle"
                    :commit-sha "abc123"
                    :persist-tier nil
                    :env-id "task-resume"
                    :phase :review
                    :timestamp nil}
                   (:workspace-checkpoint ctx)))))))))

(deftest reconstruct-context-missing-workflow-test
  (with-temp-events-dir
    (fn [base-dir]
      (testing "missing workflow dir → :not-found anomaly"
        (let [result (core/reconstruct-context base-dir (str (random-uuid)))]
          (is (anomaly/anomaly? result))
          (is (= :not-found (:anomaly/type result))))))))

(deftest reconstruct-context-prefers-machine-snapshot-test
  (testing "checkpoint data restores workflow and DAG resume state without requiring event files"
    (let [workflow-id (str (random-uuid))
          checkpoint-data {:machine-snapshot {:execution/id workflow-id
                                             :execution/workflow-id :canonical-sdlc
                                             :execution/workflow-version "1.0.0"
                                             :execution/status :running
                                             :execution/dag-result {:paused? true
                                                                    :completed-task-ids ["task-1" "task-2"]
                                                                    :artifacts [{:artifact/id "art-1"}]
                                                                    :pause-reason :rate-limit}}
                           :manifest {:workflow/phases-completed [:plan]}
                           :phase-results {:plan {:status :completed}}}]
      (with-redefs [workflow-checkpoints/load-checkpoint-data (fn [_workflow-run-id] checkpoint-data)
                    es/read-workflow-events-by-id (fn [_events-dir _workflow-run-id] nil)]
        (let [ctx (core/reconstruct-context "/tmp/unused-events" workflow-id)]
          (is (= workflow-id (:workflow-id ctx)))
          (is (= (:machine-snapshot checkpoint-data) (:machine-snapshot ctx)))
          (is (= [:plan] (:completed-phases ctx)))
          (is (= {:status :completed}
                 (get-in ctx [:phase-results :plan])))
          (is (= #{"task-1" "task-2"} (:completed-dag-tasks ctx)))
          (is (= [{:artifact/id "art-1"}] (:completed-dag-artifacts ctx)))
          (is (true? (:dag-paused? ctx)))
          (is (= :rate-limit (:dag-pause-reason ctx)))
          (is (= 0 (:event-count ctx))))))))

(deftest reconstruct-context-uses-checkpoint-status-over-stale-terminal-events-test
  (testing "a resumed running checkpoint overrides older failed events"
    (let [workflow-id (str (random-uuid))
          checkpoint-data {:machine-snapshot {:execution/id workflow-id
                                             :execution/workflow-id :canonical-sdlc
                                             :execution/workflow-version "1.0.0"
                                             :execution/status :running}
                           :manifest {:workflow/phases-completed [:plan]}
                           :phase-results {:plan {:status :completed}}}
          events [{:event/type :workflow/failed}]]
      (with-redefs [workflow-checkpoints/load-checkpoint-data (fn [_workflow-run-id] checkpoint-data)
                    es/read-workflow-events-by-id (fn [_events-dir _workflow-run-id] events)]
        (let [ctx (core/reconstruct-context "/tmp/unused-events" workflow-id)]
          (is (false? (:failed? ctx)))
          (is (false? (:completed? ctx))))))))

(deftest reconstruct-context-trims-only-completed-checkpoint-phases-test
  (testing "checkpoint manifests may list failed/retrying phases; resume skips only completed results"
    (let [workflow-id (str (random-uuid))
          checkpoint-data (configured-checkpoint-data :blocked-review
                                                      workflow-id)]
      (with-redefs [workflow-checkpoints/load-checkpoint-data (fn [_workflow-run-id] checkpoint-data)
                    es/read-workflow-events-by-id (fn [_events-dir _workflow-run-id] nil)]
        (let [ctx (core/reconstruct-context "/tmp/unused-events" workflow-id)]
          (is (= [:explore :plan :implement] (:completed-phases ctx))))))))

(deftest reconstruct-context-merges-latest-successful-events-with-checkpoint-test
  (testing "a damaged manifest can still resume from successful event history"
    (let [workflow-id (str (random-uuid))
          checkpoint-data (configured-checkpoint-data :damaged-manifest
                                                      workflow-id)
          events [{:event/type :workflow/phase-completed
                   :workflow/phase :explore
                   :phase/outcome :success}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :implement
                   :phase/outcome :failure}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :verify
                   :phase/outcome :success}]]
      (with-redefs [workflow-checkpoints/load-checkpoint-data (fn [_workflow-run-id] checkpoint-data)
                    es/read-workflow-events-by-id (fn [_events-dir _workflow-run-id] events)]
        (let [ctx (core/reconstruct-context "/tmp/unused-events" workflow-id)]
          (is (= [:explore :verify :plan] (:completed-phases ctx))))))))

;------------------------------------------------------------------------------ Layer 3
;; Interface re-exports

(deftest interface-reexports-test
  (testing "interface exposes the domain API"
    (is (= core/completed? wr/completed?))
    (is (= core/failed? wr/failed?))
    (is (= core/paused? wr/paused?))
    (is (= core/extract-completed-dag-tasks wr/extract-completed-dag-tasks))
    (is (= core/extract-completed-dag-artifacts wr/extract-completed-dag-artifacts))
    (is (= core/extract-workspace-checkpoints wr/extract-workspace-checkpoints))
    (is (= core/extract-completed-phases wr/extract-completed-phases))
    (is (= core/reconstruct-context wr/reconstruct-context))
    (is (= core/trim-pipeline wr/trim-pipeline))
    (is (= core/resolve-workflow-identity wr/resolve-workflow-identity))))

;------------------------------------------------------------------------------ Layer 4
;; Validation — schemas enforce data shape at the component boundary

(deftest reconstruct-context-validates-workflow-id-test
  (with-temp-events-dir
    (fn [base-dir]
      (testing "nil workflow-id is rejected before disk access"
        (let [result (core/reconstruct-context base-dir nil)]
          (is (anomaly/anomaly? result))
          (is (= :invalid-input (:anomaly/type result)))))

      (testing "non-string/non-uuid workflow-id is rejected"
        (let [result (core/reconstruct-context base-dir 42)]
          (is (anomaly/anomaly? result))
          (is (= :invalid-input (:anomaly/type result))))))))

(deftest reconstruct-context-filters-malformed-events-test
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        ;; Valid event
        (write-event! wf-dir "20260421T000001Z-a.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:plan"
                       "~:phase/outcome" "~:success"})
        ;; Parseable JSON but missing :event/type — gets filtered
        (write-event! wf-dir "20260421T000002Z-b.json"
                      {"legacy" "no event/type here"})
        ;; Parseable but :event/type is a string — gets filtered
        (write-event! wf-dir "20260421T000003Z-c.json"
                      {"~:event/type" "workflow/phase-completed"})
        (testing "malformed-shape events dropped; :event-count reflects valid only"
          (let [ctx (core/reconstruct-context base-dir wf-id)]
            (is (= 1 (:event-count ctx)))
            (is (= [:plan] (:completed-phases ctx)))))))))

(deftest reconstruct-context-rejects-malformed-only-events-test
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        (write-event! wf-dir "20260421T000002Z-b.json"
                      {"legacy" "no event/type here"})
        (write-event! wf-dir "20260421T000003Z-c.json"
                      {"~:event/type" "workflow/phase-completed"})
        (testing "parseable events without a valid discriminator are not a resume source"
          (let [result (core/reconstruct-context base-dir wf-id)]
            (is (anomaly/anomaly? result))
            (is (= :not-found (:anomaly/type result)))
            (is (= 2 (get-in result [:anomaly/data :raw-event-count])))))))))

(deftest trim-pipeline-validates-workflow-shape-test
  (testing "workflow without :workflow/pipeline is rejected"
    (let [result (core/trim-pipeline {:wrong-shape true} [])]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))))

  (testing "pipeline entries without :phase keyword are rejected"
    (let [result (core/trim-pipeline {:workflow/pipeline [{:no-phase "here"}]} [])]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest resolve-workflow-identity-validates-fallback-fn-test
  (testing "non-function fallback is rejected"
    (let [result (core/resolve-workflow-identity
                  {:workflow-spec {:name "x"}}
                  "not a function")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))
