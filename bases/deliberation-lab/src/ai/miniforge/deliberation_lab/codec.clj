;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.codec
  "Canonical EDN for manifests, hashes and experiment journals."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import [java.security MessageDigest]))

(defn canonical [x]
  (walk/postwalk (fn [v] (cond (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                              (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) v)
                              :else v)) x))
(defn encode [x] (pr-str (canonical x)))
(defn digest [x]
  (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                                   (.getBytes (encode x) "UTF-8")))))
(defn read-file [path] (edn/read-string {:readers {} :default (fn [tag _] (throw (ex-info "Unknown tag" {:tag tag})))} (slurp path)))
