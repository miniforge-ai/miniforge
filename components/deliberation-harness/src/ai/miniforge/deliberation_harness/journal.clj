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

(def ^{:stratum 0} record-name-format
  "The sequence zero-padded to six digits, so names list in sequence order up
   to 999999; `record-files` sorts numerically past that."
  "%06d.edn")

(defn- ^{:stratum 0} record-name [sequence]
  (String/format Locale/ROOT record-name-format (to-array [sequence])))

(defn- ^{:stratum 0} record-file [directory sequence]
  (io/file directory (record-name sequence)))

;; The sequence number a record file's name carries, or nil for another file.
(defn- ^{:stratum 0} record-number
  [^File file]
  (some-> (re-matches record-name-pattern (.getName file)) second bigint))

;; The record files in `directory` in numeric sequence order, or an anomaly
;; when the directory cannot be listed or holds anything but record files:
;; a journal directory holds records only, so nothing in it is skipped.
(defn- ^{:stratum 0} record-files
  [directory]
  (let [files (.listFiles (io/file directory))
        stray (some #(when-not (record-number %) %) files)]
    (cond
      (nil? files) (anomaly/anomaly :unavailable (messages/system-t :anomaly/unlistable-journal)
                                    {:directory (str directory)})
      stray (anomaly/anomaly :conflict (messages/system-t :anomaly/stray-journal-file)
                             {:file (str stray)})
      :else (sort-by record-number files))))

(defn- ^{:stratum 0} chain-anomaly [file]
  (anomaly/anomaly :conflict (messages/system-t :anomaly/broken-chain)
                   {:file (str file)}))

(defn- ^{:stratum 0} failure [result]
  (when (anomaly/anomaly? result) result))

;; `record` with its own hash, computed over everything else in it.
(defn- ^{:stratum 0} sealed
  [record]
  (assoc record :hash (codec/digest record)))

;; True when `record` is the record expected at `sequence` after `previous`
;; and its hash matches its content.
(defn- ^{:stratum 0} links?
  [record sequence previous]
  (and (map? record)
       (= sequence (:sequence record))
       (= previous (:previous record))
       (= (:hash record) (codec/digest (dissoc record :hash)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} open!
  "Start a journal in a new `directory` under an existing parent, or an anomaly."
  [directory]
  (let [dir (io/file directory)]
    (if (.mkdir dir)
      {:journal/directory dir
       :journal/position (atom {:sequence 0 :previous nil})}
      (anomaly/anomaly :conflict (messages/system-t :anomaly/journal-exists)
                       {:directory (str dir)}))))

;; Write `record` to its file and force it, returning nil or the anomaly.
(defn- ^{:stratum 1} persist!
  [directory record]
  (let [file (record-file directory (:sequence record))]
    (or (failure (durable/write-new-text! file (codec/encode record)))
        (failure (durable/confirm! file)))))

;; The records in `files`, verified as one unbroken chain from sequence zero,
;; or the anomaly for the first file that cannot be read or breaks it. A
;; file breaks the chain when its name is not exactly the name `append!`
;; gives its sequence, so a misnumbered file can never be replayed.
(defn- ^{:stratum 1} verified-records
  [files]
  (loop [files files
         previous nil
         sequence 0
         records []]
    (if-let [file (first files)]
      (let [[status record] (codec/parse-source file)]
        (cond
          (= :failure status) record
          (and (= (record-name sequence) (.getName ^File file))
               (links? record sequence previous))
          (recur (next files) (:hash record) (inc sequence) (conj records record))
          :else (chain-anomaly file)))
      records)))

;; Seal and force `payload` as the next record, returning its hash or the
;; write anomaly. A failed write leaves the position where it was, so
;; nothing later can chain onto a record that was not forced.
(defn- ^{:stratum 1} write-next!
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
  "Durably append `payload`; its hash, an `:invalid-input` anomaly for a payload
   that is not `codec/plain?`, or the write's anomaly."
  [journal payload]
  (if (codec/plain? payload)
    (write-next! journal payload)
    (anomaly/anomaly :invalid-input (messages/system-t :anomaly/unencodable-payload) {})))

(defn ^{:stratum 2} read-journal
  "Every record in `directory`, verified as one chain from sequence zero, or an
   anomaly naming the directory or the first record that breaks or cannot be read."
  [directory]
  (let [files (record-files directory)]
    (if (anomaly/anomaly? files) files (verified-records files))))
