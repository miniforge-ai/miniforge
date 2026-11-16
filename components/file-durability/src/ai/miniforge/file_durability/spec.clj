;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.spec
  (:require [clojure.string :as str])
  (:import [java.util.regex Pattern]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} File [:fn #(instance? java.io.File %)])

(def ^{:stratum 0 :private true} classpath-separator
  "The runtime classpath is a list of already installed paths, not argument syntax."
  (re-pattern (Pattern/quote java.io.File/pathSeparator)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} DirectoryFile
  [:and File [:fn #(not (.isEmpty (.getPath ^java.io.File %)))]])

(def ^{:stratum 1} TextWrite [:tuple File :string])

(def ^{:stratum 1} ByteWrite [:tuple File bytes?])

(def ^{:stratum 1} PublicationWrite [:tuple File bytes? fn?])

(def ^{:stratum 1} ^:private Peer
  [:map {:closed true}
   [:peer/executable [:and File [:fn #(.isAbsolute ^java.io.File %)]]]
   [:peer/classpath [:and :string
                    [:fn #(every? (fn [entry] (.isAbsolute (java.io.File. ^String entry)))
                                  (str/split % classpath-separator -1))]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} QualificationArguments [:tuple DirectoryFile :keyword Peer])

(def ^{:stratum 2} AttachedDirectoryArguments [:tuple DirectoryFile DirectoryFile])
