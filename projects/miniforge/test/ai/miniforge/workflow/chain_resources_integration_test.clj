;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-resources-integration-test
  (:require [ai.miniforge.workflow.chain-resources :as resources]
            [ai.miniforge.workflow.chain-test-support :as support]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [clojure.lang Compiler]
           [java.net URL URLClassLoader]
           [java.nio.charset StandardCharsets]
           [java.util.jar JarEntry JarOutputStream]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} write-entry! [^JarOutputStream jar path content]
  (.putNextEntry jar (JarEntry. path))
  (.write jar (.getBytes ^String content StandardCharsets/UTF_8))
  (.closeEntry jar))

(defn- ^{:stratum 0} resource-url [path]
  (.toURL (.toURI (io/file (str path)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} write-chain-jar! [path content]
  (with-open [jar (JarOutputStream. (io/output-stream (str path)))]
    (write-entry! jar "chains/" "")
    (write-entry! jar "chains/fixture-v1.0.0.edn" content)))

(defn- ^{:stratum 1} observed-resources [path]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (with-open [loader (URLClassLoader. (into-array URL [(resource-url path)]) nil)]
      (try+
        (.setContextClassLoader thread loader)
        (with-bindings {Compiler/LOADER loader}
          [(resources/names "chains")
           (resources/read-definition "chains/fixture-v1.0.0.edn")])
        (finally (.setContextClassLoader thread previous))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} encoded-directory-and-jar-paths-remain-readable
  (let [root (fs/create-temp-dir {:prefix "chain resources "})
        directory (fs/path root "directory with spaces")
        chain-file (fs/path directory "chains" "fixture-v1.0.0.edn")
        jar-path (fs/path root "jar with spaces.jar")
        definition (support/definition :fixture "1.0.0")
        content (pr-str definition)]
    (try+
      (fs/create-dirs (fs/parent chain-file))
      (spit (str chain-file) content)
      (write-chain-jar! jar-path content)
      (doseq [path [directory jar-path]]
        (let [[names actual] (observed-resources path)]
          (is (= ["fixture-v1.0.0.edn"] names))
          (is (= definition actual))))
      (finally (fs/delete-tree root)))))

(comment
  ::resource-io)
