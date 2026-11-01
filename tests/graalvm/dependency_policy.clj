;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.dependency-policy
  "Native dependency admission, independent of namespace loading."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private portable-dependency
  ['org.clojure/data.json {:mvn/version "2.4.0"}])

(defn- ^{:stratum 0} json-library? [library]
  (and (symbol? library) (= "org.clojure" (namespace library))
       (or (= "data.json" (name library)) (str/starts-with? (name library) "data.json$"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} json-dependencies [document]
  (for [node (tree-seq coll? seq document)
        :when (map? node)
        [library coordinate] node
        :when (json-library? library)]
    [library coordinate]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compatible-json? [deps-text]
  ;; bb.edn contains Clojure task forms. Read them as data, never reader evaluation.
  (let [document (binding [*read-eval* false] (read-string deps-text))]
    (every? #{portable-dependency} (json-dependencies document))))

(comment (compatible-json? "{:deps {}}"))
