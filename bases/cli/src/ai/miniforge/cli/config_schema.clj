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
(ns ai.miniforge.cli.config-schema
  "Which config.edn keys `mf config set` and `mf config validate` accept,
   and the type of each value, from config/cli/config-schema.edn. A key
   typed :map takes any sub-key (per-backend thresholds, governance
   overrides). Checking returns problems as data; config.clj prints them."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} schema-resource
  "Classpath resource holding the config key schema."
  "config/cli/config-schema.edn")

(defn ^{:stratum 0} key-type
  "The type `schema` gives `key-path`: its own, or :any below a :map key.
   nil for a key the schema does not know."
  [schema key-path]
  (or (get schema key-path)
      (some (fn [n] (when (= :map (get schema (subvec key-path 0 n))) :any))
            (range 1 (count key-path)))))

(defn ^{:stratum 0} section?
  "True when `key-path` is a section of the schema: a prefix of its keys,
   such as [:llm] or [:observability :fleet]."
  [schema key-path]
  (let [n (count key-path)]
    (boolean (some (fn [k] (and (> (count k) n) (= key-path (subvec k 0 n))))
                   (keys schema)))))

(defn ^{:stratum 0} value-ok?
  "True when `value` fits `type`. nil fits every type: `config set` removes
   the key for it (see config/dissoc-in), so the default applies.
   `backends` is the set of known backend keywords."
  [type value backends]
  (or (nil? value)
      (case type
        :keyword (keyword? value)
        :string  (string? value)
        :boolean (boolean? value)
        :integer (integer? value)
        :number  (number? value)
        :vector  (vector? value)
        :map     (map? value)
        :port    (and (integer? value) (<= 1 value 65535))
        :backend (contains? backends value)
        :any     true
        false)))

(defn ^{:stratum 0} leaf-paths
  "The key paths of every leaf of `value` below `path`: [path] itself when
   `value` is not a non-empty map. An unknown key is reported by the full
   key that was set (made.up.key), not its first segment."
  [path value]
  (if (and (map? value) (seq value))
    (mapcat (fn [[k v]] (leaf-paths (conj path k) v)) value)
    [path]))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} schema
  "{key-path type} for every settable config key."
  (:config/keys (edn/read-string (slurp (io/resource schema-resource)))))

(defn ^{:stratum 1} problems
  "Every key of `config` the schema does not know, and every value of the
   wrong type, as [{:path key-path :problem :unknown-key}] or
   [{:path key-path :problem :wrong-type :expected type :value value}].
   Empty when the config fits."
  [schema backends config]
  (letfn [(walk [prefix m]
            (mapcat (fn [[k v]]
                      (let [path (conj prefix k)
                            type (key-type schema path)]
                        (cond
                          (and (nil? type) (section? schema path))
                          (if (map? v)
                            (walk path v)
                            [{:path path :problem :wrong-type :expected :map :value v}])

                          (nil? type)
                          (for [p (leaf-paths path v)]
                            {:path p :problem :unknown-key})

                          (value-ok? type v backends)
                          []

                          :else
                          [{:path path :problem :wrong-type :expected type :value v}])))
                    m))]
    (vec (walk [] config))))
