;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.root-identity-test
  (:require [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} attributes [device inode]
  {:dev device
   :ino inode})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-identity-requires-both-kernel-integer-attributes
  (doseq [[device inode expected] [[1 2 true] [nil 2 false] [1 nil false] ["1" 2 false]]]
    (with-redefs [fs/read-attributes (constantly (attributes device inode))]
      (is (= expected (some? (identity/read-identity (fs/path "/root"))))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.root-identity-test))
