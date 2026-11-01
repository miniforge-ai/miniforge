;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.dependency-policy
  "Native dependency admission, independent of namespace loading."
  (:require [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private portable-coordinate {:mvn/version "2.4.0"})

(defn- ^{:stratum 0} json-coordinates [document]
  (for [node (tree-seq coll? seq document)
        :when (and (map? node) (contains? node 'org.clojure/data.json))]
    (get node 'org.clojure/data.json)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compatible-json? [deps-text]
  (every? #{portable-coordinate} (json-coordinates (edn/read-string deps-text))))

(comment (compatible-json? "{:deps {}}"))
