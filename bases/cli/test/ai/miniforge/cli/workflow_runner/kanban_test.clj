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

(ns ai.miniforge.cli.workflow-runner.kanban-test
  "Tests for work spec kanban lifecycle — provenance tracking and file moves."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.cli.workflow-runner :as sut]))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:dynamic *test-dir* nil)

(defn with-temp-work-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "kanban-test-"}))]
    (binding [*test-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each with-temp-work-dir)

(defn create-work-spec!
  "Create a dummy spec file at work/<name> inside the test dir."
  [name]
  (let [work-dir (str *test-dir* "/work")
        path (str work-dir "/" name)]
    (fs/create-dirs work-dir)
    (spit path "{:spec/title \"test\"}")
    path))

(defmacro with-test-kanban
  "Redirect kanban dirs and work-spec? predicate to use the temp test dir."
  [& body]
  `(let [prefix# (str *test-dir* "/work/")]
     (with-redefs [sut/work-dirs {:in-progress (str *test-dir* "/work/in-progress")
                                  :done (str *test-dir* "/work/done")
                                  :failed (str *test-dir* "/work/failed")}
                   sut/work-spec? (fn [provenance#]
                                    (when-let [src# (:source-file provenance#)]
                                      (str/starts-with? (str src#) prefix#)))]
       ~@body)))

;; ============================================================================
;; move-spec-to-in-progress! returns updated provenance
;; ============================================================================

(deftest move-spec-to-in-progress-returns-updated-provenance-test
  (testing "provenance source-file is updated to the new in-progress path"
    (let [spec-path (create-work-spec! "test.spec.edn")
          provenance {:source-file spec-path}]
      (with-test-kanban
        (let [updated (sut/move-spec-to-in-progress! provenance)]
          (is (= (str *test-dir* "/work/in-progress/test.spec.edn")
                 (:source-file updated)))
          (is (fs/exists? (:source-file updated)))
          (is (not (fs/exists? spec-path))))))))

(deftest move-spec-on-completion-uses-updated-provenance-test
  (testing "after move-to-in-progress, move-on-completion finds the file"
    (let [spec-path (create-work-spec! "lifecycle.spec.edn")
          provenance {:source-file spec-path}]
      (with-test-kanban
        (let [updated (sut/move-spec-to-in-progress! provenance)]
          ;; Simulate successful completion
          (sut/move-spec-on-completion! updated {:execution/status :completed})
          (is (fs/exists? (str *test-dir* "/work/done/lifecycle.spec.edn")))
          (is (not (fs/exists? (:source-file updated)))))))))

(deftest move-spec-on-failure-uses-updated-provenance-test
  (testing "failed workflow moves spec to failed directory"
    (let [spec-path (create-work-spec! "broken.spec.edn")
          provenance {:source-file spec-path}]
      (with-test-kanban
        (let [updated (sut/move-spec-to-in-progress! provenance)]
          (sut/move-spec-on-completion! updated {:execution/status :failed})
          (is (fs/exists? (str *test-dir* "/work/failed/broken.spec.edn")))
          (is (not (fs/exists? (:source-file updated)))))))))

(deftest move-spec-to-in-progress-non-work-spec-test
  (testing "non-work spec provenance is returned unchanged"
    (let [provenance {:source-file "/tmp/not-a-work-spec.edn"}]
      (is (= provenance (sut/move-spec-to-in-progress! provenance))))))

;; ============================================================================
;; failure-message
;; ============================================================================

(deftest failure-message-with-errors-test
  (testing "uses first error entry when errors exist"
    (let [result {:execution/errors [{:type :gate-failed :message "lint failed"}]
                  :execution/status :failed}
          msg (#'sut/failure-message result)]
      (is (str/includes? msg "lint failed")))))

(deftest failure-message-without-errors-test
  (testing "includes execution status when no errors"
    (let [result {:execution/errors []
                  :execution/status :failed}
          msg (#'sut/failure-message result)]
      (is (str/includes? msg "failed")))))

(deftest failure-message-nil-errors-test
  (testing "handles nil errors gracefully"
    (let [result {:execution/status :timeout}
          msg (#'sut/failure-message result)]
      (is (str/includes? msg "timeout")))))
