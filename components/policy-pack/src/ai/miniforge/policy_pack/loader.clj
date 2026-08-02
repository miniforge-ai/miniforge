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
(ns ai.miniforge.policy-pack.loader
  "Load policy packs from EDN files and directory structures.

   Layer 0: File discovery and path utilities
   Layer 1: EDN parsing and validation
   Layer 2: Single file and directory loaders
   Layer 3: Pack loading orchestration
   Layer 4: Trust validation (N1 §2.10.2)

   Supports two formats:
   - Single EDN file: pack.edn or *.pack.edn
   - Directory structure with pack.edn manifest and rules/ subdirectory

   Trust validation ensures:
   - Instruction authority is not transitive
   - Trust level inheritance (lowest wins)
   - Cross-trust references are validated
   - Tainted content is isolated from instruction authority"
  (:require
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as dep-validation]
   [ai.miniforge.knowledge.interface :as knowledge]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.set]
   [clojure.string :as str])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; File discovery and path utilities
(defn ^{:stratum 0} find-rule-files
  "Find all rule .edn files in a rules/ directory, recursive."
  [rules-dir]
  (when (.exists (io/file rules-dir))
    (->> (file-seq (io/file rules-dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".edn")))))

(defn ^{:stratum 0} pack-file?
  "Check if a file is a pack file (pack.edn or *.pack.edn)."
  [file]
  (let [name (.getName file)]
    (or (= name "pack.edn")
        (str/ends-with? name ".pack.edn"))))

;; EDN parsing and validation
(defn ^{:stratum 0} safe-read-edn
  "Safely read EDN from a file.
   Returns {:success? bool :data any :error string}."
  [file]
  (try
    (let [content (slurp file)
          data (edn/read-string content)]
      (schema-validation/success :data data {:error nil}))
    (catch Exception e
      (schema-validation/failure :data (.getMessage e)))))

;; Timestamp wire form
(defn ^{:stratum 0} map-instant-keys
  "Apply `f` to every timestamp key PRESENT in `pack`. Those keys cross
   the disk boundary as ISO-8601 strings, parsed back on read.

   Presence, not non-nil-ness: an absent `:pack/signed-at` is legitimately
   optional, but a key explicitly holding nil is a bad timestamp and goes
   to `f` like any other. A missing required one stays missing so the
   schema reports it, rather than being invented here."
  [pack f]
  (reduce (fn [acc k]
            (if (contains? acc k)
              (assoc acc k (f (get acc k)))
              acc))
          pack
          [:pack/created-at :pack/updated-at :pack/signed-at]))

(defn ^{:stratum 0} ->iso
  "Timestamp -> ISO-8601 string, by actual type rather than by print form.

   `inst?` admits BOTH `java.time.Instant` and `java.util.Date`, and
   `pprint` renders them differently: a Date prints as `#inst` and reads
   back, an Instant prints as `#object[...]` and makes the whole file
   unreadable by `edn/read-string`. `builders/make-pack` stamps an
   Instant, so the unreadable form is the default one.

   Both are normalized; anything else throws, strings included — one that
   does not parse would persist fine and fail only on the way back out.
   Same defect class as effect-transaction's store and zettel frontmatter."
  ^String [v]
  (cond
    (instance? Instant v) (.toString ^Instant v)
    (instance? Date v) (.toString (.toInstant ^Date v))
    :else (throw (ex-info "policy pack timestamp is not a supported instant type"
                          {:value v :type (some-> v class .getName)}))))

(defn ^{:stratum 0} ensure-instant
  "Wire timestamp -> Instant, or nil when the value is not a readable one.

   Returns nil rather than the old fail-soft `Instant/now`. A pack whose
   created-at cannot be read is corrupt, and stamping the current time
   hides that at the moment it should surface — the pack loads clean,
   dated today, and nothing downstream can tell. nil fails the schema's
   `inst?` on the next step, so the corruption surfaces through the
   loader's own `{:success? false :errors [...]}` channel: one entry in
   `load-all-packs`' `:failed`, not an exception aborting the rest."
  [value]
  (cond
    (instance? Instant value) value
    (instance? Date value) (.toInstant ^Date value)
    (string? value) (try
                      (Instant/parse value)
                      (catch Exception _ nil))
    :else nil))

