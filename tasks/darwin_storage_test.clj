;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns darwin-storage-test
  "Run the same real macOS storage fixtures in each supported product runtime."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [storage-peer-runtime :as peer])
  (:import [java.io File])
  (:refer-clojure :exclude [run!]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} runner
  "Run the native integration packet in a fresh runtime, failing on any assertion."
  (pr-str
   '(do
      (require 'clojure.test 'ai.miniforge.file-durability.runtime-peer-fixtures)
      (try
        (ai.miniforge.file-durability.runtime-peer-fixtures/require-peer!)
        (let [suites '[ai.miniforge.file-durability.macos-acl-integration-test
                       ai.miniforge.file-durability.native-probe-integration-test
                       ai.miniforge.file-durability.qualification-test
                       ai.miniforge.file-durability.qualified-root-lifetime-integration-test
                       ai.miniforge.file-durability.runtime-lock-boundary-test
                       ai.miniforge.file-durability.runtime-lock-refusal-integration-test
                       ai.miniforge.file-durability.runtime-peer-fixtures-test
                       ai.miniforge.file-durability.lock-protocol-test
                       ai.miniforge.file-durability.lock-denial-integration-test
                       ai.miniforge.file-durability.lock-qualification-integration-test
                       ai.miniforge.file-durability.directory-pin-scan-test
                       ai.miniforge.file-durability.owned-resource-integration-test
                       ai.miniforge.file-durability.owned-resource-failure-test
                       ai.miniforge.file-durability.directory-pin-integration-test
                       ai.miniforge.file-durability.attached-directory-integration-test
                       ai.miniforge.file-durability.native-process-exit-integration-test
                       ai.miniforge.file-durability.probe-cleanup-integration-test]]
          (clojure.core/run! require suites)
          (let [{:keys [fail error]} (apply clojure.test/run-tests suites)]
            (when-not (zero? (+ fail error))
              (throw (ex-info "Native storage fixtures failed." {:fail fail
                                                               :error error})))))
        (finally (shutdown-agents))))))

(defn- ^{:stratum 0} storage-path [workspace directory]
  (str (fs/path workspace "components/file-durability" directory)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} jvm-check! [workspace product tests]
  (let [directory (str (fs/path workspace "projects" product))
        dependencies (pr-str {:paths tests})]
    (process/shell {:dir directory}
                   "clojure" "-Sdeps" dependencies "-M" "-e" runner)))

(defn- ^{:stratum 1} test-paths [workspace]
  [(storage-path workspace "test")
   (str (fs/path workspace "projects/miniforge/test"))])

(defn- ^{:stratum 1} babashka-check! [workspace tests]
  (let [jar (str (fs/path workspace "dist/miniforge.jar"))
        property (str "-Dminiforge.test.lock-peer-classpath=" (peer/classpath! workspace))
        packaged (into [jar] tests)
        source (concat (map (partial storage-path workspace) ["src" "resources"]) packaged)]
    (process/shell "bb" "build:cli")
    (fs/with-temp-dir [isolated {}]
      (doseq [paths [source packaged]]
        (process/shell {:dir (str isolated)}
                       "bb" property "-cp" (str/join File/pathSeparator paths) "-e" runner)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run! []
  (when-not (= "Mac OS X" (System/getProperty "os.name"))
    (throw (ex-info "Native ACL verification requires macOS." {})))
  (let [workspace (fs/cwd)
        tests (test-paths workspace)]
    (process/shell "git" "rev-parse" "HEAD")
    (doseq [product ["miniforge-core" "miniforge"]]
      (jvm-check! workspace product tests))
    (babashka-check! workspace tests)))

(comment (run!))
