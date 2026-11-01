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
(ns ai.miniforge.schema.instrument
  "Test-time checking of Malli function schemas. Production never checks
   a function's declared schema (`:malli/schema` metadata or `m/=>`); a
   test namespace turns the checks on with `instrument-fixture`."
  (:require
   [malli.instrument :as mi]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} instrument-fixture
  "clojure.test fixture: instrument every function schema declared in
   `ns-syms` while the wrapped tests run, then restore the original
   functions. A schema is declared as `:malli/schema` var metadata or
   with `m/=>`. An invalid argument list or return value throws ex-info
   whose ex-data `:type` is `:malli.core/invalid-input` or
   `:malli.core/invalid-output`. Throws IllegalArgumentException when
   `ns-syms` declare no function schema, so a fixture naming the wrong
   namespace cannot leave tests running unchecked.

   Example:
     (use-fixtures :once (schema/instrument-fixture 'ai.miniforge.gate.interface))"
  [& ns-syms]
  (let [filters [(apply mi/-filter-ns ns-syms)]]
    (fn [run-tests]
      (when (seq ns-syms)
        (mi/collect! {:ns ns-syms}))
      (when-not (some some? (mi/instrument! {:filters filters}))
        (throw (IllegalArgumentException.
                (str "instrument-fixture: no function schema declared in "
                     (pr-str ns-syms)))))
      (try
        (run-tests)
        (finally
          (mi/unstrument! {:filters filters}))))))
