;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.journal-recovery
  "Validate untrusted stored records before reconstructing committed positions."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.journal-record :as record]
            [ai.miniforge.event-stream.journal-spec :as spec]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} valid-record? [value]
  (and (m/validate spec/RecordContent (:artifact/content value))
       (= value (record/wrap-event (record/scope value) (record/event value)))))

(defn- ^{:stratum 0} restore-record [state value]
  (let [event (record/event value)
        scope (record/scope value)
        expected (get-in state [:next-sequences scope] 0)]
    (if (and (= expected (:event/sequence-number event))
             (nil? (model/recorded state (:event/id event))))
      (model/accept state scope event)
      (reduced (model/failure :fault :journal/invalid event)))))

(defn- ^{:stratum 0} confirm-record [directory state value]
  (let [receipt (artifact/publish! directory value)]
    (cond
      (anomaly/anomaly? receipt) (reduced receipt)
      (= value receipt) state
      :else (reduced (model/failure :fault :journal/recovery (record/event value))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} recover-state
  "Reject gaps and duplicate identities, regardless of enumeration order."
  [records]
  (if (every? valid-record? records)
    (reduce restore-record (model/empty-state)
            (sort-by (comp :event/sequence-number record/event) records))
    (model/failure :fault :journal/invalid nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} recover! [directory]
  (let [records (artifact/list-published directory)
        state (if (anomaly/anomaly? records) records (recover-state records))]
    (if (anomaly/anomaly? state)
      state
      ;; An interrupted writer may have linked a complete file without finishing
      ;; its durability barriers. Reconfirm before treating it as acknowledged.
      (reduce (partial confirm-record directory) state records))))

(comment
  (recover-state []))
