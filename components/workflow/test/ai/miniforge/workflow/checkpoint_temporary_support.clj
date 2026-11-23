;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-temporary-support
  (:require [ai.miniforge.workflow.checkpoint-store :as store]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} write! [target value]
  (#'store/write-edn-atomically! target value))

(defn ^{:stratum 0} partial-write! [write-original! failure path content]
  (write-original! path content)
  (throw failure))

(defn ^{:stratum 0} permission-mode [path]
  (fs/posix->str (fs/posix-file-permissions path)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-permissions! [write! observed path contents]
  (swap! observed conj (permission-mode path))
  (write! path contents))

(defn ^{:stratum 1} attempt-write! [target value]
  (try+ (write! target value) (catch Exception failure failure)))

(defn ^{:stratum 1} interleave! [write-original! entered target paths path content]
  (swap! paths conj path)
  (write-original! path content)
  (when (compare-and-set! entered false true)
    (write! target :inner)))

(comment ::checkpoint-write-fixtures)
