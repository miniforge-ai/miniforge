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
(ns ai.miniforge.release-executor.files-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.release-executor.files :as sut]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} process-file-action-create-writes-inside-worktree
  (testing "valid create actions still write files and return success"
    (let [worktree (fs/create-temp-dir {:prefix "release-files-create-"})]
      (try
        (let [result (sut/process-file-action worktree
                                              {:action :create
                                               :path "src/app.clj"
                                               :content "(ns app)"}
                                              nil)]
          (is (= {:success? true :action :create :path "src/app.clj"} result))
          (is (= "(ns app)" (slurp (str (fs/path worktree "src/app.clj"))))))
        (finally
          (fs/delete-tree worktree))))))

(deftest ^{:stratum 0} process-file-action-create-rejects-path-traversal-as-data
  (testing "escaped create paths return a failed result with anomaly data"
    (let [worktree (fs/create-temp-dir {:prefix "release-files-create-escape-"})]
      (try
        (let [result (sut/process-file-action worktree
                                              {:action :create
                                               :path "../outside.clj"
                                               :content "escape"}
                                              nil)]
          (is (false? (:success? result)))
          (is (= :create (:action result)))
          (is (= "../outside.clj" (:path result)))
          (is (= "Path traversal rejected: candidate path escapes worktree root"
                 (:error result)))
          (is (anomaly/anomaly? (:anomaly result)))
          (is (= :invalid-input (-> result :anomaly :anomaly/type))))
        (finally
          (fs/delete-tree worktree))))))

(deftest ^{:stratum 0} process-file-action-create-rejects-symlink-escape-as-data
  (testing "worktree-local symlinks cannot escape the canonical worktree root"
    (let [worktree (fs/create-temp-dir {:prefix "release-files-symlink-worktree-"})
          outside (fs/create-temp-dir {:prefix "release-files-symlink-outside-"})]
      (try
        (fs/create-sym-link (fs/path worktree "link") outside)
        (let [result (sut/process-file-action worktree
                                              {:action :create
                                               :path "link/outside.clj"
                                               :content "escape"}
                                              nil)]
          (is (false? (:success? result)))
          (is (= :create (:action result)))
          (is (= :invalid-input (-> result :anomaly :anomaly/type)))
          (is (= (str (fs/path (fs/canonicalize outside) "outside.clj"))
                 (-> result :anomaly :anomaly/data :normalized))))
        (finally
          (fs/delete-tree worktree)
          (fs/delete-tree outside))))))

(deftest ^{:stratum 0} process-file-action-modify-rejects-path-traversal-as-data
  (testing "escaped modify paths return a failed result with anomaly data"
    (let [worktree (fs/create-temp-dir {:prefix "release-files-modify-escape-"})]
      (try
        (let [result (sut/process-file-action worktree
                                              {:action :modify
                                               :path "../outside.clj"
                                               :content "escape"}
                                              nil)]
          (is (false? (:success? result)))
          (is (= :modify (:action result)))
          (is (= :invalid-input (-> result :anomaly :anomaly/type))))
        (finally
          (fs/delete-tree worktree))))))

(deftest ^{:stratum 0} process-file-action-delete-rejects-path-traversal-as-data
  (testing "escaped delete paths return a failed result with anomaly data"
    (let [worktree (fs/create-temp-dir {:prefix "release-files-delete-escape-"})]
      (try
        (let [result (sut/process-file-action worktree
                                              {:action :delete
                                               :path "../outside.clj"}
                                              nil)]
          (is (false? (:success? result)))
          (is (= :delete (:action result)))
          (is (= :invalid-input (-> result :anomaly :anomaly/type))))
        (finally
          (fs/delete-tree worktree))))))
