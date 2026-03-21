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

   Layer 0: Protocol definition
   Layer 1: In-memory registry implementation
   Layer 2: Registry constructors"
  (:require
   [ai.miniforge.policy-pack.schema :as schema]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol definition

(defprotocol PolicyPackRegistry
  "Protocol for managing policy packs.

   Provides CRUD operations, import/export, validation,
   and composition capabilities for policy packs."

  ;; CRUD
  (register-pack [this pack]
    "Register a new pack or new version of existing pack.
     Returns the registered pack.")

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
     Returns exported data as string or map.")

  ;; Validation
  (validate-pack [this pack]
    "Validate pack schema and rule consistency.
     Returns {:valid? bool :errors [...]}.")

  (verify-signature [this pack]
    "Verify pack signature (paid feature).
     Returns {:verified? bool :signer string :timestamp inst}.")

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

;------------------------------------------------------------------------------ Layer 0
;; Version comparison helpers

(defn parse-datever
  "Parse DateVer string (YYYY.MM.DD) into comparable vector."
  [version-str]
  (when version-str
    (try
      (mapv parse-long (str/split version-str #"\."))
      (catch Exception _
        nil))))

(defn compare-versions
  "Compare two DateVer version strings.
   Returns negative if a < b, 0 if equal, positive if a > b."
  [a b]
  (let [va (or (parse-datever a) [0 0 0])
        vb (or (parse-datever b) [0 0 0])]
    (compare va vb)))

(defn latest-version
  "Get the latest version from a collection of version strings."
  [versions]
  (when (seq versions)
    (first (sort-by identity (comparator #(pos? (compare-versions %1 %2))) versions))))

;------------------------------------------------------------------------------ Layer 0
;; Rule applicability checking

(defn glob-matches?
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

(defn rule-applies?
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

(defn dedupe-by-id
  "Remove duplicate rules, keeping the last occurrence (later pack wins)."
  [rules]
  (vals (reduce (fn [acc rule]
                  (assoc acc (:rule/id rule) rule))
                {}
                rules)))

;------------------------------------------------------------------------------ Layer 1
;; In-memory registry implementation

(defrecord InMemoryPackRegistry [state]
  PolicyPackRegistry

  (register-pack [_this pack]
    (let [{:keys [valid? errors]} (schema/validate-pack pack)]
      (if valid?
        (let [pack-id (:pack/id pack)
              version (:pack/version pack)]
          (swap! state assoc-in [:packs pack-id version] pack)
          pack)
        (throw (ex-info "Invalid pack schema"
                        {:errors errors
                         :pack-id (:pack/id pack)})))))

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
      (throw (ex-info "File/URL import not implemented in registry"
                      {:source source
                       :hint "Use loader/load-pack-from-file instead"}))))

  (export-pack [_this pack-id version format]
    (if-let [pack (get-in @state [:packs pack-id version])]
      (case format
        :edn (pr-str pack)
        :json (throw (ex-info "JSON export not implemented" {:format format}))
        :directory (throw (ex-info "Directory export not implemented" {:format format}))
        (throw (ex-info "Unknown export format" {:format format})))
      (throw (ex-info "Pack not found"
                      {:pack-id pack-id
                       :version version}))))

  (validate-pack [_this pack]
    (schema/validate-pack pack))

  (verify-signature [_this pack]
    ;; Signature verification is a paid feature - stub implementation
    (if (:pack/signature pack)
      {:verified? false
       :reason "Signature verification not implemented (paid feature)"
       :signer (:pack/signed-by pack)
       :timestamp (:pack/signed-at pack)}
      {:verified? false
       :reason "Pack is not signed"}))

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

;------------------------------------------------------------------------------ Layer 2
;; Registry constructors

(defn create-registry
  "Create an in-memory policy pack registry.

   Options:
   - :logger - Logger instance for structured logging

   Example:
     (create-registry)
     (create-registry {:logger my-logger})"
  ([]
   (create-registry {}))
  ([_opts]
   (->InMemoryPackRegistry (atom {:packs {}}))))

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
                                :rule/severity :major
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
