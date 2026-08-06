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
(ns ai.miniforge.policy-pack.registry
  "Policy pack registry protocol and in-memory implementation.

   Layer 0: PolicyPackRegistry protocol, parse-datever, glob-matches?,
     dedupe-by-id
   Layer 1: compare-versions, rule-applies? (over parse-datever/glob-matches?)
   Layer 2: latest-version (over compare-versions)
   Layer 3: InMemoryPackRegistry (implements the Layer 0 protocol, uses
     latest-version/rule-applies?/dedupe-by-id)
   Layer 4: create-registry (constructs InMemoryPackRegistry)

   5 real strata — over the rule 210 budget of 3; a genuine namespace split
   (Wave 2), not a labeling problem."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.messages.interface :as messages]
   [ai.miniforge.policy-pack.canonical-edn :as canonical-edn]
   [ai.miniforge.policy-pack.crypto :as crypto]
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.trust-roots :as trust-roots]
   [ai.miniforge.policy-pack.trust-roots-config :as trust-roots-config]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/policy-pack/messages/system.edn"
                              :policy-pack/system))

;; Protocol definition
(defprotocol ^{:stratum 0} PolicyPackRegistry
  "Protocol for managing policy packs.

   Provides CRUD operations, import/export, validation,
   and composition capabilities for policy packs."

  ;; CRUD
  (register-pack [this pack]
    "Register a new pack or new version of existing pack.
     Returns the registered pack or an anomaly map on validation failure.")

  (get-pack [this pack-id]
    "Get latest version of pack.
     Returns pack or nil if not found.")

  (get-pack-version [this pack-id version]
    "Get specific version of pack.
     Returns pack or nil if not found.")

  (list-packs [this criteria]
    "List packs matching criteria.
     Criteria map:
     - :category - Filter by category prefix
     - :author - Filter by author
     - :search - Text search in name/description
     Returns vector of pack summaries.")

  (delete-pack [this pack-id version]
    "Remove a pack version.
     Returns true if deleted, false if not found.")

  ;; Import/Export
  (import-pack [this source]
    "Import pack from URL, file path, or git repo.
     Source can be:
     - String file path
     - Map with :type (:file, :url, :git)
     Returns imported pack or throws.")

  (export-pack [this pack-id version format]
    "Export pack to specified format.
     Format: :edn, :json, or :directory
     Returns exported data as string/map, or an anomaly map when the pack
     is not found.")

  ;; Validation
  (validate-pack [this pack]
    "Validate pack schema and rule consistency.
     Returns {:valid? bool :errors [...]}.")

  (verify-signature [this pack]
    "Verify pack signature against the configured trust roots (N4 §8.2).
     Returns {:verified? bool :reason string}, plus :signer and :timestamp
     when the pack carries a signature — an unsigned pack has neither, and
     reporting a signer for one would imply something signed it. :signer is
     the identifier the pack CLAIMS; it is verified only when :verified? is
     true.")

  ;; Composition
  (resolve-pack [this pack-id]
    "Resolve pack with all extended packs merged.
     Returns fully resolved pack with inherited rules.")

  (get-rules-for-context [this pack-ids context]
    "Get applicable rules given task context.
     Context map should contain:
     - :task - Task map with :task/intent
     - :artifact - Artifact being checked
     - :repo - Repository metadata
     - :phase - Current workflow phase
     Returns vector of applicable rules."))

;; Version comparison helpers
(defn ^{:stratum 0} parse-datever
  "Parse DateVer string (YYYY.MM.DD) into comparable vector."
  [version-str]
  (when version-str
    (try
      (mapv parse-long (str/split version-str #"\."))
      (catch Exception _
        nil))))

;; Rule applicability checking
(defn ^{:stratum 0} glob-matches?
  "Simple glob pattern matching.
   Supports * (any within segment) and ** (any path segments)."
  [pattern path]
  (let [regex-pattern (str "^"
                          (-> pattern
                              (str/replace "." "\\.")
                              (str/replace "**/" "<<<GLOBSTAR_SLASH>>>")
                              (str/replace "**" "<<<GLOBSTAR>>>")
                              (str/replace "*" "[^/]*")
                              (str/replace "<<<GLOBSTAR_SLASH>>>" "(.*/)?")
                              (str/replace "<<<GLOBSTAR>>>" ".*"))
                          "$")]
    (try
      (boolean (re-matches (re-pattern regex-pattern) path))
      (catch Exception _
        false))))

(defn ^{:stratum 0} decode-signature
  "Base64 pack signature to bytes; nil when the field is not a string or not
   base64. A pack reaching verification has not necessarily been through
   schema validation, so the type check belongs here."
  [sig-str]
  (when (string? sig-str)
    (try
      (.decode (java.util.Base64/getDecoder) ^String sig-str)
      (catch IllegalArgumentException _
        nil))))

(defn ^{:stratum 0} dedupe-by-id
  "Remove duplicate rules, keeping the last occurrence (later pack wins)."
  [rules]
  (vals (reduce (fn [acc rule]
                  (assoc acc (:rule/id rule) rule))
                {}
                rules)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-versions
  "Compare two DateVer version strings.
   Returns negative if a < b, 0 if equal, positive if a > b."
  [a b]
  (let [va (or (parse-datever a) [0 0 0])
        vb (or (parse-datever b) [0 0 0])]
    (compare va vb)))

(defn ^{:stratum 1} rule-applies?
  "Check if a rule applies to the given context.

   Context map:
   - :task - Task with :task/intent containing :intent/type
   - :artifact - Artifact with :artifact/path, :artifact/type
   - :repo - Repository with :repo/type
   - :phase - Current workflow phase keyword"
  [rule context]
  (let [{:keys [task-types file-globs repo-types phases]}
        (:rule/applies-to rule)]
    (and
     ;; Task type filter
     (or (nil? task-types)
         (empty? task-types)
         (contains? task-types (get-in context [:task :task/intent :intent/type])))

     ;; File glob filter
     (or (nil? file-globs)
         (empty? file-globs)
         (let [path (get-in context [:artifact :artifact/path] "")]
           (some #(glob-matches? % path) file-globs)))

     ;; Repo type filter
     (or (nil? repo-types)
         (empty? repo-types)
         (contains? repo-types (get-in context [:repo :repo/type])))

     ;; Phase filter
     (or (nil? phases)
         (empty? phases)
         (contains? phases (:phase context))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} latest-version
  "Get the latest version from a collection of version strings."
  [versions]
  (when (seq versions)
    (first (sort-by identity (comparator #(pos? (compare-versions %1 %2))) versions))))

;------------------------------------------------------------------------------ Layer 3

;; In-memory registry implementation. `trust-roots` (N4 §8.2.1) sits outside
;; `state` because it is fixed configuration, not state a caller mutates.
(defrecord ^{:stratum 3} InMemoryPackRegistry [state trust-roots]
  PolicyPackRegistry

  (register-pack [_this pack]
    (let [{:keys [valid? errors]} (schema/validate-pack pack)]
      (if valid?
        (let [pack-id (:pack/id pack)
              version (:pack/version pack)]
          (swap! state assoc-in [:packs pack-id version] pack)
          pack)
        (anomaly/sub-anomaly :invalid-input
                             :anomalies/incorrect
                             "Invalid pack schema"
                             {:errors errors
                              :pack-id (:pack/id pack)}))))

  (get-pack [this pack-id]
    (let [versions (get-in @state [:packs pack-id])]
      (when (seq versions)
        (let [latest (latest-version (keys versions))]
          (get-pack-version this pack-id latest)))))

  (get-pack-version [_this pack-id version]
    (get-in @state [:packs pack-id version]))

  (list-packs [_this criteria]
    (let [{:keys [category author search]} criteria
          all-packs (for [[_pack-id versions] (:packs @state)
                          [_version pack] versions]
                      pack)]
      (->> all-packs
           (filter (fn [pack]
                     (and (or (nil? category)
                              (some #(str/starts-with? (:category/id %) category)
                                    (:pack/categories pack)))
                          (or (nil? author)
                              (= author (:pack/author pack)))
                          (or (nil? search)
                              (str/includes? (str/lower-case (:pack/name pack ""))
                                             (str/lower-case search))
                              (str/includes? (str/lower-case (:pack/description pack ""))
                                             (str/lower-case search))))))
           ;; Return summaries
           (map (fn [pack]
                  {:pack/id (:pack/id pack)
                   :pack/name (:pack/name pack)
                   :pack/version (:pack/version pack)
                   :pack/author (:pack/author pack)
                   :pack/description (:pack/description pack)
                   :rule-count (count (:pack/rules pack))}))
           (distinct)
           vec)))

  (delete-pack [_this pack-id version]
    (if (get-in @state [:packs pack-id version])
      (do
        (swap! state update-in [:packs pack-id] dissoc version)
        ;; Clean up empty pack-id entry
        (when (empty? (get-in @state [:packs pack-id]))
          (swap! state update :packs dissoc pack-id))
        true)
      false))

  (import-pack [this source]
    ;; Delegate to loader - this is a placeholder
    ;; Real implementation would handle :file, :url, :git sources
    (if (map? source)
      ;; Assume source is already a pack map
      (register-pack this source)
      ;; String path - would need loader integration
      (response/throw-anomaly! :anomalies/unsupported
                               "File/URL import not implemented in registry"
                               {:source source
                                :hint "Use loader/load-pack-from-file instead"})))

  (export-pack [_this pack-id version format]
    (if-let [pack (get-in @state [:packs pack-id version])]
      (case format
        :edn (pr-str pack)
        :json (response/throw-anomaly! :anomalies/unsupported
                                       "JSON export not implemented"
                                       {:format format})
        :directory (response/throw-anomaly! :anomalies/unsupported
                                            "Directory export not implemented"
                                            {:format format})
        (response/throw-anomaly! :anomalies/incorrect
                                 "Unknown export format"
                                 {:format format}))
      (anomaly/sub-anomaly :not-found
                           :anomalies/not-found
                           "Pack not found"
                           {:pack-id pack-id
                            :version version})))

  (validate-pack [_this pack]
    (schema/validate-pack pack))

  (verify-signature [_this pack]
    ;; N4 §8.2: the key comes from the trust roots, resolved by the
    ;; identifier the pack names — never from the pack itself.
    (let [key-id  (:pack/signed-by pack)
          sig-str (:pack/signature pack)
          claimed {:signer key-id :timestamp (:pack/signed-at pack)}]
      (cond
        (nil? sig-str)
        {:verified? false :reason (t :verify/unsigned)}

        (not (and (string? key-id) (seq key-id)))
        (assoc claimed :verified? false :reason (t :verify/missing-key-id))

        :else
        (if-let [pub-bytes (trust-roots/resolve-key trust-roots key-id)]
          (if-let [sig-bytes (decode-signature sig-str)]
            (merge claimed
                   (crypto/verify-ed25519 (canonical-edn/pack-signable-bytes pack)
                                          sig-bytes
                                          pub-bytes))
            (assoc claimed :verified? false
                   :reason (t :crypto/undecodable-signature)))
          (assoc claimed :verified? false
                 :reason (t :verify/untrusted-key-id))))))

  (resolve-pack [this pack-id]
    (when-let [pack (get-pack this pack-id)]
      (if-let [extends (:pack/extends pack)]
        ;; Recursively resolve parent packs and merge
        (let [parent-rules (mapcat (fn [{:keys [pack-id]}]
                                     (when-let [parent (resolve-pack this pack-id)]
                                       (:pack/rules parent)))
                                   extends)
              ;; Child rules override parent rules by ID
              child-rules (:pack/rules pack)
              child-ids (set (map :rule/id child-rules))
              merged-rules (concat
                            (remove #(child-ids (:rule/id %)) parent-rules)
                            child-rules)]
          (assoc pack :pack/rules (vec merged-rules)))
        ;; No extensions, return as-is
        pack)))

  (get-rules-for-context [this pack-ids context]
    (let [resolved-packs (keep #(resolve-pack this %) pack-ids)
          all-rules (mapcat :pack/rules resolved-packs)]
      (->> all-rules
           (filter #(rule-applies? % context))
           (dedupe-by-id)
           vec))))

;------------------------------------------------------------------------------ Layer 4

;; Registry constructors
(defn ^{:stratum 4} create-registry
  "Create an in-memory policy pack registry.

   Options:
   - :logger      - Logger instance for structured logging
   - :trust-roots - Trusted-publisher store for signature verification
                    (see policy-pack.trust-roots). Defaults to the
                    configured store, which ships empty — with nothing
                    configured, no signed pack verifies.

   Example:
     (create-registry)
     (create-registry {:logger my-logger})"
  ([]
   (create-registry {}))
  ([opts]
   (->InMemoryPackRegistry (atom {:packs {}})
                           (or (:trust-roots opts)
                               (trust-roots-config/load-trust-roots)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create registry
  (def reg (create-registry))

  ;; Register a pack
  (register-pack reg
                 {:pack/id "test-pack"
                  :pack/name "Test Pack"
                  :pack/version "2026.01.22"
                  :pack/description "A test policy pack"
                  :pack/author "test-author"
                  :pack/categories [{:category/id "300"
                                     :category/name "Infrastructure"
                                     :category/rules [:test-rule]}]
                  :pack/rules [{:rule/id :test-rule
                                :rule/title "Test Rule"
                                :rule/description "A test rule"
                                :rule/severity :high
                                :rule/category "300"
                                :rule/applies-to {}
                                :rule/detection {:type :content-scan
                                                 :pattern "TODO"}
                                :rule/enforcement {:action :warn
                                                   :message "Found TODO"}}]
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)})

  ;; Get pack
  (get-pack reg "test-pack")

  ;; List packs
  (list-packs reg {:author "test-author"})

  ;; Get applicable rules
  (get-rules-for-context reg
                         ["test-pack"]
                         {:artifact {:artifact/path "main.tf"}
                          :phase :implement})

  ;; Version comparison
  (compare-versions "2026.01.22" "2026.01.15")
  ;; => 7 (positive, first is later)

  (latest-version ["2025.12.01" "2026.01.22" "2026.01.15"])
  ;; => "2026.01.22"

  ;; Glob matching
  (glob-matches? "**/*.tf" "modules/vpc/main.tf")
  ;; => true

  (glob-matches? "*.tf" "main.tf")
  ;; => true

  (glob-matches? "*.tf" "modules/main.tf")
  ;; => false

  :leave-this-here)
