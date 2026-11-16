;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns darwin-storage-test
  "Run the same real macOS storage fixtures in each supported product runtime."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import [java.io File])
  (:refer-clojure :exclude [run!]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} runner
  (pr-str
   '(do
      (require 'clojure.test)
      (try
        (let [suites '[ai.miniforge.file-durability.macos-acl-test
                       ai.miniforge.file-durability.native-probe-test]]
          (clojure.core/run! require suites)
          (let [{:keys [fail error]} (apply clojure.test/run-tests suites)]
            (when-not (zero? (+ fail error))
              (throw (ex-info "Native storage fixtures failed." {:fail fail :error error})))))
        (finally (shutdown-agents))))))

(defn- ^{:stratum 0} storage-path [workspace directory]
  (str (fs/path workspace "components/file-durability" directory)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} jvm-check! [workspace product tests]
  (process/shell {:dir (str (fs/path workspace "projects" product))}
                 "clojure" "-Sdeps" (pr-str {:paths [tests]}) "-M" "-e" runner))

(defn- ^{:stratum 1} babashka-check! [workspace tests]
  (let [jar (str (fs/path workspace "dist/miniforge.jar"))
        packaged [jar tests]
        source (concat (map (partial storage-path workspace) ["src" "resources"]) packaged)]
    (process/shell "bb" "build:cli")
    (fs/with-temp-dir [isolated {}]
      (doseq [paths [source packaged]]
        (process/shell {:dir (str isolated)}
                       "bb" "-cp" (str/join File/pathSeparator paths) "-e" runner)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run! []
  (when-not (= "Mac OS X" (System/getProperty "os.name"))
    (throw (ex-info "Native ACL verification requires macOS." {})))
  (let [workspace (fs/cwd)
        tests (storage-path workspace "test")]
    (process/shell "git" "rev-parse" "HEAD")
    (doseq [product ["miniforge-core" "miniforge"]]
      (jvm-check! workspace product tests))
    (babashka-check! workspace tests)))

(comment (run!))
