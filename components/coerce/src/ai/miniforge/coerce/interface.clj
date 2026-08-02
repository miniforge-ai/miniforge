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
(ns ai.miniforge.coerce.interface
  "Tiny safe-coercion helpers shared across the OSS components.

   Replaces the duplicated

       (try (Integer/parseInt s) (catch Exception _ default))

   pattern that grew up across compliance-scanner, pr-sync, tui-views,
   web-dashboard, workflow-security-compliance, policy-pack,
   connector-sarif, and the cli base — and the duplicated
   Instant-only instant walker that grew up across cursor-store,
   workflow's checkpoint-store, and the etl base."
  (:require [clojure.walk :as walk])
  (:import [java.time Instant]
           [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(defn ^{:stratum 0} safe-parse-int
  "Parse `s` as a 32-bit integer. Returns `default` when `s` is nil,
   non-string, non-numeric, or out of range. The default `default` is
   `nil` so callers can pattern-match on the parsed-or-not distinction;
   pass `0` (or any sentinel) when the call site needs a guaranteed
   number.

   Pre-checks for nil/non-string up front so the common control-flow
   paths don't run through exception handling, and the catch is
   narrowed to the specific parse failure (`NumberFormatException`)."
  ([s] (safe-parse-int s nil))
  ([s default]
   (if-not (string? s)
     default
     (try (Integer/parseInt ^String s)
          (catch NumberFormatException _ default)))))

(defn ^{:stratum 0} safe-parse-long
  "Parse `s` as a 64-bit integer. Returns `default` when `s` is nil,
   non-string, non-numeric, or out of range. See [[safe-parse-int]] for
   the rationale on the up-front nil check and narrowed catch."
  ([s] (safe-parse-long s nil))
  ([s default]
   (if-not (string? s)
     default
     (try (Long/parseLong ^String s)
          (catch NumberFormatException _ default)))))

(defn ^{:stratum 0} safe-parse-double
  "Parse `s` as a double. Returns `default` when `s` is nil, non-string,
   or non-numeric. See [[safe-parse-int]] for the rationale on the
   up-front nil check and narrowed catch."
  ([s] (safe-parse-double s nil))
  ([s default]
   (if-not (string? s)
     default
     (try (Double/parseDouble ^String s)
          (catch NumberFormatException _ default)))))

(defn ^{:stratum 0} stringify-instants
  "Walk `v`, replacing every instant with its ISO-8601 string.
   Non-instant values are returned unchanged.

   Dispatches on the ACTUAL type. `clojure.core/inst?` admits BOTH
   `java.time.Instant` and `java.util.Date`, so a walker that tests
   only `(instance? Instant x)` lets a Date past untouched. That is
   the harder failure to notice, because a Date has a print form
   (`#inst`) that survives an EDN round-trip: nothing throws, the
   file is valid EDN, and the value simply arrives at the reader as
   a Date where the reader expected a string. Connector cursor
   watermarks were being silently dropped exactly that way.

   Unlike the strict `->iso` helpers at single-key write boundaries
   (effect-transaction, knowledge), this cannot throw on unrecognized
   input — it walks whole payloads in which most values are
   legitimately not timestamps."
  [v]
  (walk/postwalk
   (fn [x]
     (cond
       (instance? Instant x) (.toString ^Instant x)
       (instance? Date x)    (.toString (.toInstant ^Date x))
       :else                 x))
   v))
