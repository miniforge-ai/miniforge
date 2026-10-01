;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.interface
  "Trusted-process filesystem primitives. Inputs are caller-owned java.io.Files.
   Return the input file on success or an anomaly; callers must propagate failures.
   Stores own path authorization, publication atomicity, and record identity.
   Complete writes force file contents; publication needs a subsequent confirm!."
  (:require [ai.miniforge.file-durability.core :as core]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} write-new-text!
  "Create and force a new UTF-8 file without replacing existing files or links."
  core/write-new-text!)

(def ^{:stratum 0} write-temporary-bytes!
  "Fill and force an existing fresh temporary file without following a final symlink."
  core/write-temporary-bytes!)

(def ^{:stratum 0} sync-ancestry!
  "Force directory entries from the supplied directory through its ancestors."
  core/sync-ancestry!)

(def ^{:stratum 0} confirm!
  "Force the published file and all ancestor directory entries before acknowledging."
  core/confirm!)