(defn ^{:stratum 0} normalize-rule
  "Normalize a rule, ensuring required fields and types."
  [rule]
  (cond-> rule
    (not (:rule/applies-to rule))
    (assoc :rule/applies-to {})

    (get-in rule [:rule/applies-to :task-types])
    (update-in [:rule/applies-to :task-types] #(if (set? %) % (set %)))

    (get-in rule [:rule/applies-to :repo-types])
    (update-in [:rule/applies-to :repo-types] #(if (set? %) % (set %)))

    (get-in rule [:rule/applies-to :phases])
    (update-in [:rule/applies-to :phases] #(if (set? %) % (set %)))))

;; Overlay pack resolution (N4 §2.5)
(defn- ^{:stratum 0} validate-no-rule-id-collisions
  "Verify that overlay rules don't collide with inherited rule IDs."
  [inherited-ids overlay-rules]
  (let [overlay-ids  (set (map :rule/id overlay-rules))
        collisions   (clojure.set/intersection inherited-ids overlay-ids)]
    (when (seq collisions)
      (mapv #(str "Rule ID collision in overlay: " %) collisions))))

(defn- ^{:stratum 0} apply-overrides
  "Apply :pack/overrides to a rule set.
   Only :rule/severity and :rule/enabled? are overridable per N4 spec."
  [rules overrides]
  (if (empty? overrides)
    rules
    (let [override-map (zipmap (map :rule/id overrides) overrides)]
      (mapv (fn [rule]
              (if-let [ov (get override-map (:rule/id rule))]
                (cond-> rule
                  (contains? ov :rule/severity) (assoc :rule/severity (:rule/severity ov))
                  (contains? ov :rule/enabled?) (assoc :rule/enabled? (:rule/enabled? ov)))
                rule))
            rules))))

(defn- ^{:stratum 0} validate-taxonomy-refs
  "Validate that overlay and base pack taxonomy refs don't conflict."
  [base-packs overlay-pack]
  (let [refs (->> (conj base-packs overlay-pack)
                  (keep :pack/taxonomy-ref)
                  (map :taxonomy/id)
                  distinct)]
    (when (> (count refs) 1)
      [(str "Conflicting taxonomy refs: " (pr-str refs))])))

(defn- ^{:stratum 0} resolve-base-packs
  "Look up each base pack from the store in declaration order."
  [extends pack-store]
  (mapv (fn [{:keys [pack-id]}]
          (get pack-store pack-id))
        extends))

(defn- ^{:stratum 0} find-missing-base-packs
  "Return error strings for any base pack refs that resolved to nil."
  [extends base-packs]
  (keep-indexed (fn [i bp]
                  (when (nil? bp)
                    (str "Base pack not found: "
                         (:pack-id (nth extends i)))))
                base-packs))

;; Dependency validation
(defn ^{:stratum 0} validate-pack-dependencies
  "Validate pack dependencies before loading.

   Per N4 §2.4.2, validates:
   1. No circular dependencies
   2. All dependencies available
   3. Version constraints satisfied
   4. Trust level constraints (when PR14 merged)
   5. Dependency depth within limits

   Arguments:
   - packs - Vector of pack manifests to validate
   - opts  - Options map:
             :max-dependency-depth - Maximum depth (default: 5)
             :check-trust?        - Enable trust validation (default: false)

   Returns:
   - {:valid? boolean
      :violations [{:type keyword :message string ...}]
      :warnings [{:type keyword :message string ...}]}

   Example:
     (validate-pack-dependencies [pack-a pack-b pack-c])
     (validate-pack-dependencies [pack-a] {:max-dependency-depth 3})"
  ([packs]
   (validate-pack-dependencies packs {}))
  ([packs opts]
   (let [max-depth (get opts :max-dependency-depth 5)
         check-trust? (get opts :check-trust? false)]
     (dep-validation/validate-pack-dependencies
      packs
      {:max-depth max-depth
       :check-trust? check-trust?}))))

;; Trust validation (N1 §2.10.2)
(defn ^{:stratum 0} pack->trust-ref
  "Convert a pack manifest to a trust reference for validation."
  [pack]
  (knowledge/make-pack-ref
   (:pack/id pack)
   (:pack/trust-level pack :untrusted)
   (:pack/authority pack :authority/data)
   :dependencies (mapv :pack-id (:pack/extends pack []))))

;------------------------------------------------------------------------------ Layer 1

;; Directory structure loader
(defn ^{:stratum 1} normalize-pack
  "Normalize a pack, ensuring required fields and types."
  [pack]
  (-> pack
      (update :pack/rules #(mapv normalize-rule (or % [])))
      (update :pack/categories #(or % []))
      (map-instant-keys ensure-instant)
      ;; Default trust metadata
      (update :pack/trust-level #(or % :untrusted))
      (update :pack/authority #(or % :authority/data))
      (update :pack/dependencies #(or % []))))

(defn ^{:stratum 1} write-pack-to-file
  "Write a pack manifest to a single EDN file.

   Timestamps are normalized to ISO-8601 strings first: written raw, the
   file cannot be read back at all. An unsupported timestamp is refused —
   `->iso` throws, nothing is written, and the caller gets
   `{:success? false}` instead of a file that fails only on load.

   Arguments:
   - pack - PackManifest
   - file-path - Output file path

   Returns:
   - {:success? bool :error string}"
  [pack file-path]
  (try
    (let [content (with-out-str
                    (pprint/pprint (map-instant-keys pack ->iso)))]
      (spit file-path content)
      {:success? true :error nil})
    (catch Exception e
      (schema-validation/failure nil (.getMessage e)))))

(defn ^{:stratum 1} load-rule-file
  "Load a single rule from an EDN file."
  [file]
  (let [{:keys [success? data error]} (safe-read-edn file)]
    (if success?
      (schema-validation/success :rule (normalize-rule data) {:error nil})
      (schema-validation/failure :rule error))))

(defn- ^{:stratum 1} compose-resolved-pack
  "Merge inherited + overlay rules, apply overrides, inherit taxonomy ref."
  [overlay-pack base-packs inherited-rules overlay-rules]
  (let [combined-rules (into inherited-rules overlay-rules)
        overrides      (:pack/overrides overlay-pack [])
        final-rules    (apply-overrides combined-rules overrides)
        base-tax-ref   (some :pack/taxonomy-ref (filterv some? base-packs))
        final-tax-ref  (or (:pack/taxonomy-ref overlay-pack) base-tax-ref)
        resolved-pack  (cond-> (assoc overlay-pack :pack/rules final-rules)
                         final-tax-ref (assoc :pack/taxonomy-ref final-tax-ref))]
    (schema-validation/success :pack resolved-pack {})))

(defn ^{:stratum 1} discover-packs
  "Discover all packs in a directory.

   Looks for:
   - *.pack.edn files
   - Subdirectories containing pack.edn

   Arguments:
   - packs-dir - Directory containing packs

   Returns:
   - Vector of {:path string :type :file|:directory}"
  [packs-dir]
  (let [dir (io/file packs-dir)]
    (when (.exists dir)
      (let [;; Find *.pack.edn files
            pack-files (->> (.listFiles dir)
                            (filter #(.isFile %))
                            (filter pack-file?)
                            (map (fn [f]
                                   {:path (.getPath f)
                                    :type :file})))
            ;; Find subdirectories with pack.edn
            pack-dirs (->> (.listFiles dir)
                           (filter #(.isDirectory %))
                           (filter #(.exists (io/file % "pack.edn")))
                           (map (fn [d]
                                  {:path (.getPath d)
                                   :type :directory})))]
        (vec (concat pack-files pack-dirs))))))

(defn ^{:stratum 1} validate-pack-trust
  "Validate transitive trust rules for a pack.

   Arguments:
   - pack        - PackManifest to validate
   - pack-store  - Map of pack-id -> PackManifest for dependency resolution

   Returns:
   - {:valid? true} if all trust rules pass
   - {:valid? false :errors [...]} if any rule fails

   Validates:
   1. Instruction authority is not transitive
   2. Trust level inheritance
   3. Cross-trust references (no cycles, missing deps)
   4. Tainted isolation

   Example:
     (validate-pack-trust pack {\"dep-pack\" dep-pack-manifest})"
  [pack pack-store]
  (let [pack-id (:pack/id pack)
        pack-ref (pack->trust-ref pack)

        ;; Build trust graph: pack + all dependencies
        dep-refs (reduce
                  (fn [acc dep]
                    (let [dep-id (:pack-id dep)
                          dep-pack (get pack-store dep-id)]
                      (if dep-pack
                        (assoc acc dep-id (pack->trust-ref dep-pack))
                        acc)))
                  {}
                  (:pack/extends pack []))

        trust-graph (assoc dep-refs pack-id pack-ref)]

    ;; Validate all transitive trust rules
    (try
      (let [result (knowledge/validate-transitive-trust trust-graph)]
        (if (:valid? result)
          {:valid? true}
          {:valid? false
           :errors (:errors result)}))
      (catch Exception e
        {:valid? false
         :errors [(str "Trust validation error: " (.getMessage e))]}))))

;------------------------------------------------------------------------------ Layer 2

;; Pack writing
;; Single file loader
(defn ^{:stratum 2} load-pack-from-file
  "Load a policy pack from a single EDN file.

   The file should contain a complete pack manifest with all rules inline.

   Arguments:
   - file-path - Path to the .pack.edn or pack.edn file

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack-from-file \"terraform-safety.pack.edn\")"
  [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (let [{:keys [success? data error]} (safe-read-edn file)]
        (if success?
          (let [pack (normalize-pack data)
                {:keys [valid? errors]} (schema/validate-pack pack)]
            (if valid?
              (schema-validation/success :pack pack {:errors nil})
              (schema-validation/failure-with-errors :pack errors)))
          (schema-validation/failure-with-errors :pack [{:file file-path :error error}])))
      (schema-validation/failure-with-errors :pack [{:file file-path :error "File not found"}]))))

(defn ^{:stratum 2} load-pack-from-directory
  "Load a policy pack from a directory structure.

   Expected structure:
   ```
   my-pack/
   ├── pack.edn           # Pack manifest (rules can be inline or in rules/)
   ├── rules/             # Optional separate rule files
   │   ├── 310-import-safety/
   │   │   ├── 310-import-block-preservation.edn
   │   │   └── 311-import-no-creates.edn
   │   └── 320-network-safety/
   │       └── 320-network-recreation-block.edn
   └── examples/          # Optional test examples
       └── ...
   ```

   Arguments:
   - dir-path - Path to the pack directory

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack-from-directory \"./packs/terraform-safety\")"
  [dir-path]
  (let [dir (io/file dir-path)
        manifest-file (io/file dir "pack.edn")
        rules-dir (io/file dir "rules")]

    (cond
      (not (.exists dir))
      (schema-validation/failure-with-errors :pack [{:dir dir-path :error "Directory not found"}])

      (not (.exists manifest-file))
      (schema-validation/failure-with-errors :pack [{:file "pack.edn" :error "Manifest not found in directory"}])

      :else
      (let [{:keys [success? data error]} (safe-read-edn manifest-file)]
        (if-not success?
          (schema-validation/failure-with-errors :pack [{:file "pack.edn" :error error}])

          ;; Load rules from rules/ directory if it exists
          (let [rule-files (when (.exists rules-dir)
                             (find-rule-files rules-dir))
                rule-results (mapv load-rule-file rule-files)
                successful-rules (keep :rule (filter schema-validation/succeeded? rule-results))
                rule-errors (keep (fn [r]
                                    (when-not (schema-validation/succeeded? r)
                                      {:error (:error r)}))
                                  rule-results)

                ;; Merge rules: inline rules + directory rules
                ;; Directory rules override inline rules with same ID
                inline-rules (:pack/rules data [])
                inline-by-id (zipmap (map :rule/id inline-rules) inline-rules)
                dir-by-id (zipmap (map :rule/id successful-rules) successful-rules)
                merged-rules (vals (merge inline-by-id dir-by-id))

                pack (-> data
                         (assoc :pack/rules (vec merged-rules))
                         normalize-pack)

                {:keys [valid? errors]} (schema/validate-pack pack)]

            (if valid?
              (schema-validation/success :pack pack {:errors (when (seq rule-errors) rule-errors)})
              (schema-validation/failure-with-errors :pack (concat errors rule-errors)))))))))

(defn ^{:stratum 2} resolve-overlay
  "Resolve an overlay pack by merging inherited rules from base packs.

   Resolution order (per N4 §2.5):
   1. Inherited rules merged from all :pack/extends entries in declaration order
   2. Overlay :pack/rules appended (IDs MUST NOT collide with inherited)
   3. :pack/overrides apply last (only :rule/severity and :rule/enabled?)
   4. Taxonomy ref inherited from base pack(s); conflicting refs invalid

   Arguments:
   - overlay-pack - The overlay pack with :pack/extends
   - pack-store   - Map of pack-id -> PackManifest for base pack resolution

   Returns:
   - {:success? true :pack <resolved PackManifest>}
   - {:success? false :errors [...]}"
  [overlay-pack pack-store]
  (let [extends    (:pack/extends overlay-pack [])
        base-packs (resolve-base-packs extends pack-store)
        missing    (find-missing-base-packs extends base-packs)]

    (if (seq missing)
      (schema-validation/failure-with-errors :pack (vec missing))

      (let [tax-errors (validate-taxonomy-refs (filterv some? base-packs) overlay-pack)]

        (if (seq tax-errors)
          (schema-validation/failure-with-errors :pack tax-errors)

          (let [inherited-rules  (vec (mapcat :pack/rules (filterv some? base-packs)))
                inherited-ids    (set (map :rule/id inherited-rules))
                overlay-rules    (:pack/rules overlay-pack [])
                collision-errors (validate-no-rule-id-collisions inherited-ids overlay-rules)]

            (if (seq collision-errors)
              (schema-validation/failure-with-errors :pack collision-errors)
              (compose-resolved-pack overlay-pack base-packs
                                     inherited-rules overlay-rules))))))))

;------------------------------------------------------------------------------ Layer 3

;; Auto-detect and load
(defn ^{:stratum 3} load-pack
  "Load a policy pack, auto-detecting format.

   Supports:
   - Single EDN file (pack.edn or *.pack.edn)
   - Directory with pack.edn manifest

   Arguments:
   - path - File or directory path

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack \"terraform-safety.pack.edn\")
     (load-pack \"./packs/terraform-safety/\")"
  [path]
  (let [file (io/file path)]
    (cond
      (not (.exists file))
      (schema-validation/failure-with-errors :pack [{:path path :error "Path not found"}])

      (.isFile file)
      (load-pack-from-file path)

      (.isDirectory file)
      (load-pack-from-directory path)

      :else
      (schema-validation/failure-with-errors :pack [{:path path :error "Unknown path type"}]))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} load-all-packs
  "Load all packs from a packs directory.

   Arguments:
   - packs-dir - Directory containing packs
   - opts      - Optional configuration map:
                 :validate-dependencies? - Run dependency validation (default: true)
                 :max-dependency-depth   - Maximum dependency depth (default: 5)

   Returns:
   - {:loaded [PackManifest...]
      :failed [{:path :errors}...]
      :dependency-validation {:valid? bool :violations [...] :warnings [...]}}

   Example:
     (load-all-packs \".miniforge/packs\")
     (load-all-packs \".miniforge/packs\" {:max-dependency-depth 3})"
  ([packs-dir]
   (load-all-packs packs-dir {}))
  ([packs-dir opts]
   (let [validate-deps? (get opts :validate-dependencies? true)
         max-depth (get opts :max-dependency-depth 5)
         discovered (discover-packs packs-dir)
         results (map (fn [{:keys [path]}]
                        (assoc (load-pack path) :path path))
                      discovered)
         loaded-packs (vec (keep :pack (filter schema-validation/succeeded? results)))
         failed (vec (map #(select-keys % [:path :errors])
                         (remove schema-validation/succeeded? results)))

         ;; Run dependency validation if requested
         dep-validation-result (when (and validate-deps? (seq loaded-packs))
                                (dep-validation/validate-pack-dependencies
                                 loaded-packs
                                 {:max-depth max-depth
                                  :check-trust? false}))]  ;; Enable when PR14 merged

     (cond-> {:loaded loaded-packs
              :failed failed}
       dep-validation-result
       (assoc :dependency-validation dep-validation-result)))))

(defn ^{:stratum 4} load-pack-with-trust-validation
  "Load a pack and validate trust rules.

   This is the recommended entry point for loading packs with trust enforcement.

   Arguments:
   - path        - File or directory path
   - pack-store  - Map of pack-id -> PackManifest for dependency resolution
   - options     - Optional map:
     - :skip-trust-validation? - Skip trust validation (default: false)
     - :trust-level            - Override trust level
     - :authority              - Override authority channel

   Returns:
   - {:success? bool :pack PackManifest :errors [...] :trust-result {...}}

   Example:
     (load-pack-with-trust-validation \"./packs/my-pack\" loaded-packs-map)"
  [path pack-store & [options]]
  (let [load-result (load-pack path)
        skip-trust? (:skip-trust-validation? options false)]

    (if-not (schema-validation/succeeded? load-result)
      load-result  ; Return load errors immediately

      ;; Apply overrides if provided
      (let [pack (cond-> (:pack load-result)
                   (:trust-level options)
                   (assoc :pack/trust-level (:trust-level options))

                   (:authority options)
                   (assoc :pack/authority (:authority options)))

            ;; Validate trust rules
            trust-result (if skip-trust?
                          {:valid? true :skipped? true}
                          (validate-pack-trust pack pack-store))]

        (if (:valid? trust-result)
          {:success? true
           :pack pack
           :errors (:errors load-result)  ; Non-fatal load warnings
           :trust-result trust-result}
          {:success? false
           :pack nil
           :errors (concat (:errors load-result [])
                          (:errors trust-result))
           :trust-result trust-result})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load pack from single file
  (load-pack-from-file "terraform-safety.pack.edn")

  ;; Load pack from directory
  (load-pack-from-directory "./packs/terraform-safety")

  ;; Auto-detect and load
  (load-pack "./packs/terraform-safety.pack.edn")
  (load-pack "./packs/terraform-safety/")

  ;; Discover all packs
  (discover-packs ".miniforge/packs")
  ;; => [{:path ".miniforge/packs/terraform-safety.pack.edn" :type :file}
  ;;     {:path ".miniforge/packs/kubernetes/" :type :directory}]

  ;; Load all packs
  (load-all-packs ".miniforge/packs")
  ;; => {:loaded [...] :failed [...]}

  ;; Test normalization
  (normalize-rule {:rule/id :test
                   :rule/title "Test"
                   :rule/description "Desc"
                   :rule/severity :high
                   :rule/category "300"
                   :rule/applies-to {:task-types [:import]}  ; vector, not set
                   :rule/detection {:type :content-scan}
                   :rule/enforcement {:action :warn :message "Warning"}})

  ;; Trust validation examples
  (def base-pack
    {:pack/id "base-pack"
     :pack/name "Base Pack"
     :pack/version "1.0.0"
     :pack/description "Base rules"
     :pack/author "system"
     :pack/trust-level :trusted
     :pack/authority :authority/instruction
     :pack/categories []
     :pack/rules []
     :pack/created-at (java.time.Instant/now)
     :pack/updated-at (java.time.Instant/now)})

  (def untrusted-pack
    {:pack/id "untrusted-pack"
     :pack/name "Untrusted Pack"
     :pack/version "1.0.0"
     :pack/description "External rules"
     :pack/author "external"
     :pack/trust-level :untrusted
     :pack/authority :authority/data  ; Must be data-only
     :pack/extends [{:pack-id "base-pack"}]
     :pack/categories []
     :pack/rules []
     :pack/created-at (java.time.Instant/now)
     :pack/updated-at (java.time.Instant/now)})

  ;; Validate trust rules (should pass)
  (validate-pack-trust untrusted-pack {"base-pack" base-pack})
  ;; => {:valid? true}

  ;; Invalid: untrusted pack with instruction authority
  (def invalid-pack
    (assoc untrusted-pack :pack/authority :authority/instruction))

  (validate-pack-trust invalid-pack {"base-pack" base-pack})
  ;; => {:valid? false :errors [...]}

  ;; Load with trust validation
  (load-pack-with-trust-validation
   "./packs/my-pack"
   {"base-pack" base-pack}
   {:trust-level :trusted
    :authority :authority/instruction})

  :leave-this-here)
