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

(ns ai.miniforge.datalevin.interface
  "Datalevin access behind one dialect bridge so the JVM dep doesn't leak.

   Datalevin/nippy are JVM-only, so under babashka this requires the
   `huahaiy/datalevin` pod (`pod.huahaiy.datalevin`) and under Clojure the
   library (`datalevin.core`); both expose the same connection / transact /
   query surface. Components that store in Datalevin require this namespace
   instead of `datalevin.core` directly, so the same `.cljc` store code runs
   under both runtimes and no component carries a JVM-only dep of its own.

   The pod must be declared in the consuming bb runtime's `bb.edn :pods`; `bb
   uberjar` embeds that declaration, so `bb --jar` auto-loads the pod at
   startup. Two thin helpers paper over pod-specific gaps — see `entity-map`
   and `transient-dir`."
  (:require
   ;; `:default` (not `:clj`) so static analysis of the non-bb branch still
   ;; binds `d`; bb takes the pod, every JVM dialect the library.
   #?(:bb      [pod.huahaiy.datalevin :as d]
      :default [datalevin.core :as d])))

;------------------------------------------------------------------------------ Layer 0
;; Datalevin surface — re-exported so callers depend on this boundary, not the
;; lib/pod namespace. `q` is variadic; aliasing the fn preserves that.

(def get-conn  "See `datalevin.core/get-conn`."  d/get-conn)
(def transact! "See `datalevin.core/transact!`." d/transact!)
(def db        "See `datalevin.core/db`."        d/db)
(def q         "See `datalevin.core/q`."         d/q)
(def pull      "See `datalevin.core/pull`."      d/pull)
(def close     "See `datalevin.core/close`."     d/close)

;------------------------------------------------------------------------------ Layer 1
;; Pod-safety helpers

(defn entity-map
  "Read the entity at `lookup` (a lookup-ref or entity id) from `database` as a
   plain map, or nil if it has no attributes.

   Uses `pull`, not `entity`: a Datalevin `Entity` isn't transit-serializable,
   so it can't cross the bb pod boundary, whereas `pull` returns a map. `:db/id`
   is dropped so the shape matches the older `(into {} (d/entity …))` callers."
  [database lookup]
  (some-> (pull database '[*] lookup)
          (dissoc :db/id)
          not-empty))

(defn transient-dir
  "A fresh writable directory, for an omitted store dir. Datalevin's in-memory
   (`nil`-dir) store isn't usable via the bb pod — the pod process can't create
   datalevin's own temp coordination dir — so the parent process creates a
   directory here and hands the pod an existing path."
  []
  ;; Host (JVM/bb) interop only — never cljs; silence the phantom-dialect pass.
  #_{:clj-kondo/ignore [:unresolved-symbol :unresolved-namespace]}
  (let [dir (str (java.io.File. (System/getProperty "java.io.tmpdir")
                                (str "miniforge-datalevin-" (java.util.UUID/randomUUID))))]
    (.mkdirs (java.io.File. dir))
    dir))
