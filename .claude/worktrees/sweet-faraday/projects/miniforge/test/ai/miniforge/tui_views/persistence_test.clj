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
          (is (= "Loaded 1 workflows" (:flash-message m))))
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
  (testing "Workflows without a spec name are excluded from top-level list
            (quick-named-workflow? pre-filter rejects anonymous workflows)"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (write-event-file! dir wf-id
          [{:event/type :workflow/started :event/id (java.util.UUID/randomUUID)
            :event/timestamp ts :event/version "1.0.0"
            :event/sequence-number 0 :workflow/id wf-id
            :message "Workflow started"}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          ;; Anonymous workflows (no spec name) are filtered out
          (is (= 0 (count wfs))))
        (finally (cleanup-dir! dir)))))

  (testing "workflow-name falls back to id prefix when no spec name"
    (let [wf-id (java.util.UUID/randomUUID)
          events [{:event/type :workflow/started :workflow/id wf-id}]]
      (is (.startsWith (persistence/workflow-name wf-id events) "workflow-")))))

(deftest load-workflow-only-completed-event-test
  (testing "Files with only a completed event (no started) are excluded —
            quick-named-workflow? requires a :workflow/started with a named spec"
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
          (is (= 0 (count wfs)) "no started event means filtered out"))
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

(deftest stale-workflow-detection-test
  (testing "Non-terminal workflow with old file is marked :stale"
    (let [dir (temp-events-dir)
          wf-id (java.util.UUID/randomUUID)
          ts (java.util.Date.)]
      (try
        (let [file (write-event-file! dir wf-id
                     [{:event/type :workflow/started
                       :event/id (java.util.UUID/randomUUID)
                       :event/timestamp ts
                       :event/version "1.0.0"
                       :event/sequence-number 0
                       :workflow/id wf-id
                       :workflow/spec {:name "stale-test"}}])]
          ;; Set file modification time to 2 hours ago
          (.setLastModified file (- (System/currentTimeMillis) (* 2 60 60 1000)))
          (let [wfs (persistence/load-workflows {:dir dir})]
            (is (= 1 (count wfs)))
            (is (= :stale (:status (first wfs))))))
        (finally (cleanup-dir! dir)))))

  (testing "Non-terminal workflow with recent file stays :running"
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
            :workflow/spec {:name "fresh-test"}}])
        (let [wfs (persistence/load-workflows {:dir dir})]
          (is (= 1 (count wfs)))
          (is (= :running (:status (first wfs)))))
        (finally (cleanup-dir! dir))))))

(deftest normalize-artifact-type-inference-test
  (testing "Infers :code type from :code/files key"
    (let [a (persistence/normalize-artifact {:code/files ["src/foo.clj"]} :implement)]
      (is (= :code (:type a)))
      (is (= :implement (:phase a)))
      (is (string? (:name a)))))

  (testing "Infers :plan type from :plan/tasks key"
    (let [a (persistence/normalize-artifact {:plan/tasks [{:id :t1}]} :plan)]
      (is (= :plan (:type a)))))

  (testing "Infers :review type from :review/id key"
    (let [a (persistence/normalize-artifact {:review/id :r1} :review)]
      (is (= :review (:type a)))))

  (testing "Preserves explicit :type when set"
    (let [a (persistence/normalize-artifact {:type :custom :name "my-art"} :build)]
      (is (= :custom (:type a)))
      (is (= "my-art" (:name a)))))

  (testing "Non-map artifact gets :unknown type"
    (let [a (persistence/normalize-artifact (java.util.UUID/randomUUID) :implement)]
      (is (= :unknown (:type a))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Token and cost tracking

(deftest metrics-accumulation-test
  (testing "Phase-completed events accumulate tokens and cost"
    (let [wf-id (random-uuid)
          events [(persistence/workflow-started-event wf-id {:name "test"})
                  (persistence/phase-started-event wf-id :plan)
                  (persistence/phase-completed-event wf-id :plan :success nil 5000
                                                      {:tokens 1200 :cost-usd 0.05})
                  (persistence/phase-started-event wf-id :implement)
                  (persistence/phase-completed-event wf-id :implement :success nil 10000
                                                      {:tokens 3800 :cost-usd 0.12})]
          detail (persistence/detail-from-events wf-id events)]
      (is (= 5000 (:tokens detail)))
      (is (< (abs (- 0.17 (:cost-usd detail))) 0.001))
      ;; Per-phase metrics
      (let [plan-phase (first (filter #(= :plan (:phase %)) (:phases detail)))
            impl-phase (first (filter #(= :implement (:phase %)) (:phases detail)))]
        (is (= 1200 (:tokens plan-phase)))
        (is (= 0.05 (:cost-usd plan-phase)))
        (is (= 3800 (:tokens impl-phase)))
        (is (= 0.12 (:cost-usd impl-phase))))))

  (testing "Workflow-completed overrides totals when present"
    (let [wf-id (random-uuid)
          events [(persistence/workflow-started-event wf-id {:name "test"})
                  (persistence/phase-completed-event wf-id :plan :success nil 5000
                                                      {:tokens 1200 :cost-usd 0.05})
                  (persistence/workflow-completed-event wf-id :success 15000 nil
                                                         {:tokens 5000 :cost-usd 0.20})]
          detail (persistence/detail-from-events wf-id events)]
      (is (= 5000 (:tokens detail)))
      (is (= 0.20 (:cost-usd detail)))))

  (testing "Zero defaults when no metrics present"
    (let [wf-id (random-uuid)
          events [(persistence/workflow-started-event wf-id {:name "test"})
                  (persistence/phase-completed-event wf-id :plan :success nil 5000)]
          detail (persistence/detail-from-events wf-id events)]
      (is (= 0 (:tokens detail)))
      (is (= 0.0 (:cost-usd detail))))))
