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
(ns ai.miniforge.execution-grant.breach
  "Breach history: what a principal blew through, and whether we caught
   it or merely noticed (Ariadne step 2e, §13.5).

   THE HONEST HALF. Gates prevent what they can; logbooks catch the
   rest. Some conditions cannot be checked before the fact — a cost that
   only materializes mid-stream, an effect whose true outcome arrives
   later. Those are found by reconciliation, after the effect. A
   governance product that reports those as PREVENTED is claiming a
   capability it does not have, so every breach records which it was.

   Append-only by construction: one file per breach, never rewritten.
   A history you can edit is a history that stops being evidence."
  (:require
   [ai.miniforge.execution-grant.schema :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption]
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; Durable, append-only
(defn- ^{:stratum 0} ->iso
  "Timestamp -> ISO-8601, by actual type rather than by type hint.

   `:breach/at` is validated with `inst?`, and `inst?` admits
   `java.util.Date` as well as `java.time.Instant`. A Date's `.toString`
   is not ISO-8601, so persisting one would write a value `Instant/parse`
   cannot read back — corrupting the history at the moment of recording
   it. Same defect effect-transaction's store carried; fixed there and
   not swept here until review caught it."
  ^String [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v) (.toString (.toInstant ^Date v))
    :else (throw (ex-info "breach timestamp is not a supported instant type"
                          {:value v :type (some-> v class .getName)}))))

(defn- ^{:stratum 0} <-wire [m] (update m :breach/at #(Instant/parse %)))

(defn ^{:stratum 0} valid?
  "True when `b` satisfies the closed Breach schema."
  [b]
  (m/validate schema/Breach b))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} ->wire [b] (update b :breach/at ->iso))

(defn ^{:stratum 1} history
  "Every recorded breach under `dir`, optionally narrowed to one
   principal. Missing directory reads as empty, not as an error — a
   principal with no history is the normal case."
  ([dir] (history dir nil))
  ([dir principal]
   (let [^File d (io/file dir)
         xform (comp (filter #(.endsWith (.getName ^File %) ".edn"))
                     (map #(<-wire (edn/read-string (slurp % :encoding "UTF-8"))))
                     (filter #(or (nil? principal)
                                  (= principal (:breach/principal %)))))]
     (if-not (.isDirectory d)
       []
       (into [] xform (.listFiles d))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record!
  "Append one breach to `dir`. One file per breach, written atomically
   and never rewritten — the append-only property is structural rather
   than a rule someone has to remember.

   A malformed breach throws rather than persisting: a history entry
   nobody can read is worse than a loud failure at the moment of
   writing, because the alternative is discovering the gap during an
   audit."
  [dir b]
  (when-not (valid? b)
    (throw (ex-info "breach record failed validation" {:breach b})))
  (let [^File target (io/file dir (str (:breach/id b) ".edn"))
        _ (io/make-parents target)
        ;; Checked explicitly rather than left to the move. ATOMIC_MOVE
        ;; without REPLACE_EXISTING is documented as implementation-
        ;; specific when the target exists, and on POSIX it lowers to
        ;; rename(2), which overwrites silently. Append-only cannot rest
        ;; on a guarantee the platform does not actually make.
        _ (when (.exists target)
            (throw (ex-info "breach id already recorded; history is append-only"
                            {:breach/id (:breach/id b) :path (str target)})))
        ;; Unique per attempt. A deterministic <id>.edn.tmp lets two
        ;; writers of the same id trample each other before the move,
        ;; and leaves a lingering partial that reads like evidence
        ;; during manual inspection.
        ^File tmp (io/file (.getParentFile target)
                           (str (.getName target) "." (random-uuid) ".tmp"))]
    (spit tmp (pr-str (->wire b)) :encoding "UTF-8")
    ;; ATOMIC_MOVE WITHOUT REPLACE_EXISTING. Append-only has to be
    ;; enforced by the filesystem, not asserted in a docstring: with
    ;; REPLACE_EXISTING a reused :breach/id would silently overwrite an
    ;; earlier breach, which is precisely the edit this record exists to
    ;; make impossible. The move throws FileAlreadyExistsException
    ;; instead, and the caller learns rather than losing evidence.
    (Files/move (.toPath tmp) (.toPath target)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE]))
    b))
