;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.spec
  (:require [ai.miniforge.file-durability.lock-spec :as lock]
            [ai.miniforge.file-durability.peer-spec :as peer]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} File [:fn #(instance? java.io.File %)])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} DirectoryFile
  [:and File [:fn #(not (.isEmpty (.getPath ^java.io.File %)))]])

(def ^{:stratum 1} TextWrite [:tuple File :string])

(def ^{:stratum 1} ByteWrite [:tuple File bytes?])

(def ^{:stratum 1} PublicationWrite [:tuple File bytes? fn?])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} RuntimeLockArguments [:tuple DirectoryFile peer/Peer lock/Limits])

(def ^{:stratum 2} AttachedDirectoryArguments [:tuple DirectoryFile DirectoryFile])
