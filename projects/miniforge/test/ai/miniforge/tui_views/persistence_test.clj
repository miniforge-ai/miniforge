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

(ns ai.miniforge.tui-views.persistence-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Helpers

(defn temp-events-dir
  "Create a temporary directory for test event files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "miniforge-test-events-" (System/nanoTime)))]
    (.mkdirs dir)
    dir))

(defn write-event-file!
  "Write EDN events to a file in the events directory."
  [dir workflow-id events]
  (let [file (io/file dir (str workflow-id ".edn"))]
    (with-open [w (io/writer file)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    file))

(defn cleanup-dir!
  "Remove temporary directory and all files."
  [dir]
  (doseq [f (.listFiles dir)]
    (.delete f))
  (.delete dir))

;------------------------------------------------------------------------------ Tests

(deftest load-workflows-empty-dir-test
  (testing "Returns empty vector for non-existent directory"
    (let [dir (io/file "/tmp/miniforge-nonexistent-dir-12345")]
      (is (= [] (persistence/load-workflows {:dir dir}))))))

(deftest load-workflows-empty-events-test
  (testing "Returns empty vector for empty events directory"
    (let [dir (temp-events-dir)]
      (try
        (is (= [] (persistence/load-workflows {:dir dir})))
        (finally (cleanup-dir! dir))))))

(deftest load-single-completed-workflow-test
  (testing "Loads a completed workflow from a single event file"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :message "Workflow started"
            :workflow/spec {:name "deploy-api"}}
           {:event/type :workflow/completed
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 5
            :workflow/id wf-id
            :message "Workflow success"
            :workflow/status :success}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 1 (count wfs)))
          (let [wf (first wfs)]
            (is (= wf-id (:id wf)))
            (is (= "deploy-api" (:name wf)))
            (is (= :success (:status wf)))
            (is (= 100 (:progress wf)))))
        (finally (cleanup-dir! dir))))))

(deftest load-in-progress-workflow-test
  (testing "Loads an in-progress workflow (no terminal event)"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :message "Workflow started"
            :workflow/spec {:name "build-infra"}}
           {:event/type :workflow/phase-started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 1
            :workflow/id wf-id
            :message "plan phase started"
            :workflow/phase :plan}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 1 (count wfs)))
          (let [wf (first wfs)]
            (is (= "build-infra" (:name wf)))
            (is (= :running (:status wf)))
            (is (= :plan (:phase wf)))
            (is (= 40 (:progress wf)))))
        (finally (cleanup-dir! dir))))))

(deftest load-failed-workflow-test
  (testing "Loads a failed workflow"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :message "Workflow started"
            :workflow/spec {:name "failing-wf"}}
           {:event/type :workflow/failed
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 2
            :workflow/id wf-id
            :message "Workflow failed"
            :error {:message "Out of tokens"}}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 1 (count wfs)))
          (is (= :failed (:status (first wfs)))))
        (finally (cleanup-dir! dir))))))

(deftest load-multiple-workflows-test
  (testing "Loads multiple workflows sorted by most recent first"
    (let [dir (temp-events-dir)
          wf1-id (java.util.UUID/randomUUID)
          wf2-id (java.util.UUID/randomUUID)
          wf3-id (java.util.UUID/randomUUID)
          old-ts (java.util.Date. (- (System/currentTimeMillis) 60000))
          mid-ts (java.util.Date. (- (System/currentTimeMillis) 30000))
          new-ts (java.util.Date.)]
      (try
        (write-event-file! dir wf1-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp old-ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf1-id
            :message "Workflow started" :workflow/spec {:name "oldest"}}])
        (write-event-file! dir wf2-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp new-ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf2-id
            :message "Workflow started" :workflow/spec {:name "newest"}}])
        (write-event-file! dir wf3-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp mid-ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf3-id
            :message "Workflow started" :workflow/spec {:name "middle"}}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 3 (count wfs)))
          ;; Newest first
          (is (= "newest" (:name (first wfs))))
          (is (= "middle" (:name (second wfs))))
          (is (= "oldest" (:name (nth wfs 2)))))
        (finally (cleanup-dir! dir))))))

