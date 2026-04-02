(ns ai.miniforge.agent.file-artifacts-test
  (:require
   [ai.miniforge.agent.file-artifacts :as sut]
   [clojure.java.io :as io]
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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.file-artifacts-test)

  :leave-this-here)
