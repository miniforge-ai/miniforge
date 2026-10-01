;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.status.presentation
  (:require [ai.miniforge.cli.main.display :as display]
            [ai.miniforge.cli.main.util :as util]
            [ai.miniforge.cli.messages :as messages]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} summary-limit "Maximum workflows in the concise status listing." 10)

(defn- ^{:stratum 0} known-or-unknown [value]
  (if value value (messages/t :status/value-unknown)))

(defn- ^{:stratum 0} completed-phase-label [phases]
  (if (seq phases) (str/join ", " (map name phases))
    (messages/t :status/value-none)))

(defn- ^{:stratum 0} print-field [[message value]]
  (println (messages/t message {:value value})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} field-values [{:keys [status spec-name event-count completed-phases
                            completed-dag-task-count last-updated]}]
  [[:status/field-status (util/status-label status)]
   [:status/field-spec (known-or-unknown spec-name)]
   [:status/field-events event-count]
   [:status/field-last-updated (known-or-unknown last-updated)]
   [:status/field-completed-phases (completed-phase-label completed-phases)]
   [:status/field-completed-dag-tasks completed-dag-task-count]])

(defn- ^{:stratum 1} print-summary [{:keys [workflow-id status spec-name last-updated]}]
  (let [id-label (format "%-36s" workflow-id)
        status-label (format "%-10s" (util/status-label status))
        spec-label (known-or-unknown spec-name)]
    (println (messages/t :status/summary-row
                         {:workflow-id id-label :status status-label :spec-name spec-label}))
    (print-field [:status/summary-last-updated (known-or-unknown last-updated)])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} print-workflow [summary]
  (display/print-info (messages/t :status/workflow {:workflow-id (:workflow-id summary)}))
  (run! print-field (field-values summary)))

(defn ^{:stratum 2} print-all [summaries]
  (display/print-info (messages/t :status/all-workflows))
  (if-let [recent (seq (take summary-limit summaries))]
    (run! print-summary recent)
    (println (str "  " (messages/t :status/value-none)))))

(comment
  (print-all []))
