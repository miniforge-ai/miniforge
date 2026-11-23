;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns storage-peer-runtime-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storage-peer-runtime :as runtime])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-peer-classpath-binds-relative-entries-to-the-product-not-the-task
  (let [workspace (fs/cwd)
        absolute (str (fs/path workspace "installed"))
        relative "classes directory"
        expected (.getCanonicalPath ^File (fs/file workspace "projects/miniforge" relative))
        output (str relative File/pathSeparator absolute "\n")]
    (with-redefs [process/shell (constantly {:exit 0
                                            :out output})]
      (is (= (str/join File/pathSeparator [expected absolute]) (runtime/classpath! workspace))))))

(comment (clojure.test/run-tests 'storage-peer-runtime-test))
