;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence.exporting
  "Export the validated in-memory value, without rereading mutable source bytes."
  (:require [ai.miniforge.cli.main.commands.evidence.validation :as validation]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.messages :as messages]
            [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unsupported-format! [fmt]
  (display/print-error (messages/t :evidence/export-format-unsupported {:format fmt}))
  (shared/exit! 1))

(defn- ^{:stratum 0} write-with-exception-handling! [bundle destination]
  (try
    (spit destination (evidence/encode-bundle-edn bundle))
    (display/print-success (messages/t :evidence/export-raw {:path destination}))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _
      (display/print-error (messages/t :evidence/export-failed {:path destination}))
      (shared/exit! 1))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} export! [id bundle fmt destination]
  (cond
    (not= "edn" fmt) (unsupported-format! fmt)
    (validation/require-valid! id bundle) (write-with-exception-handling! bundle destination)))

(comment
  ::export!)
