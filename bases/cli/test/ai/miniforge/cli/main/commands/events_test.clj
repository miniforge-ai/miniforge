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

(ns ai.miniforge.cli.main.commands.events-test
  "Unit / integration tests for the `events show` CLI command.

   Strategy:
   - events-show (pure core) tested with synthetic event vectors.
   - events-show-cmd (CLI handler) tested via with-redefs on reader/sinks so
     no real filesystem or transit-JSON encoding is required.
   - exit! is always stubbed to prevent JVM termination during tests."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.cli.main.commands.events :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.reader :as reader]))

;------------------------------------------------------------------------------ Fixtures & factories

(def ^:dynamic *tmp-dir* nil)

(defn tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "events-test-"}))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each tmp-dir-fixture)

;; Synthetic event vectors used across tests.

(defn- make-started-event
  "Minimal `:workflow/started` event."
  [& {:as overrides}]
  (merge {:event/type      :workflow/started
          :event/timestamp "2026-05-20T10:00:00.000Z"
          :workflow/id     "test-wf-abc123"
          :workflow/phase  :plan}
         overrides))

(defn- make-completed-event
  "Minimal `:workflow/completed` event."
  [& {:as overrides}]
  (merge {:event/type      :workflow/completed
          :event/timestamp "2026-05-20T10:01:00.000Z"
          :workflow/id     "test-wf-abc123"
          :workflow/phase  :release}
         overrides))

(def ^:private synthetic-events
  [(make-started-event)
   (make-completed-event)])

(def ^:private nonexistent-base-dir
  "/nonexistent/events/dir/that/does/not/exist")

;------------------------------------------------------------------------------ Layer 0: events-show pure fn

(deftest events-show-returns-ok-for-valid-events
  (testing "returns :ok status with non-blank output for a normal event sequence"
    (let [base-dir (java.io.File. *tmp-dir*)
          result   (with-redefs [reader/read-workflow-events-by-id
                                 (fn [_ _] synthetic-events)]
                     (sut/events-show base-dir "test-wf-abc123" {}))]
      (is (= :ok (:status result)))
      (is (string? (:output result)))
      (is (not (str/blank? (:output result)))))))

(deftest events-show-errors-when-base-dir-missing
  (testing "returns :error when the events base directory does not exist"
    (let [base-dir (java.io.File. nonexistent-base-dir)
          result   (sut/events-show base-dir "any-wf-id" {})]
      (is (= :error (:status result)))
      (is (pos-int? (:exit-code result)))
      (is (str/includes? (:message result) "not found")))))

(deftest events-show-errors-when-workflow-not-found
  (testing "returns :error when reader returns nil (workflow-id unknown)"
    (let [base-dir (java.io.File. *tmp-dir*)
          result   (with-redefs [reader/read-workflow-events-by-id (fn [_ _] nil)]
                     (sut/events-show base-dir "ghost-wf-id" {}))]
      (is (= :error (:status result)))
      (is (pos-int? (:exit-code result)))
      (is (str/includes? (:message result) "ghost-wf-id")))))

(deftest events-show-raw-mode-dumps-edn
  (testing ":raw true produces EDN output containing the event type keyword"
    (let [base-dir (java.io.File. *tmp-dir*)
          result   (with-redefs [reader/read-workflow-events-by-id
                                 (fn [_ _] synthetic-events)]
                     (sut/events-show base-dir "test-wf-abc123" {:raw true}))]
      (is (= :ok (:status result)))
      (is (str/includes? (:output result) "workflow/started"))
      (is (str/includes? (:output result) "workflow/completed")))))

(deftest events-show-custom-gap-threshold
  (testing "custom gap-threshold-secs is passed through without error"
    (let [base-dir (java.io.File. *tmp-dir*)
          result   (with-redefs [reader/read-workflow-events-by-id
                                 (fn [_ _] synthetic-events)]
                     (sut/events-show base-dir "test-wf-abc123" {:gap-threshold 5}))]
      (is (= :ok (:status result))))))

;------------------------------------------------------------------------------ Layer 1: events-show-cmd CLI handler

(deftest events-show-cmd-happy-path-prints-timeline
  (testing "prints rendered timeline to stdout when events exist"
    (let [exit-code  (atom nil)
          output     (with-redefs [sinks/default-events-dir
                                   (fn [] (java.io.File. *tmp-dir*))
                                   reader/read-workflow-events-by-id
                                   (fn [_ _] synthetic-events)
                                   shared/exit!
                                   (fn [code] (reset! exit-code code))]
                       (with-out-str
                         (sut/events-show-cmd {:workflow-id   "test-wf-abc123"
                                               :gap-threshold 60
                                               :raw           false})))]
      (is (nil? @exit-code) "exit! must not be called on success")
      (is (not (str/blank? output))))))

(deftest events-show-cmd-missing-workflow-id-exits-1
  (testing "exits 1 and prints usage when workflow-id is absent"
    (let [exit-code (atom nil)]
      (with-redefs [shared/exit! (fn [code] (reset! exit-code code))]
        (with-out-str
          (sut/events-show-cmd {:workflow-id nil})))
      (is (= 1 @exit-code)))))

(deftest events-show-cmd-blank-workflow-id-exits-1
  (testing "exits 1 when workflow-id is an empty string"
    (let [exit-code (atom nil)]
      (with-redefs [shared/exit! (fn [code] (reset! exit-code code))]
        (with-out-str
          (sut/events-show-cmd {:workflow-id ""})))
      (is (= 1 @exit-code)))))

(deftest events-show-cmd-workflow-not-found-exits-1
  (testing "exits 1 when reader returns nil (workflow-id not in any layout)"
    (let [exit-code (atom nil)]
      (with-redefs [sinks/default-events-dir
                    (fn [] (java.io.File. *tmp-dir*))
                    reader/read-workflow-events-by-id
                    (fn [_ _] nil)
                    shared/exit!
                    (fn [code] (reset! exit-code code))]
        (with-out-str
          (sut/events-show-cmd {:workflow-id "ghost-wf-id"})))
      (is (= 1 @exit-code)))))

(deftest events-show-cmd-events-dir-missing-exits-1
  (testing "exits 1 when the events directory itself does not exist"
    (let [exit-code (atom nil)]
      (with-redefs [sinks/default-events-dir
                    (fn [] (java.io.File. nonexistent-base-dir))
                    shared/exit!
                    (fn [code] (reset! exit-code code))]
        (with-out-str
          (sut/events-show-cmd {:workflow-id "any-wf-id"})))
      (is (= 1 @exit-code)))))

(deftest events-show-cmd-raw-flag-dumps-edn
  (testing "--raw dumps parsed events as EDN including keyword names"
    (let [exit-code (atom nil)
          output    (with-redefs [sinks/default-events-dir
                                  (fn [] (java.io.File. *tmp-dir*))
                                  reader/read-workflow-events-by-id
                                  (fn [_ _] synthetic-events)
                                  shared/exit!
                                  (fn [code] (reset! exit-code code))]
                      (with-out-str
                        (sut/events-show-cmd {:workflow-id   "test-wf-abc123"
                                              :raw           true})))]
      (is (nil? @exit-code) "exit! must not be called on success")
      (is (str/includes? output "workflow/started"))
      (is (str/includes? output "workflow/completed")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run all tests in this namespace
  (clojure.test/run-tests 'ai.miniforge.cli.main.commands.events-test)

  :end)
