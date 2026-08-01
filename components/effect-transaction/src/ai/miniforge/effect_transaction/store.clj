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
(ns ai.miniforge.effect-transaction.store
  "Durable storage for EffectTransactions (Ariadne step 2c).

   Records land with the same atomic discipline the operator
   intervention path already uses (`operator_requests.clj`
   `write-event-file!`): write a sibling `.tmp`, then `Files/move` with
   ATOMIC_MOVE. A reader can never observe a half-written record, and
   the `.tmp` suffix keeps partials out of the `.edn` listing.

   The rule that discipline exists to enforce: a transaction that did
   not reach disk MUST NOT report a committed effect. If we cannot
   record that we are about to do something irreversible, we do not do
   it — otherwise the first thing a crash destroys is the evidence that
   the effect was ever attempted."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption]
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; Wire form
(def ^{:stratum 0} instant-keys
  "Record keys holding an Instant. EDN has no reader for
   `java.time.Instant`, so these cross the disk boundary as ISO-8601
   strings and are parsed back on read — explicit, rather than relying
   on a print form that does not round-trip."
  [:effect/at :effect/updated-at])

(defn ^{:stratum 0} record-file
  "Path for one record: `{dir}/{id}.edn`."
  ^File [dir id]
  (io/file dir (str id ".edn")))

(defn ^{:stratum 0} ->iso
  "Timestamp -> ISO-8601 string, by actual type rather than by type hint.

   The schema says `inst?`, and `inst?` admits BOTH `java.time.Instant`
   and `java.util.Date` — so a record the schema accepts can arrive here
   holding either. Both are normalized; anything else throws.

   Throwing is the right answer for a store: persisting a value that
   cannot be read back turns a durable record into a landmine that only
   goes off later, when someone is trying to reconcile an effect they
   already performed.

   Strings are NOT waved through. A string that happens not to parse
   would persist fine and only fail on the way back out — which is the
   landmine this is meant to prevent, just relocated."
  ^String [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v) (.toString (.toInstant ^Date v))
    :else (throw (ex-info "EffectTransaction timestamp is not a supported instant type"
                          {:value v :type (some-> v class .getName)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ->wire
  "Record -> EDN-safe map (timestamps to ISO-8601 strings)."
  [record]
  (reduce (fn [acc k]
            (if (some? (get acc k))
              (assoc acc k (->iso (get acc k)))
              acc))
          record
          instant-keys))

(defn ^{:stratum 1} <-wire
  "EDN map -> record (ISO-8601 strings back to Instants). Reading is
   deliberately strict: a stored value that will not parse is corruption
   and must surface, not be silently carried forward as a string."
  [m]
  (reduce (fn [acc k]
            (if-let [v (get acc k)]
              (assoc acc k (Instant/parse v))
              acc))
          m
          instant-keys))

;------------------------------------------------------------------------------ Layer 2

;; Atomic write
(defn ^{:stratum 2} write!
  "Persist `record` under `dir` atomically. Returns the target File.

   Throws if the write cannot complete — callers MUST treat that as
   'the effect did not happen and must not be attempted', never as a
   soft failure to log past."
  ^File [dir record]
  (let [^File target (record-file dir (:effect/id record))
        _ (io/make-parents target)
        ^File tmp (io/file (.getParentFile target) (str (.getName target) ".tmp"))]
    (spit tmp (pr-str (->wire record)) :encoding "UTF-8")
    (Files/move (.toPath tmp)
                (.toPath target)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    target))

(defn ^{:stratum 2} read-record
  "Read one record by id, or nil when absent."
  [dir id]
  (let [^File f (record-file dir id)]
    (when (.exists f)
      (<-wire (edn/read-string (slurp f :encoding "UTF-8"))))))

;; Listing
(defn ^{:stratum 2} list-records
  "Every persisted record under `dir`. `.tmp` files are skipped by the
   `.edn` suffix filter — a partial write is never mistaken for a
   record."
  [dir]
  (let [^File d (io/file dir)
        ;; Bound rather than inlined: as an inline `(comp ...)` argument
        ;; this read as a 2-arity `into` to two reviewers running, which
        ;; is reason enough to name it even though the 3-arity form was
        ;; correct.
        xform (comp (filter #(.endsWith (.getName ^File %) ".edn"))
                    (map #(<-wire (edn/read-string (slurp % :encoding "UTF-8")))))]
    (if-not (.isDirectory d)
      []
      (into [] xform (.listFiles d)))))
