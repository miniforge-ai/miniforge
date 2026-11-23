;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.runtime-peer-fixtures
  "Select installed peer runtimes without assuming JVM and Babashka classpaths match."
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private peer-classpath-property
  "Use the installed peer classpath rather than assuming the JVM and BB layouts match."
  "miniforge.test.lock-peer-classpath")

(defn- ^{:stratum 0} peer-executable []
  (case (protocol/runtime-kind)
    :jvm (fs/which "bb")
    :babashka (fs/which "java")))

(defn- ^{:stratum 0} jvm-classpath []
  (let [separator (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))]
    (->> (str/split (System/getProperty "java.class.path") separator)
         (map #(.getCanonicalPath ^File (fs/file %)))
         (str/join File/pathSeparator))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} peer-config [classpath]
  (let [path (peer-executable)]
    (when path
      {:peer/executable (.getAbsoluteFile ^File (fs/file path))
       :peer/classpath classpath})))

(defn ^{:stratum 1} peer-classpath []
  (or (System/getProperty peer-classpath-property)
      (when (= :jvm (protocol/runtime-kind)) (jvm-classpath))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} require-peer! []
  (or (some-> (peer-classpath) peer-config)
      (throw (ex-info "Native storage fixtures require the other installed runtime." {}))))

(comment (peer-classpath))
