;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.json-document
  "Admit exactly one duplicate-free JSON document and preserve decimal precision."
  (:require [cheshire.core :as json]
            [cheshire.factory :as factory]
            [clojure.data.json :as exact-json])
  (:import [java.io BufferedReader StringReader]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private strict-json-factory
  (factory/make-json-factory {:strict-duplicate-detection true
                              :allow-unquoted-control-chars false}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode! [text]
  ;; Cheshire's "strict" parser only makes arrays eager; it does not check EOF.
  (binding [factory/*json-factory* strict-json-factory]
    (with-open [reader (BufferedReader. (StringReader. text))]
      (let [documents (take 2 (json/parsed-seq reader false))]
        (when-not (= 1 (count documents)) (throw (IllegalArgumentException.)))
        ;; Babashka hides Cheshire's decimal binding; validate there, decode losslessly here.
        (exact-json/read-str text :bigdec true)))))

(comment ::strict-native-json-document)
