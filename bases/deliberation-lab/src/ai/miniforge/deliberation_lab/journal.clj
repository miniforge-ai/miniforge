;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.journal
  "One durable, hash-chained stream of immutable records. Never overwrites a run.
   Snapshots are projections in the journal, not a separately acknowledged write."
  (:require [ai.miniforge.deliberation-lab.codec :as codec]
            [ai.miniforge.file-durability.interface :as durable]
            [clojure.java.io :as io]))

(defn- checked [result]
  (when (map? result) (throw (ex-info "Journal durability failed" {:anomaly result})))
  result)
(defn open! [directory manifest]
  (let [dir (io/file directory)]
    (when-not (.mkdir dir)
      (throw (ex-info "Output directory must be new, with an existing parent" {:directory directory})))
    {:directory dir :position (atom {:sequence 0 :previous nil}) :manifest manifest}))
(defn append! [{:keys [directory position]} payload]
  (locking position
    (let [{:keys [sequence previous]} @position
          record {:sequence sequence :previous previous :payload payload}
          hash (codec/digest record)
          file (io/file directory (format "%06d.edn" sequence))]
      (checked (durable/write-new-text! file (codec/encode (assoc record :hash hash))))
      (checked (durable/confirm! file))
      (reset! position {:sequence (inc sequence) :previous hash})
      hash)))
(defn read! [directory]
  (let [files (sort-by #(.getName %) (filter #(re-matches #"[0-9]{6}\.edn" (.getName %))
                                                         (.listFiles (io/file directory))))]
    (loop [remaining files previous nil sequence 0 records []]
      (if-let [file (first remaining)]
        (let [record (codec/read-file file)]
          (when-not (and (= sequence (:sequence record)) (= previous (:previous record))
                         (= (:hash record) (codec/digest (dissoc record :hash))))
            (throw (ex-info "Invalid experiment journal chain" {:file (str file)})))
          (recur (next remaining) (:hash record) (inc sequence) (conj records record)))
        records))))
