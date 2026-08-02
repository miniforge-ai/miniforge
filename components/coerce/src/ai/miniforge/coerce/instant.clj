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
(ns ai.miniforge.coerce.instant
  "Instant normalization for values crossing a serialization boundary.

   Replaces the Instant-only postwalk that grew up independently in
   cursor-store, workflow's checkpoint-store, and the etl base — each
   of which matched `java.time.Instant` and silently let a
   `java.util.Date` past."
  (:require [clojure.walk :as walk])
  (:import [java.time Instant]
           [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ->iso
  "Instant -> ISO-8601 string, dispatched on the ACTUAL type.
   Anything that is not an instant is returned unchanged.

   `clojure.core/inst?` admits BOTH `java.time.Instant` and
   `java.util.Date`, so a normalizer that tests only
   `(instance? Instant x)` lets a Date past. That is the harder
   failure to notice: a Date has a print form (`#inst`) that survives
   an EDN round-trip, so nothing throws, the file stays valid EDN, and
   the value simply arrives at the reader as a Date where a string was
   expected.

   Passing non-instants through is what separates this from the strict
   `->iso` helpers in effect-transaction and knowledge. Those guard a
   single key that is always supposed to hold a timestamp, so they can
   throw on anything else. This one is applied to every value in a
   payload, most of which legitimately are not timestamps."
  [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v)    (.toString (.toInstant ^Date v))
    :else                 v))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stringify-instants
  "Walk `v`, replacing every instant with its ISO-8601 string."
  [v]
  (walk/postwalk ->iso v))
