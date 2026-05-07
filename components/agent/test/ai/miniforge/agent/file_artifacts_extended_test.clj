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

(ns ai.miniforge.agent.file-artifacts-extended-test
  "Extended tests for file-artifacts: porcelain parsing edge cases, rename/copy
   status codes, changed-paths filtering, empty snapshots, and manifest handling."
  (:require
   [ai.miniforge.agent.file-artifacts :as sut]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.java.io :as io]
   [clojure.java.shell]
   [clojure.string]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "file-artifacts-ext-test-"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree! [dir]
  (doseq [file (reverse (file-seq (io/file dir)))]
    (.delete ^java.io.File file)))

(defn- write-file! [dir path content]
  (let [file (io/file dir path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- make-snapshot
  [& {:keys [untracked modified deleted added]
      :or {untracked #{} modified #{} deleted #{} added #{}}}]
  {:untracked untracked :modified modified :deleted deleted :added added})

;------------------------------------------------------------------------------ Layer 1
;; empty-snapshot tests

(deftest empty-snapshot-test
  (testing "returns map with four empty sets"
    (let [snap (sut/empty-snapshot)]
      (is (= #{} (:untracked snap)))
      (is (= #{} (:modified snap)))
      (is (= #{} (:deleted snap)))
      (is (= #{} (:added snap))))))

;------------------------------------------------------------------------------ Layer 1
;; Porcelain status parsing (via snapshot-working-dir)

(deftest snapshot-untracked-files-test
  (testing "?? status maps to :untracked"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "?? new.txt\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"new.txt"} (:untracked snap)))
        (is (= #{} (:modified snap)))))))

(deftest snapshot-added-files-test
  (testing "A_ status maps to :added"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "A  staged.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"staged.clj"} (:added snap)))))))

(deftest snapshot-deleted-files-test
  (testing "D_ status maps to :deleted"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "D  gone.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"gone.clj"} (:deleted snap)))))))

(deftest snapshot-renamed-files-test
  (testing "R_ status with -> in path maps to :modified with target path"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "R  old.clj -> new.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"new.clj"} (:modified snap)))))))

(deftest snapshot-copied-files-test
  (testing "C_ status maps to :modified"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "C  src.clj -> dst.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"dst.clj"} (:modified snap)))))))

(deftest snapshot-type-change-files-test
  (testing "T_ status maps to :modified"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "T  symlink.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"symlink.clj"} (:modified snap)))))))

(deftest snapshot-index-modified-test
  (testing "_M status (working tree modified) maps to :modified"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out " M changed.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"changed.clj"} (:modified snap)))))))

(deftest snapshot-index-deleted-test
  (testing "_D status (working tree deleted) maps to :deleted"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out " D removed.clj\n" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"removed.clj"} (:deleted snap)))))))

(deftest snapshot-mixed-statuses-test
  (testing "handles multiple mixed status lines"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0
                              :out (str "?? new1.clj\n"
                                        "?? new2.clj\n"
                                        " M mod1.clj\n"
                                        "M  mod2.clj\n"
                                        "D  del1.clj\n"
                                        "A  add1.clj\n"
                                        "R  old.clj -> renamed.clj\n")
                              :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= #{"new1.clj" "new2.clj"} (:untracked snap)))
        (is (contains? (:modified snap) "mod1.clj"))
        (is (contains? (:modified snap) "mod2.clj"))
        (is (contains? (:modified snap) "renamed.clj"))
        (is (= #{"del1.clj"} (:deleted snap)))
        (is (= #{"add1.clj"} (:added snap)))))))

(deftest snapshot-empty-output-test
  (testing "empty git output produces empty snapshot"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0 :out "" :err ""})]
      (let [snap (sut/snapshot-working-dir "/tmp")]
        (is (= (sut/empty-snapshot) snap))))))

(deftest snapshot-git-failure-returns-anomaly-test
  (testing "non-zero exit returns a :fault anomaly (not a throw)"
    ;; Migrated from thrown-test under the exceptions-as-data cleanup:
    ;; snapshot-working-dir is now anomaly-returning so the surrounding
    ;; review-phase pipeline never has to wrap it in try/catch.
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 128 :out "" :err "not a git repo"})]
      (let [result (sut/snapshot-working-dir "/tmp")]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result)))
        (let [data (:anomaly/data result)]
          (is (= "/tmp" (:working-dir data)))
          (is (= 128 (:exit data)))
          (is (= "not a git repo" (:stderr data))))))))

;------------------------------------------------------------------------------ Layer 2
;; changed-paths filtering

(deftest collect-excludes-pre-dirty-files-test
  (testing "files dirty before session start are excluded"
    (let [dir (temp-dir)
          pre (make-snapshot :modified #{"src/already.clj"})]
      (try
        (write-file! dir "src/new.clj" "(ns new)")
        (write-file! dir "src/already.clj" "(ns already)")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot
                         :untracked #{"src/new.clj"}
                         :modified #{"src/already.clj"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (= 1 (count (:code/files result))))
            (is (= "src/new.clj" (:path (first (:code/files result)))))))
        (finally (delete-tree! dir))))))

(deftest collect-excludes-pre-untracked-files-test
  (testing "files untracked before session start are excluded"
    (let [dir (temp-dir)
          pre (make-snapshot :untracked #{"junk.txt"})]
      (try
        (write-file! dir "junk.txt" "unchanged")
        (write-file! dir "real.clj" "(ns real)")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot :untracked #{"junk.txt" "real.clj"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (= 1 (count (:code/files result))))
            (is (= "real.clj" (:path (first (:code/files result)))))))
        (finally (delete-tree! dir))))))

(deftest collect-staged-additions-as-create-test
  (testing "newly staged files (A status) produce :create actions"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (write-file! dir "src/staged.clj" "(ns staged)")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot :added #{"src/staged.clj"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (= 1 (count (:code/files result))))
            (is (= :create (:action (first (:code/files result)))))))
        (finally (delete-tree! dir))))))

(deftest collect-deleted-files-have-empty-content-test
  (testing "deleted files have empty string content"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot :deleted #{"src/removed.clj"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (= 1 (count (:code/files result))))
            (is (= :delete (:action (first (:code/files result)))))
            (is (= "" (:content (first (:code/files result)))))))
        (finally (delete-tree! dir))))))

(deftest collect-excludes-manifest-from-files-test
  (testing "miniforge-artifact.edn is excluded from file list"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (write-file! dir "src/real.clj" "(ns real)")
        (write-file! dir "miniforge-artifact.edn"
                     "{:code/summary \"test\"}")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot
                         :untracked #{"src/real.clj" "miniforge-artifact.edn"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (= 1 (count (:code/files result))))
            (is (= "src/real.clj" (:path (first (:code/files result)))))))
        (finally (delete-tree! dir))))))

(deftest collect-nil-pre-snapshot-returns-nil-test
  (testing "nil pre-snapshot returns nil"
    (is (nil? (sut/collect-written-files nil "/tmp")))))

(deftest collect-files-sorted-by-path-test
  (testing "files are sorted alphabetically by path"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (write-file! dir "z_file.clj" "(ns z)")
        (write-file! dir "a_file.clj" "(ns a)")
        (write-file! dir "m_file.clj" "(ns m)")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot
                         :untracked #{"z_file.clj" "a_file.clj" "m_file.clj"}))]
          (let [result (sut/collect-written-files pre dir)
                paths (mapv :path (:code/files result))]
            (is (= ["a_file.clj" "m_file.clj" "z_file.clj"] paths))))
        (finally (delete-tree! dir))))))

(deftest collect-summary-mentions-file-count-test
  (testing "summary includes file count"
    (let [dir (temp-dir)
          pre (make-snapshot)]
      (try
        (write-file! dir "a.clj" "(ns a)")
        (write-file! dir "b.clj" "(ns b)")
        (with-redefs [sut/snapshot-working-dir
                      (fn [_]
                        (make-snapshot :untracked #{"a.clj" "b.clj"}))]
          (let [result (sut/collect-written-files pre dir)]
            (is (clojure.string/includes? (:code/summary result) "2 files"))))
        (finally (delete-tree! dir))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.file-artifacts-extended-test)
  :leave-this-here)
