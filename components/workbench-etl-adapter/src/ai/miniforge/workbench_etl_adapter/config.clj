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

(ns ai.miniforge.workbench-etl-adapter.config
  "Pure, deterministic inventory of an ETL resolved-run configuration.

   Miniforge accepts open-ended pipeline and environment maps, so the factor
   count belongs to each concrete run configuration rather than to a closed
   global enum. Maps and sequential collections are walked to scalar leaves;
   empty collections and sets are single leaves. Credential-bearing paths are
   counted as redacted controls and never copied into a snapshot."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical path/value primitives

(def inventory-schema-version "miniforge_resolved_run_factors/v1")

(def ^:private missing-value
  (content-hash/canonical-edn ::missing-factor))

(def ^:private secret-key-names
  "Exact configuration-key names whose values must never cross the adapter
   boundary. Matching exact names avoids treating harmless keys such as
   `:auth/token-endpoint` as credentials."
  #{"api-key" "api_key" "access-token" "access_token" "authorization"
    "client-secret"
    "client_secret" "credential-id" "credential_id" "password"
    "private-key" "private_key" "refresh-token" "refresh_token" "secret"
    "token"})

(defn- segment-name [segment]
  (cond
    (keyword? segment) (name segment)
    (symbol? segment)  (name segment)
    (string? segment)  segment
    :else              nil))

(defn- secret-path?
  "True when any keyword, symbol, or string segment names a
   credential-bearing value."
  [path]
  (boolean
   (some (fn [segment]
           (some-> segment segment-name str/lower-case secret-key-names))
         path)))

(defn- path-id
  "Stable, human-readable id for a leaf path. Canonical EDN preserves key
   namespaces, string stage names, and vector indexes without ambiguity."
  [path]
  (content-hash/canonical-edn path))

(defn- factor-value
  "Stable string representation that round-trips through JSON without losing
   Clojure value types such as keywords and sets."
  [value]
  (content-hash/canonical-edn value))

;------------------------------------------------------------------------------ Layer 1
;; Tree walk

(declare leaf-entries)

(defn- indexed-entries [path values]
  (mapcat (fn [[index value]]
            (leaf-entries (conj path index) value))
          (map-indexed vector values)))

(defn- map-entries [path value]
  (mapcat (fn [[key child]]
            (leaf-entries (conj path key) child))
          value))

(defn- leaf-entries
  "Return `[path value]` leaves beneath `value`. Empty collections remain
   addressable factors; sets remain atomic because set member indexes are not
   stable across serialization."
  [path value]
  (cond
    (map? value)        (if (seq value) (map-entries path value) [[path value]])
    (sequential? value) (if (seq value) (indexed-entries path value) [[path value]])
    (set? value)        [[path value]]
    :else               [[path value]]))

;------------------------------------------------------------------------------ Layer 2
;; Public inventory and diff

(defn factor-inventory
  "Inventory the concrete non-secret leaves in `resolved-run-config`.

   Returns the stable factor map used both for the defensible per-run N and for
   baseline/candidate one-factor checks. `:values` is safe to persist in
   snapshot metadata; secret values are omitted, not masked."
  [resolved-run-config]
  (let [entries         (leaf-entries [] resolved-run-config)
        redacted        (filter (comp secret-path? first) entries)
        visible         (remove (comp secret-path? first) entries)
        values          (into (sorted-map)
                              (map (fn [[path value]]
                                     [(path-id path) (factor-value value)]))
                              visible)]
    {:schema_version inventory-schema-version
     :factor_count   (count values)
     :redacted_count (count redacted)
     :config_hash    (str "sha256:" (content-hash/content-hash values))
     :values         values}))

(defn factor-diff
  "Return every changed factor between two persisted `:values` maps. Added and
   removed leaves are changes as well as unequal values."
  [baseline-values candidate-values]
  (let [paths (sort (set/union (set (keys baseline-values))
                               (set (keys candidate-values))))]
    (->> paths
         (keep (fn [path]
                 (let [baseline  (get baseline-values path missing-value)
                       candidate (get candidate-values path missing-value)]
                   (when (not= baseline candidate)
                     {:id path
                      :baseline baseline
                      :candidate candidate}))))
         vec)))
