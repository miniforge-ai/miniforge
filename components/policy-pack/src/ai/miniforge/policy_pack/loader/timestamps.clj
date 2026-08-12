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
(ns ai.miniforge.policy-pack.loader.timestamps
  "Timestamp wire form for policy pack manifests.

   `:pack/created-at`, `:pack/updated-at`, and `:pack/signed-at` cross the
   disk boundary as ISO-8601 strings and are parsed back to `Instant` on
   read. Extracted from `ai.miniforge.policy-pack.loader` (rule 210: the
   combined namespace measured 5 real layers, max 3)."
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} map-instant-keys
  "Apply `f` to every timestamp key PRESENT in `pack`. Those keys cross
   the disk boundary as ISO-8601 strings, parsed back on read.

   Presence, not non-nil-ness: an absent `:pack/signed-at` is legitimately
   optional, but a key explicitly holding nil is a bad timestamp and goes
   to `f` like any other. A missing required one stays missing so the
   schema reports it, rather than being invented here."
  [pack f]
  (reduce (fn [acc k]
            (if (contains? acc k)
              (assoc acc k (f (get acc k)))
              acc))
          pack
          [:pack/created-at :pack/updated-at :pack/signed-at]))

(defn ^{:stratum 0} ->iso
  "Timestamp -> ISO-8601 string, by actual type rather than by print form.

   `inst?` admits BOTH `java.time.Instant` and `java.util.Date`, and
   `pprint` renders them differently: a Date prints as `#inst` and reads
   back, an Instant prints as `#object[...]` and makes the whole file
   unreadable by `edn/read-string`. `builders/make-pack` stamps an
   Instant, so the unreadable form is the default one.

   Both are normalized; anything else throws, strings included — one that
   does not parse would persist fine and fail only on the way back out.
   Same defect class as effect-transaction's store and zettel frontmatter."
  ^String [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v) (.toString (.toInstant ^Date v))
    :else (throw (ex-info "policy pack timestamp is not a supported instant type"
                          {:value v :type (some-> v class .getName)}))))

(defn ^{:stratum 0} ensure-instant
  "Wire timestamp -> Instant, or nil when the value is not a readable one.

   Returns nil rather than the old fail-soft `Instant/now`. A pack whose
   created-at cannot be read is corrupt, and stamping the current time
   hides that at the moment it should surface — the pack loads clean,
   dated today, and nothing downstream can tell. nil fails the schema's
   `inst?` on the next step, so the corruption surfaces through the
   loader's own `{:success? false :errors [...]}` channel: one entry in
   `load-all-packs`' `:failed`, not an exception aborting the rest."
  [value]
  (cond
    (instance? Instant value) value
    (instance? Date value) (.toInstant ^Date value)
    (string? value) (try
                      (Instant/parse value)
                      (catch Exception _ nil))
    :else nil))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (->iso (Instant/now))
  (ensure-instant "2026-08-01T12:34:56.789Z")
  (map-instant-keys {:pack/created-at (Instant/now)} ->iso)

  :leave-this-here)
