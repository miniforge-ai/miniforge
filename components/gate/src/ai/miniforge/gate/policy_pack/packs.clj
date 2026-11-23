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
(ns ai.miniforge.gate.policy-pack.packs
  "The shipped standards pack, read from the classpath once per process."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private standards-pack-resource
  "Classpath location of the compiled standards pack (emitted by
   `bb standards:pack`, placed on the classpath at build time)."
  "packs/miniforge-standards.pack.edn")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-standards-pack
  "Read the shipped standards pack manifest from the classpath.

   Returns nil ONLY when the resource is absent (a repo legitimately without a
   compiled pack). A present-but-malformed/unreadable pack THROWS — so
   `gate.interface/check-gate` converts it into a failed gate (fail-closed),
   rather than letting a corrupt pack masquerade as 'no pack' and silently
   skip enforcement."
  []
  (when-let [res (io/resource standards-pack-resource)]
    (edn/read-string (slurp res))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} standards-pack
  "Memoized shipped pack — read once per JVM (the classpath is stable for a
   process). A read failure is cached as a thrown deref, keeping enforcement
   fail-closed on every evaluation."
  (delay (read-standards-pack)))
