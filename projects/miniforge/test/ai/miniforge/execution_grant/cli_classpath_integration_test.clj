;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.cli-classpath-integration-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workspace-root []
  (loop [directory (fs/canonicalize ".")]
    (cond
      (fs/exists? (fs/path directory "workspace.edn")) (str directory)
      (nil? (fs/parent directory)) nil
      :else (recur (fs/parent directory)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} declared-cli-classpath-loads-current-authority-dependencies-test
  (let [root (workspace-root)
        result @(process/process ["bb" "miniforge" "--help"] {:dir root
                                                          :out :string
                                                          :err :string})]
    (is (some? root))
    (is (zero? (:exit result)) (:err result))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.cli-classpath-integration-test))
