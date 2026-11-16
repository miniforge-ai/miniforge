;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-qualification-integration-test
  "Uses real installed runtimes on a disposable path, never production authority."
  (:require [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} peer-classpath-property
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

(defn- ^{:stratum 1} peer-classpath []
  (or (System/getProperty peer-classpath-property)
      (when (= :jvm (protocol/runtime-kind)) (jvm-classpath))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} independent-runtime-verifies-lock-exclusion-and-reacquisition
  (let [classpath (peer-classpath)]
    (when classpath
      (support/with-root
       (fn [root]
         (when (support/posix? root)
           (let [peer (peer-config classpath)
                 directory (fs/real-path root)]
             (is (some? peer))
             (is (true? (lock/check! directory peer)))
             (is (empty? (fs/list-dir root))))))))))

(comment ::launch-with-an-explicit-other-runtime-classpath)
