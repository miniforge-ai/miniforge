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
(ns ai.miniforge.tenancy.instant
  "One canonical instant representation for identity that gets persisted.

   WHY THIS EXISTS. The acting context is written into the workflow
   machine snapshot, and that snapshot puts every value through
   `coerce/stringify-instants`. A field typed `inst?` therefore goes in
   an `Instant` and comes back a `String`, so a context that validated
   on the way in fails its own validation on the way out. Rather than
   coerce at every boundary that reads one, the stored form IS the
   string, and this is the one place that produces and recognizes it."
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} iso-instant?
  "True for a string that reads back as an instant."
  [x]
  (and (string? x)
       (try (Instant/parse x) true
            (catch Exception _ false))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ->iso
  "Normalize an instant to the canonical ISO-8601 string, or nil.

   Takes `Instant` and `Date` both, because `inst?` — which every
   instant-bearing schema here uses — admits either, so a caller holding
   a schema-valid instant may hold either one. Assuming `Instant` and
   calling `str` on a `Date` yields 'Sat Aug 16 ...', which is not a
   parseable instant and would fail validation far from its cause."
  [now]
  (cond
    (instance? Instant now) (str now)
    (instance? Date now) (str (.toInstant ^Date now))
    (iso-instant? now) now
    :else nil))
