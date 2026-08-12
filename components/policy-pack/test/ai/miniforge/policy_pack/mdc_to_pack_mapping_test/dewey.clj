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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.dewey
  "Dewey-code → phase-set mapping, part of the reference implementation
   for the MDC-to-Pack Rule Field Mapping acceptance spec (Section 2 and
   Section 4 of work/designs/mdc-to-pack-field-mapping.edn).

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210: slice 1/7 of a stratum-lint SL003 split train — that
   namespace measured 6 real layers, max 3. Same approach as the
   mdc_compiler.clj split train, miniforge#1729-#1743). This was the
   deepest same-file chain in the parent namespace:
   `dewey-range-to-phases` fed `dewey-ranges`, which fed
   `dewey-to-phases`, which in turn fed `build-applies-to` and
   `compile-rule`. Moving it to its own namespace lets every downstream
   consumer reach it as a cross-namespace call, which does not count
   toward the caller's own layer budget.

   Layer 0: parse-dewey, dewey-range-to-phases, default-phases, all-phases
   Layer 1: dewey-ranges
   Layer 2: dewey-to-phases")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} parse-dewey
  "Parse dewey string to integer. Returns -1 on failure (outside all ranges)."
  [dewey-str]
  (try
    (Integer/parseInt (str dewey-str))
    (catch Exception _ -1)))

(def ^{:stratum 0} dewey-range-to-phases
  "Dewey range → phase set mapping from Section 2 of the design."
  [[0 99]    #{:plan :implement :review :verify :release}
   [100 199] #{:implement :review}
   [200 299] #{:implement :review}
   [300 399] #{:plan :implement :review}
   [400 499] #{:implement :verify}
   [500 599] #{:implement :review}
   [600 699] #{:implement :review}
   [700 799] #{:plan :implement :review :verify :release}
   [800 899] #{:implement :review}
   [900 999] #{}])

(def ^{:stratum 0} default-phases #{:implement :review})

(def ^{:stratum 0} all-phases #{:plan :implement :review :verify :release})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} dewey-ranges
  "Parsed vector of [low high phases] triples."
  (->> (partition 2 dewey-range-to-phases)
       (mapv (fn [[[lo hi] phases]] [lo hi phases]))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} dewey-to-phases
  "Look up phases for a dewey code (string). Falls back to default."
  [dewey-str]
  (let [code (parse-dewey dewey-str)]
    (or (some (fn [[lo hi phases]]
                (when (<= lo code hi) phases))
              dewey-ranges)
        default-phases)))
