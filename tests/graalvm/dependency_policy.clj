;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.dependency-policy
  "Native dependency admission, independent of namespace loading."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private portable-dependency
  ['org.clojure/data.json {:mvn/version "2.4.0"}])

(def ^{:stratum 0} ^:private dependency-scopes
  [[:manifest [:deps]]
   [:aliases [:deps :extra-deps :replace-deps :override-deps :default-deps :classpath-overrides]]
   [:tasks [:extra-deps]]])

(defn- ^{:stratum 0} json-library? [library]
  (and (symbol? library) (= "org.clojure" (namespace library))
       (or (= "data.json" (name library)) (str/starts-with? (name library) "data.json$"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verified-json? [[field library coordinate]]
  (and (not= field :classpath-overrides) (= portable-dependency [library coordinate])))

(defn- ^{:stratum 1} dependency-declarations [document]
  (for [[scope fields] dependency-scopes
        config (if (= scope :manifest) [document] (vals (get document scope)))
        :when (map? config)
        field fields
        :let [dependencies (get config field)]
        :when (map? dependencies)
        [library coordinate] dependencies
        :when (json-library? library)]
    [field library coordinate]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compatible-json? [deps-text]
  ;; bb.edn contains Clojure task forms. Read them as data, never reader evaluation.
  (let [document (binding [*read-eval* false] (read-string deps-text))]
    (every? verified-json? (dependency-declarations document))))

(comment (compatible-json? "{:deps {}}"))
