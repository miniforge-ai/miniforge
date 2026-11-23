;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-test-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files CopyOption]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} withdraw! [directory id]
  (let [files (filterv #(str/starts-with? (.getName ^java.io.File %) (str id "."))
                       (.listFiles (io/file directory)))
        _single (is (= 1 (count files)))
        original (.toPath ^java.io.File (first files))
        withheld (.resolveSibling original (str (.getFileName original) ".withheld"))]
    (Files/move original withheld (make-array CopyOption 0))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-then-withdraw! [publish directory value]
  (let [published (publish directory value)
        preparation? (and (= :verification-environment (get-in value [:artifact/metadata :opsv/material-kind]))
                          (not (contains? (:artifact/content value) :comparison/stage)))]
    (when (and preparation? (not (anomaly/anomaly? published)))
      (withdraw! directory (:artifact/id published)))
    published))

(comment ::project-integration-moves-only-inside-the-canonical-new-temporary-test-store)
