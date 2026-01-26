(ns ai.miniforge.workflow.release-test
  "Unit tests for release helpers that do not require git."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.release :as release]
   [babashka.fs :as fs]))

(def ^:dynamic *temp-dirs* (atom []))

(defn create-temp-dir
  []
  (let [dir (fs/create-temp-dir {:prefix "release-unit-"})]
    (swap! *temp-dirs* conj dir)
    dir))

(defn cleanup-temp-dirs
  [f]
  (reset! *temp-dirs* [])
  (try
    (f)
    (finally
      (doseq [dir @*temp-dirs*]
        (when (fs/exists? dir)
          (fs/delete-tree dir))))))

(use-fixtures :each cleanup-temp-dirs)

(deftest ensure-parent-dir-test
  (testing "ensure-parent-dir! creates nested directories"
    (let [temp-dir (create-temp-dir)
          test-path (fs/path temp-dir "foo" "bar" "baz.clj")]
      (release/ensure-parent-dir! test-path)
      (is (fs/exists? (fs/path temp-dir "foo" "bar"))))))

(deftest write-file-test
  (testing "write-file! creates parent directories"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "src" "example.clj")
          content "(ns example)\n(defn hello [] \"world\")"]
      (release/write-file! file-path content)
      (is (fs/exists? file-path))
      (is (= content (slurp (str file-path)))))))

(deftest delete-file-test
  (testing "delete-file! removes existing files"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "delete-me.clj")]
      (spit (str file-path) "temp")
      (is (true? (release/delete-file! file-path)))
      (is (false? (fs/exists? file-path)))))

  (testing "delete-file! returns false for missing files"
    (let [temp-dir (create-temp-dir)
          file-path (fs/path temp-dir "missing.clj")]
      (is (false? (release/delete-file! file-path))))))
