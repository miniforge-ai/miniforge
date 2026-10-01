;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence.exporting
  "Export the validated in-memory value, without rereading mutable source bytes."
  (:require [clojure.java.io :as io]
            [ai.miniforge.cli.main.commands.evidence.validation :as validation]
            [ai.miniforge.cli.main.commands.evidence.formats :as formats]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.messages :as messages]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unsupported-format! [fmt]
  (display/print-error (messages/t :evidence/export-format-unsupported {:format fmt}))
  (shared/exit! 1))

(defn- ^{:stratum 0} export-failed! [destination]
  (display/print-error (messages/t :evidence/export-failed {:path destination}))
  (shared/exit! 1))

(defn- ^{:stratum 0} write-content! [content destination]
  (io/make-parents destination)
  (spit destination content :encoding "UTF-8")
  (display/print-success (messages/t :evidence/export-raw {:path destination})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} render-with-exception-handling! [bundle fmt destination]
  (try+
    (let [content (formats/encode bundle fmt)]
      (if (string? content) (write-content! content destination) (export-failed! destination)))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Error fatal (throw fatal))
    (catch Object _ (export-failed! destination))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} export! [id bundle fmt destination]
  (cond
    (not (contains? formats/encoders fmt)) (unsupported-format! fmt)
    (validation/require-valid! id bundle) (render-with-exception-handling! bundle fmt destination)))

(comment
  ::export!)
