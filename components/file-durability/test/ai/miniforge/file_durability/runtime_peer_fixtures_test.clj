;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.runtime-peer-fixtures-test
  (:require [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-native-packet-cannot-silently-skip-a-missing-peer-classpath
  (with-redefs [fixtures/peer-classpath (constantly nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"other installed runtime" (fixtures/require-peer!)))))

(deftest ^{:stratum 0} test-native-packet-requires-the-peer-executable-as-well
  (with-redefs [fixtures/peer-classpath (constantly "/installed/classes")
                fs/which (constantly nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"other installed runtime" (fixtures/require-peer!)))))

(deftest ^{:stratum 0} test-native-packet-uses-the-selected-peer-classpath
  (with-redefs [fixtures/peer-classpath (constantly "/installed/classes")
                fs/which (constantly "/installed/runtime")]
    (is (= "/installed/classes" (:peer/classpath (fixtures/require-peer!))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.runtime-peer-fixtures-test))
