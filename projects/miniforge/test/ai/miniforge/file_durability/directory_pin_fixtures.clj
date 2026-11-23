;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.directory-pin-fixtures
  (:require [ai.miniforge.file-durability.boundary.directory-pin :as pin]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files Path]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} limit
  "Fixture roots have at most a handful of entries; finite fuel still exercises lookup."
  64)

(def ^{:stratum 0 :private true} budget-ns
  "Allow native access-control reads without approaching the fixture deadline."
  5000000000)

(def ^{:stratum 0 :private true} private-probe-mode
  "Pin witnesses are private at creation, independently of the process umask."
  "rw-------")

(def ^{:stratum 0 :private true} native-release
  "Fault controls may replace the public operation; fixture cleanup must still work."
  lease/release!)

(defn ^{:stratum 0} prepare! [path]
  (durability/prepare-protected-directory! (fs/file path)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} until [] (+ (System/nanoTime) budget-ns))

(defn ^{:stratum 1} release! [pin] (native-release pin))

(defn ^{:stratum 1} replace-before-open! [witness open! ^Path root]
  (is (= private-probe-mode (support/mode (.toFile ^Path @witness))))
  (Files/delete @witness)
  (Files/delete root)
  (prepare! root)
  (open! root))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} open! [root]
  (pin/open! (fs/real-path root) limit (until)))

(comment (until))
