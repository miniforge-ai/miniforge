;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-spec
  "The spec file a run was started from, as its checkpoint manifest records
   it, and whether that file has changed since.

   The checkpoint spec (GROUP 3) has resume refuse a run whose spec changed
   after it started, since the checkpointed phases were produced from the
   old one: the manifest carries `:workflow/spec-path` and
   `:workflow/spec-hash` (SHA-256 of the file at the run's first
   checkpoint), and `spec-drift` compares the file now against them."
  (:require
   [ai.miniforge.config.interface :as config]
   [babashka.fs :as fs]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} file-hash
  "SHA-256 of the file at `path`, nil when it is not a readable file."
  [path]
  (try+
    (when (fs/regular-file? path)
      (config/sha256-hex (slurp (str path))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} spec-fields
  "The spec fields a run's manifest carries. Once recorded they are kept,
   so the hash stays the one taken at the run's first checkpoint. Until
   then: the absolute path and hash of the run's input spec file
   (`:spec/path`), or none when the run has no readable spec file."
  [ctx existing-manifest]
  (if (:workflow/spec-hash existing-manifest)
    (select-keys existing-manifest [:workflow/spec-path :workflow/spec-hash])
    (let [path (get-in ctx [:execution/input :spec/path])
          spec-hash (some-> path file-hash)]
      (if spec-hash
        {:workflow/spec-path (str (fs/absolutize path)) :workflow/spec-hash spec-hash}
        {}))))

(defn ^{:stratum 1} spec-drift
  "How the spec a run started from has changed, nil when it has not or the
   manifest recorded none (a run without a spec file, or one checkpointed
   before this was recorded). Returns `{:workflow/spec-path
   :spec/expected-hash :spec/actual-hash}`; the actual hash is nil when the
   file is gone or unreadable."
  [manifest]
  (let [{:workflow/keys [spec-path spec-hash]} manifest]
    (when spec-hash
      (let [actual (file-hash spec-path)]
        (when (not= spec-hash actual)
          {:workflow/spec-path spec-path
           :spec/expected-hash spec-hash
           :spec/actual-hash actual})))))

(comment
  (spec-drift {:workflow/spec-path "work/x.spec.edn" :workflow/spec-hash "sha256:00"}))
