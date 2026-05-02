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
   connector-sarif, and the cli base.")

;------------------------------------------------------------------------------ Layer 0
;; No in-namespace dependencies.

(defn safe-parse-int
  "Parse `s` as a 32-bit integer. Returns `default` on any failure
   (`nil`, non-numeric, overflow). The default `default` is `nil` so
   callers can pattern-match on the parsed-or-not distinction; pass
   `0` (or any sentinel) when the call site needs a guaranteed number."
  ([s] (safe-parse-int s nil))
  ([s default]
   (try (Integer/parseInt s)
        (catch Exception _ default))))

(defn safe-parse-long
  "Parse `s` as a 64-bit integer. Returns `default` on any failure."
  ([s] (safe-parse-long s nil))
  ([s default]
   (try (Long/parseLong s)
        (catch Exception _ default))))

(defn safe-parse-double
  "Parse `s` as a double. Returns `default` on any failure."
  ([s] (safe-parse-double s nil))
  ([s default]
   (try (Double/parseDouble s)
        (catch Exception _ default))))
