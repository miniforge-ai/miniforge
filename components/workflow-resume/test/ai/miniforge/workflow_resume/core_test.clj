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
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.workflow-resume.core :as core]
   [ai.miniforge.workflow-resume.interface :as wr]))

;------------------------------------------------------------------------------ Layer 0
;; Pure extractors

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
  (testing "only :phase-completed events with :success outcome are collected"
    (let [events [{:event/type :workflow/started}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :explore :phase/outcome :success}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :plan :phase/outcome :success}
                  {:event/type :workflow/phase-completed
                   :workflow/phase :implement :phase/outcome :failure}]]
      (is (= [:explore :plan] (core/extract-completed-phases events))))))

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
      (is (= "2026-04-21T00:00:00Z" (get-in result [:plan :timestamp]))))))

(deftest extract-completed-dag-tasks-test
  (testing "collects :dag/task-id values from :dag/task-completed events"
    (let [events [{:event/type :dag/task-completed :dag/task-id "t1"}
                  {:event/type :dag/task-completed :dag/task-id "t2"}
                  {:event/type :dag/task-failed    :dag/task-id "t3"}]]
      (is (= #{"t1" "t2"} (core/extract-completed-dag-tasks events))))))

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
      (is (= [] (:workflow/pipeline (core/trim-pipeline wf [:a :b])))))))

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

(deftest resolve-workflow-identity-fallback-test
  (testing "no spec → fallback-fn result used as :workflow-type"
    (is (= {:workflow-type :default-sdlc :workflow-version "latest"}
           (core/resolve-workflow-identity
             {}
             (fn [] :default-sdlc)))))

  (testing "neither spec nor fallback → :anomalies/not-found"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Could not resolve a workflow type for resume"
         (core/resolve-workflow-identity
           {}
           (constantly nil))))))

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
        ;; implement failed — should NOT appear in completed-phases
        (write-event! wf-dir "20260421T000003Z-i.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:implement"
                       "~:phase/outcome" "~:failure"})
        (testing "reconstructs context from per-event JSON files"
          (let [ctx (core/reconstruct-context base-dir wf-id)]
            (is (= [:explore :plan] (:completed-phases ctx)))
            (is (= 4 (:event-count ctx)))
            (is (false? (:completed? ctx)))
            (is (false? (:failed? ctx)))
            (is (= wf-id (:workflow-id ctx)))
            (is (= {"name" "resumable" "version" "2.0"}
                   (:workflow-spec ctx)))
            (is (= 123 (get-in ctx [:phase-results :explore :duration-ms])))
            (is (= :success (get-in ctx [:phase-results :plan :outcome])))))))))

(deftest reconstruct-context-missing-workflow-test
  (with-temp-events-dir
    (fn [base-dir]
      (testing "missing workflow dir → :anomalies/not-found"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"No events found for workflow:"
             (core/reconstruct-context base-dir (str (random-uuid)))))))))

(deftest reconstruct-context-prefers-machine-snapshot-test
  (testing "checkpoint data restores without requiring event files"
    (let [workflow-id (str (random-uuid))
          checkpoint-data {:machine-snapshot {:execution/id workflow-id
                                             :execution/workflow-id :canonical-sdlc
                                             :execution/workflow-version "1.0.0"
                                             :execution/status :running}
                           :manifest {:workflow/phases-completed [:plan]}
                           :phase-results {:plan {:status :completed}}}]
      (with-redefs [workflow/load-checkpoint-data (fn [_workflow-run-id] checkpoint-data)
                    es/read-workflow-events-by-id (fn [_events-dir _workflow-run-id] nil)]
        (let [ctx (core/reconstruct-context "/tmp/unused-events" workflow-id)]
          (is (= workflow-id (:workflow-id ctx)))
          (is (= (:machine-snapshot checkpoint-data) (:machine-snapshot ctx)))
          (is (= [:plan] (:completed-phases ctx)))
          (is (= {:status :completed}
                 (get-in ctx [:phase-results :plan])))
          (is (= 0 (:event-count ctx))))))))

;------------------------------------------------------------------------------ Layer 3
;; Interface re-exports

(deftest interface-reexports-test
  (testing "interface exposes the domain API"
    (is (= core/completed? wr/completed?))
    (is (= core/failed? wr/failed?))
    (is (= core/paused? wr/paused?))
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
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid reconstruct-context input"
             (core/reconstruct-context base-dir nil))))

      (testing "non-string/non-uuid workflow-id is rejected"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid reconstruct-context input"
             (core/reconstruct-context base-dir 42)))))))

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

(deftest trim-pipeline-validates-workflow-shape-test
  (testing "workflow without :workflow/pipeline is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid trim-pipeline input"
         (core/trim-pipeline {:wrong-shape true} []))))

  (testing "pipeline entries without :phase keyword are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid trim-pipeline input"
         (core/trim-pipeline {:workflow/pipeline [{:no-phase "here"}]} [])))))

(deftest resolve-workflow-identity-validates-fallback-fn-test
  (testing "non-function fallback is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid resolve-workflow-identity input"
         (core/resolve-workflow-identity
           {:workflow-spec {:name "x"}}
           "not a function")))))
