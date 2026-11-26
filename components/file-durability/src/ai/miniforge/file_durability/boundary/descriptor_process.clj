;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.descriptor-process
  "Own the fixed read-only native program, with no inherited interpreter options."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.lang ProcessBuilder$Redirect]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} executable
  "This support boundary uses the system interpreter, never PATH or caller argv."
  "/usr/bin/perl")

(def ^{:stratum 0 :private true} program-resource
  "The trusted installation owns the program; no script is read from the target."
  "config/file-durability/native/directory-descriptor.pl")

(defn- ^{:stratum 0} close-input! [^Process child] (.close (.getInputStream child)))

(defn- ^{:stratum 0} close-output! [^Process child] (.close (.getOutputStream child)))

(defn- ^{:stratum 0} stop! [path child timeout-ms]
  (when-not (exit/stop! child timeout-ms)
    (throw+ (boundary/unreaped-peer path))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start! [path]
  (let [program (slurp (io/resource program-resource))
        arguments [executable "-f" "-e" program "--" (str path)]
        builder (ProcessBuilder. ^java.util.List arguments)]
    (.clear (.environment builder))
    (.redirectError builder ProcessBuilder$Redirect/DISCARD)
    (.start builder)))

(defn- ^{:stratum 1} close-streams! [child]
  (cleanup/finish! (partial close-input! child) (partial close-output! child)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} release! [path timeout-ms child]
  (cleanup/finish! (partial stop! path child timeout-ms) (partial close-streams! child)))

(comment (io/resource program-resource))
