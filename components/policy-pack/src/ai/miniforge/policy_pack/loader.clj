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

   Layer 0: Overlay resolution helpers, dependency-validation wrapper, trust
            ref, and the single-file/directory loaders
   Layer 1: Overlay pack composition, trust validation (N1 §2.10.2), and
            format auto-detection
   Layer 2: Overlay resolution (N4 §2.5) and the trust-validated /
            load-all-packs top-level entry points

   Supports two formats:
   - Single EDN file: pack.edn or *.pack.edn
   - Directory structure with pack.edn manifest and rules/ subdirectory

   Trust validation ensures:
   - Instruction authority is not transitive
   - Trust level inheritance (lowest wins)
   - Cross-trust references are validated
   - Tainted content is isolated from instruction authority

   EDN parsing, timestamp/rule normalization, pack writing, and rule/pack
   discovery live in sibling `ai.miniforge.policy-pack.loader.*` namespaces.
   Extracting those (rule 210: the combined namespace measured 5 real
   layers, max 3) also shortened this namespace's own in-file call chain —
   the file-IO/normalization hops no longer count toward its local layer
   depth, so the remaining overlay/dependency/trust/orchestration code now
   measures 3 real layers on its own, within budget."
  (:require
   [ai.miniforge.policy-pack.loader.io :as loader-io]
   [ai.miniforge.policy-pack.loader.normalize :as normalize]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation :as dep-validation]
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]
   [clojure.java.io :as io]
   [clojure.set]))

;------------------------------------------------------------------------------ Layer 0

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

;; Single file loader
(defn ^{:stratum 0} load-pack-from-file
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
      (let [{:keys [success? data error]} (loader-io/safe-read-edn file)]
        (if success?
          (let [pack (normalize/normalize-pack data)
                {:keys [valid? errors]} (schema/validate-pack pack)]
            (if valid?
              (schema-validation/success :pack pack {:errors nil})
              (schema-validation/failure-with-errors :pack errors)))
          (schema-validation/failure-with-errors :pack [{:file file-path :error error}])))
      (schema-validation/failure-with-errors :pack [{:file file-path :error "File not found"}]))))

(defn ^{:stratum 0} load-pack-from-directory
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
      (let [{:keys [success? data error]} (loader-io/safe-read-edn manifest-file)]
        (if-not success?
          (schema-validation/failure-with-errors :pack [{:file "pack.edn" :error error}])

          ;; Load rules from rules/ directory if it exists
          (let [rule-files (when (.exists rules-dir)
                             (loader-io/find-rule-files rules-dir))
                rule-results (mapv loader-io/load-rule-file rule-files)
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
                         normalize/normalize-pack)

                {:keys [valid? errors]} (schema/validate-pack pack)]

            (if valid?
              (schema-validation/success :pack pack {:errors (when (seq rule-errors) rule-errors)})
              (schema-validation/failure-with-errors :pack (concat errors rule-errors)))))))))

;------------------------------------------------------------------------------ Layer 1

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

;; Auto-detect and load
(defn ^{:stratum 1} load-pack
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

;------------------------------------------------------------------------------ Layer 2

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

(defn ^{:stratum 2} load-all-packs
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
         discovered (loader-io/discover-packs packs-dir)
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

(defn ^{:stratum 2} load-pack-with-trust-validation
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

  ;; Load all packs
  (load-all-packs ".miniforge/packs")
  ;; => {:loaded [...] :failed [...]}

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
