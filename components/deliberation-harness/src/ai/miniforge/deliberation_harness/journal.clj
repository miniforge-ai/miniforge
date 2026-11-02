;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.deliberation-harness.journal
  "One durable, hash-chained stream of immutable experiment records. Each
   record names its sequence and its predecessor's hash, is written to a
   new file and forced before the next is accepted, and never replaces an
   earlier run. The journal is authoritative; reports are projections."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [ai.miniforge.file-durability.interface :as durable]
   [clojure.java.io :as io])
  (:import
   (java.io File)
   (java.util Locale)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} record-name-pattern
  "Record files are the sequence number, zero-padded to at least six digits."
  #"([0-9]{6,})\.edn")

(def ^{:stratum 0} record-name-format "%06d.edn")

(defn- ^{:stratum 0} record-file [directory sequence]
  (io/file directory (String/format Locale/ROOT record-name-format (to-array [sequence]))))

(defn- ^{:stratum 0} record-number
  "The sequence number a record file's name carries, or nil for another file."
  [^File file]
  (some-> (re-matches record-name-pattern (.getName file)) second bigint))

(defn- ^{:stratum 0} record-files
  "The record files in `directory` in numeric sequence order, or an anomaly
   when the directory cannot be listed."
  [directory]
  (if-let [files (.listFiles (io/file directory))]
    (sort-by record-number (filter record-number files))
    (anomaly/anomaly :unavailable (messages/system-t :anomaly/unlistable-journal)
                     {:directory (str directory)})))

(defn- ^{:stratum 0} chain-anomaly [file]
  (anomaly/anomaly :conflict (messages/system-t :anomaly/broken-chain)
                   {:file (str file)}))

(defn- ^{:stratum 0} failure [result]
  (when (anomaly/anomaly? result) result))

(defn- ^{:stratum 0} sealed
  "`record` with its own hash, computed over everything else in it."
  [record]
  (assoc record :hash (codec/digest record)))

(defn- ^{:stratum 0} links?
  "True when `record` is the record expected at `sequence` after `previous`
   and its hash matches its content."
  [record sequence previous]
  (and (map? record)
       (= sequence (:sequence record))
       (= previous (:previous record))
       (= (:hash record) (codec/digest (dissoc record :hash)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} open!
  "Start a journal in `directory`, which must not exist yet and whose parent
   must. Returns the journal, or an anomaly: a run never reuses a directory."
  [directory]
  (let [dir (io/file directory)]
    (if (.mkdir dir)
      {:journal/directory dir
       :journal/position (atom {:sequence 0 :previous nil})}
      (anomaly/anomaly :conflict (messages/system-t :anomaly/journal-exists)
                       {:directory (str dir)}))))

(defn- ^{:stratum 1} persist!
  "Write `record` to its file and force it, returning nil or the anomaly."
  [directory record]
  (let [file (record-file directory (:sequence record))]
    (or (failure (durable/write-new-text! file (codec/encode record)))
        (failure (durable/confirm! file)))))

(defn- ^{:stratum 1} verified-records
  "The records in `files`, verified as one unbroken chain from sequence zero,
   or the anomaly for the first file that cannot be read or breaks it."
  [files]
  (loop [files files
         previous nil
         sequence 0
         records []]
    (if-let [file (first files)]
      (let [[status record] (codec/parse-source file)]
        (cond
          (= :failure status) record
          (links? record sequence previous)
          (recur (next files) (:hash record) (inc sequence) (conj records record))
          :else (chain-anomaly file)))
      records)))

(defn- ^{:stratum 1} write-next!
  "Seal and force `payload` as the next record, returning its hash or the
   write anomaly. A failed write leaves the position where it was, so
   nothing later can chain onto a record that was not forced."
  [journal payload]
  (let [directory (:journal/directory journal)
        position (:journal/position journal)]
    (locking position
      (let [{:keys [sequence previous]} @position
            record (sealed {:sequence sequence :previous previous :payload payload})
            failure (persist! directory record)]
        (if failure
          failure
          (do (reset! position {:sequence (inc sequence) :previous (:hash record)})
              (:hash record)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} append!
  "Durably append `payload` as the next record. Returns the record's hash, an
   `:invalid-input` anomaly for a payload that is not plain EDN (it could
   not be replayed), or the anomaly from writing it."
  [journal payload]
  (if (codec/plain? payload)
    (write-next! journal payload)
    (anomaly/anomaly :invalid-input (messages/system-t :anomaly/unencodable-payload) {})))

(defn ^{:stratum 2} read-journal
  "Every record in `directory`, verified as one unbroken chain from sequence
   zero, or an anomaly naming the directory or the first record that breaks
   or cannot be read."
  [directory]
  (let [files (record-files directory)]
    (if (anomaly/anomaly? files) files (verified-records files))))
