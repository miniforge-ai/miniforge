;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-resources
  "Classpath access for chain selection, independent of execution."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-selection :as selection]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.jar JarFile]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read-definition [path]
  (try+
    (when-let [resource (io/resource path)]
      (edn/read-string (slurp resource)))
    (catch Exception e (selection/read-failure path (.getMessage e)))))

(defn ^{:stratum 0} existing [path]
  (when (and path (io/resource path)) path))

(defn- ^{:stratum 0} file-names [url]
  (let [directory (io/file url)]
    (if (.isDirectory directory)
      (->> (.listFiles directory)
           (filter #(.isFile ^java.io.File %))
           (map #(.getName ^java.io.File %)))
      [])))

(defn- ^{:stratum 0} jar-names [url prefix]
  (let [jar-uri (first (str/split (.getPath url) #"!/" 2))
        jar-path (io/file (java.net.URL. jar-uri))]
    (with-open [^JarFile jar (JarFile. jar-path)]
      (->> (enumeration-seq (.entries jar))
           (map #(.getName %))
           (filter #(str/starts-with? % prefix))
           (remove #(= % prefix))
           (map #(subs % (count prefix)))
           (remove #(str/includes? % "/"))
           vec))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} candidates [{:keys [chain-id version]} latest-path]
  (let [base (str "chains/" (name chain-id) ".edn")
        exact (str "chains/" (name chain-id) "-v" version ".edn")]
    (cond
      (not (selection/latest? version)) [exact]
      (existing base) [base]
      :else (filterv some? [base (latest-path chain-id)]))))

(defn- ^{:stratum 1} resource-names [prefix url]
  (case (.getProtocol url)
    "file" (file-names url)
    "jar" (jar-names url prefix)
    []))

(defn ^{:stratum 1} summary [path]
  (try+
    (let [content (read-definition path)
          steps (count (:chain/steps content))]
      (when (and (map? content) (not (anomaly/anomaly? content)))
        {:id (:chain/id content)
         :version (:chain/version content)
         :description (:chain/description content)
         :steps steps}))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} names [directory]
  (let [urls (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) directory))
        prefix (str directory "/")]
    (when (seq urls)
      (->> urls (mapcat (partial resource-names prefix)) distinct vec))))

(comment
  (names "chains"))
