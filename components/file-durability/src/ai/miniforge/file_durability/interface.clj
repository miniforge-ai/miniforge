;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.interface
  "Trusted-process filesystem primitives. Inputs are caller-owned java.io.Files.
   Return the input file on success or an anomaly; callers must propagate failures.
   Stores own path authorization, publication atomicity, and record identity.
   Complete writes force file contents; publication needs a subsequent confirm!."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.core :as core]
            [ai.miniforge.file-durability.spec :as spec]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} write-new-text!
  "Create and force a new UTF-8 file without replacing existing files or links."
  [file text]
  (if (m/validate spec/TextWrite [file text])
    (core/write-new-text! file text)
    (boundary/invalid-input :write-new)))

(defn ^{:stratum 0} write-temporary-bytes!
  "Fill and force an existing fresh temporary file without following a final symlink."
  [file bytes]
  (if (m/validate spec/ByteWrite [file bytes])
    (core/write-temporary-bytes! file bytes)
    (boundary/invalid-input :write-temporary)))

(defn ^{:stratum 0} sync-ancestry!
  "Force directory entries from the supplied directory through its ancestors."
  [directory]
  (if (m/validate spec/File directory)
    (core/sync-ancestry! directory)
    (boundary/invalid-input :sync-ancestry)))

(defn ^{:stratum 0} confirm!
  "Force the published file and all ancestor directory entries before acknowledging."
  [file]
  (if (m/validate spec/File file)
    (core/confirm! file)
    (boundary/invalid-input :confirm)))
