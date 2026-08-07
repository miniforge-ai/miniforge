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
(ns ai.miniforge.execution-grant.temporal
  "Timestamp normalization for the two concrete types admitted by
   Clojure's `inst?` predicate."
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ->instant
  "Normalize an `inst?` value to `java.time.Instant`.

   Callers are inside the execution-grant boundary and may therefore
   trust that `value` already satisfies `inst?`."
  ^Instant [value]
  (if (instance? Instant value)
    value
    (.toInstant ^Date value)))
