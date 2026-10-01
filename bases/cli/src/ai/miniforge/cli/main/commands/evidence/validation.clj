;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence.validation
  "Reject invalid or unsealed evidence at CLI consumption boundaries."
  (:require [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.messages :as messages]
            [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject! [id]
  (display/print-error (messages/t :evidence/invalid {:id id}))
  false)

(defn- ^{:stratum 0} exit-invalid! []
  (shared/exit! 1)
  false)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} accepted? [id bundle]
  (let [report (evidence/validate-published-bundle bundle)]
    (if (:valid? report)
      true
      (reject! id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} require-valid! [id bundle]
  (if (accepted? id bundle)
    true
    (exit-invalid!)))

(comment
  (accepted? "test" {}))
