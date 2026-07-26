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
(ns ai.miniforge.bb-test-runner.deps-config
  "Merge a repo's `deps.edn` map with one or more alias keys into a
   runnable `{:paths [...] :deps {...}}` config. Kept separate from
   `coverage-cmd` (its only caller, via `build-coverage-sdeps`) because
   folding it back in would re-create the over-budget chain this split
   exists to resolve — see `core.cljc`'s deferred `SL003` note.

   Layer 0: `normalized-alias-keys`.
   Layer 1: `merge-deps-config`, built on Layer 0.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} normalized-alias-keys
  [{:keys [alias-key alias-keys]}]
  (vec (or alias-keys
           (when alias-key [alias-key])
           [:test])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} merge-deps-config
  "Merge the root deps.edn data with one or more alias keys into a
   runnable config map of `{:paths [...], :deps {...}}`."
  [deps-config opts]
  (let [alias-keys (normalized-alias-keys opts)
        alias-configs (map #(get-in deps-config [:aliases %] {}) alias-keys)
        alias-paths (mapcat #(get % :extra-paths []) alias-configs)
        alias-deps (apply merge (map #(get % :extra-deps {}) alias-configs))
        base-paths (get deps-config :paths [])
        base-deps (get deps-config :deps {})]
    {:paths (vec (concat base-paths alias-paths))
     :deps (merge base-deps alias-deps)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (merge-deps-config {:paths ["src"]
                      :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                      :aliases {:test {:extra-paths ["test"]}}}
                     {:alias-key :test})

  :leave-this-here)