(deftest load-workflows-limit-test
  (testing "Respects :limit option"
    (let [dir (temp-events-dir)
          ids (repeatedly 5 #(java.util.UUID/randomUUID))]
      (try
        (doseq [[i wf-id] (map-indexed vector ids)]
          (write-event-file! dir wf-id
            [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
              :event/timestamp (java.util.Date. (+ (System/currentTimeMillis) (* i 1000)))
              :event/version "1.0.0" :event/sequence-number 0
              :workflow/id wf-id :message "Workflow started"
              :workflow/spec {:name (str "wf-" i)}}])
          ;; Small delay to ensure distinct modification times
          (Thread/sleep 10))
        (let [wfs (persistence/load-workflows {:dir dir :limit 3})]
          (is (= 3 (count wfs))))
        (finally (cleanup-dir! dir))))))

(deftest load-workflows-into-model-test
  (testing "Populates model with loaded workflows"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf-id
            :message "Workflow started" :workflow/spec {:name "model-test"}}
           {:event/type :workflow/completed :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.) :event/version "1.0.0"
            :event/sequence-number 1 :workflow/id wf-id
            :message "Workflow success" :workflow/status :success}])
        (let [m (persistence/load-workflows-into-model (model/init-model) {:dir dir})]
          (is (= 1 (count (:workflows m))))
          (is (some? (:last-updated m)))
          (is (= "Loaded 1 workflows from disk" (:flash-message m))))
        (finally (cleanup-dir! dir))))))

(deftest load-workflows-into-model-empty-test
  (testing "Returns original model when no workflows found"
    (let [dir (temp-events-dir)]
      (try
        (let [init-m (model/init-model)
              m (persistence/load-workflows-into-model init-m {:dir dir})]
          (is (= [] (:workflows m)))
          (is (nil? (:last-updated m)))
          (is (nil? (:flash-message m))))
        (finally (cleanup-dir! dir))))))

(deftest load-workflow-no-spec-name-test
  (testing "Falls back to workflow-id prefix when no spec name"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf-id
            :message "Workflow started"}])
        (let [wfs (persistence/load-workflows {:dir dir})
              wf (first wfs)]
          (is (= 1 (count wfs)))
          (is (.startsWith (:name wf) "workflow-")))
        (finally (cleanup-dir! dir))))))

(deftest load-workflow-only-completed-event-test
  (testing "Handles files with only a completed event (no started event)"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/completed
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :message "Workflow success"
            :workflow/status :success}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 1 (count wfs)))
          (is (= :success (:status (first wfs))))
          (is (= 100 (:progress (first wfs)))))
        (finally (cleanup-dir! dir))))))

(deftest load-workflows-ignores-phase-only-files-test
  (testing "Phase-only event files are ignored in the top-level workflow list"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/phase-started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :workflow/phase :implement
            :message "implement phase started"}])
        (is (= [] (persistence/load-workflows {:dir dir})))
        (finally (cleanup-dir! dir))))))

(deftest load-workflow-detail-test
  (testing "Reconstructs phases, agent output, validation, and artifacts from the event file"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          artifact-id (java.util.UUID/randomUUID)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 0
            :workflow/id wf-id
            :workflow/spec {:name "detail-test"}
            :message "Workflow started"}
           {:event/type :workflow/phase-started
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 1
            :workflow/id wf-id
            :workflow/phase :implement
            :message "implement phase started"}
           {:event/type :agent/status
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 2
            :workflow/id wf-id
            :agent/id :implement
            :status/type :thinking
            :message "Thinking"}
           {:event/type :agent/chunk
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 3
            :workflow/id wf-id
            :agent/id :implement
            :chunk/delta "hello"}
           {:event/type :gate/failed
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 4
            :workflow/id wf-id
            :gate/id :lint
            :message "lint failed"}
           {:event/type :workflow/phase-completed
            :event/id (java.util.UUID/randomUUID)
            :event/timestamp (java.util.Date.)
            :event/version "1.0.0"
            :event/sequence-number 5
            :workflow/id wf-id
            :workflow/phase :implement
            :phase/outcome :success
            :phase/duration-ms 123
            :phase/artifacts [artifact-id]
            :message "implement phase success"}])
        (let [detail (persistence/load-workflow-detail wf-id {:dir dir})]
          (is (= wf-id (:workflow-id detail)))
          (is (= :implement (:current-phase detail)))
          (is (= "hello" (:agent-output detail)))
          (is (= :thinking (get-in detail [:current-agent :status])))
          (is (= 1 (count (:phases detail))))
          (is (= 1 (count (get-in detail [:evidence :validation :results]))))
          (is (= artifact-id (:id (first (:artifacts detail))))))
        (finally (cleanup-dir! dir))))))

(deftest load-corrupted-file-test
  (testing "Gracefully handles corrupted event files"
    (let [dir (temp-events-dir)
          file (io/file dir "bad-file.edn")]
      (try
        (spit file "this is not valid edn {{{")
        (let [wfs (persistence/load-workflows {:dir dir})]
          ;; Should return empty — corrupted file silently skipped
          (is (= 0 (count wfs))))
        (finally (cleanup-dir! dir))))))
