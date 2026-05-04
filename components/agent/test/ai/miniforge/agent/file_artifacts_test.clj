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

(ns ai.miniforge.agent.file-artifacts-test
  (:require
   [ai.miniforge.agent.file-artifacts :as sut]
   [clojure.java.io :as io]
   [clojure.java.shell]
   [clojure.test :as test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn- temp-dir
  "Create a temp directory for file artifact tests."
  []
  (str (java.nio.file.Files/createTempDirectory
        "file-artifacts-test-"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree!
  "Delete a temp directory recursively."
  [dir]
  (doseq [file (reverse (file-seq (io/file dir)))]
    (.delete ^java.io.File file)))

(defn- write-file!
  "Write a file relative to the temp working directory."
  [dir path content]
  (let [file (io/file dir path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- make-snapshot
  "Create a snapshot map for tests."
  [& {:keys [untracked modified deleted added]
      :or {untracked #{}
           modified #{}
           deleted #{}
           added #{}}}]
  {:untracked untracked
   :modified modified
   :deleted deleted
   :added added})

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest snapshot-working-dir-test
  (testing "parses git porcelain output into working tree buckets"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _]
                    {:exit 0
                     :out (str "?? src/new_file.clj\n"
                               " M src/changed.clj\n"
                               "D  src/deleted.clj\n"
                               "A  src/staged_new.clj\n")
                     :err ""})]
      (is (= {:untracked #{"src/new_file.clj"}
              :modified #{"src/changed.clj"}
              :deleted #{"src/deleted.clj"}
              :added #{"src/staged_new.clj"}}
             (sut/snapshot-working-dir "/tmp/work"))))))

(deftest collect-written-files-test
  (testing "collects only files attributable to the current session"
    (let [dir (temp-dir)
          pre (make-snapshot :modified #{"src/already_dirty.clj"})]
      (try
        (write-file! dir "src/new_file.clj" "(ns new-file)")
        (write-file! dir "src/changed.clj" "(ns changed)")
        (write-file! dir "src/already_dirty.clj" "(ns keep-out)")
        (with-redefs [ai.miniforge.agent.file-artifacts/snapshot-working-dir
                      (fn [_]
                        (make-snapshot
                         :untracked #{"src/new_file.clj"}
                         :modified #{"src/changed.clj" "src/already_dirty.clj"}
                         :deleted #{"src/deleted.clj"}))]
          (is (= {:code/files [{:path "src/new_file.clj"
                                :content "(ns new-file)"
                                :action :create}
                               {:path "src/changed.clj"
                                :content "(ns changed)"
                                :action :modify}
                               {:path "src/deleted.clj"
                                :content ""
                                :action :delete}]
                  :code/summary "3 files collected from agent working directory (no MCP submit)"
                  :code/language "clojure"
                  :code/tests-needed? true}
                 (sut/collect-written-files pre dir))))
        (finally
          (delete-tree! dir)))))

  (testing "returns nil when nothing changed"
    (let [pre (make-snapshot :modified #{"src/already_dirty.clj"})]
      (with-redefs [ai.miniforge.agent.file-artifacts/snapshot-working-dir
                    (fn [_] pre)]
        (is (nil? (sut/collect-written-files pre "/tmp/work"))))))

  (testing "returns nil when the working tree cannot be snapshotted"
    (let [pre (make-snapshot)]
      (with-redefs [ai.miniforge.agent.file-artifacts/snapshot-working-dir
                    (fn [_]
                      (throw (ex-info "git unavailable" {})))]
        (is (nil? (sut/collect-written-files pre "/tmp/work"))))))

  (testing "merges optional artifact manifest metadata"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (write-file! dir "src/new_file.clj" "(ns new-file)")
        (write-file! dir "miniforge-artifact.edn"
                     "{:code/summary \"Collected from manifest\"\n :code/tests-needed? false}")
        (with-redefs [ai.miniforge.agent.file-artifacts/snapshot-working-dir
                      (fn [_]
                        (make-snapshot
                         :untracked #{"src/new_file.clj" "miniforge-artifact.edn"}))]
          (is (= {:code/files [{:path "src/new_file.clj"
                                :content "(ns new-file)"
                                :action :create}]
                  :code/summary "Collected from manifest"
                  :code/language "clojure"
                  :code/tests-needed? false}
                 (sut/collect-written-files pre dir))))
        (finally
          (delete-tree! dir))))))

(deftest rehydrate-files-test
  (testing "reads file content from disk for each recorded path"
    (let [dir (temp-dir)]
      (try
        (write-file! dir "src/new.clj" "(ns new)")
        (write-file! dir "src/changed.clj" "(ns changed)")
        (is (= [{:path "src/new.clj"     :content "(ns new)"     :action :create}
                {:path "src/changed.clj" :content "(ns changed)" :action :modify}]
               (sut/rehydrate-files dir
                                    ["src/new.clj" "src/changed.clj"]
                                    [:create :modify])))
        (finally
          (delete-tree! dir)))))

  (testing "delete actions emit empty content without touching disk"
    (let [dir (temp-dir)]
      (try
        (is (= [{:path "src/gone.clj" :content "" :action :delete}]
               (sut/rehydrate-files dir ["src/gone.clj"] [:delete])))
        (finally
          (delete-tree! dir)))))

  (testing "paths absent from disk are skipped rather than invented"
    (let [dir (temp-dir)]
      (try
        (write-file! dir "src/here.clj" "(ns here)")
        (is (= [{:path "src/here.clj" :content "(ns here)" :action :modify}]
               (sut/rehydrate-files dir
                                    ["src/here.clj" "src/missing.clj"]
                                    [:modify :modify])))
        (finally
          (delete-tree! dir)))))

  (testing "default action is :modify when no actions are supplied"
    (let [dir (temp-dir)]
      (try
        (write-file! dir "src/x.clj" "(ns x)")
        (is (= [{:path "src/x.clj" :content "(ns x)" :action :modify}]
               (sut/rehydrate-files dir ["src/x.clj"])))
        (finally
          (delete-tree! dir)))))

  (testing "returns nil for nil working-dir"
    (is (nil? (sut/rehydrate-files nil ["src/x.clj"] [:modify]))))

  (testing "returns empty vector when no recorded path is present on disk"
    (let [dir (temp-dir)]
      (try
        (is (= [] (sut/rehydrate-files dir ["src/missing.clj"] [:modify])))
        (finally
          (delete-tree! dir))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.file-artifacts-test)

  :leave-this-here)
