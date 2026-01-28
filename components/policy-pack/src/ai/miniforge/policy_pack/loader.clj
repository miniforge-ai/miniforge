;; Copyright 2025 miniforge.ai
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
   [ai.miniforge.knowledge.interface :as knowledge]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File discovery and path utilities

(defn- find-rule-files
  "Find all rule .edn files in a rules/ directory, recursive."
  [rules-dir]
  (when (.exists (io/file rules-dir))
    (->> (file-seq (io/file rules-dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".edn")))))

(defn- pack-file?
  "Check if a file is a pack file (pack.edn or *.pack.edn)."
  [file]
  (let [name (.getName file)]
    (or (= name "pack.edn")
        (str/ends-with? name ".pack.edn"))))

;------------------------------------------------------------------------------ Layer 1
;; EDN parsing and validation

(defn- safe-read-edn
  "Safely read EDN from a file.
   Returns {:success? bool :data any :error string}."
  [file]
  (try
    (let [content (slurp file)
          data (edn/read-string content)]
      {:success? true :data data :error nil})
    (catch Exception e
      {:success? false :data nil :error (.getMessage e)})))

(defn- ensure-instant
  "Convert various timestamp representations to Instant."
  [value]
  (cond
    (inst? value) value
    (string? value) (try
                      (java.time.Instant/parse value)
                      (catch Exception _
                        (java.time.Instant/now)))
    :else (java.time.Instant/now)))

(defn- normalize-rule
  "Normalize a rule, ensuring required fields and types."
  [rule]
  (-> rule
      (update :rule/applies-to #(or % {}))
      (update-in [:rule/applies-to :task-types]
                 #(when % (if (set? %) % (set %))))
      (update-in [:rule/applies-to :repo-types]
                 #(when % (if (set? %) % (set %))))
      (update-in [:rule/applies-to :phases]
                 #(when % (if (set? %) % (set %))))))

(defn- normalize-pack
  "Normalize a pack, ensuring required fields and types."
  [pack]
  (-> pack
      (update :pack/rules #(mapv normalize-rule (or % [])))
      (update :pack/categories #(or % []))
      (update :pack/created-at ensure-instant)
      (update :pack/updated-at ensure-instant)
      ;; Default trust metadata
      (update :pack/trust-level #(or % :untrusted))
      (update :pack/authority #(or % :authority/data))
      (update :pack/dependencies #(or % []))))

;------------------------------------------------------------------------------ Layer 2
;; Single file loader

(defn load-pack-from-file
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
              {:success? true :pack pack :errors nil}
              {:success? false :pack nil :errors errors}))
          {:success? false :pack nil :errors [{:file file-path :error error}]}))
      {:success? false :pack nil :errors [{:file file-path :error "File not found"}]})))

;------------------------------------------------------------------------------ Layer 2
;; Directory structure loader

(defn- load-rule-file
  "Load a single rule from an EDN file."
  [file]
  (let [{:keys [success? data error]} (safe-read-edn file)]
    (if success?
      {:success? true :rule (normalize-rule data) :error nil}
      {:success? false :rule nil :error error})))

(defn load-pack-from-directory
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
      {:success? false :pack nil :errors [{:dir dir-path :error "Directory not found"}]}

      (not (.exists manifest-file))
      {:success? false :pack nil :errors [{:file "pack.edn" :error "Manifest not found in directory"}]}

      :else
      (let [{:keys [success? data error]} (safe-read-edn manifest-file)]
        (if-not success?
          {:success? false :pack nil :errors [{:file "pack.edn" :error error}]}

          ;; Load rules from rules/ directory if it exists
          (let [rule-files (when (.exists rules-dir)
                             (find-rule-files rules-dir))
                rule-results (mapv load-rule-file rule-files)
                successful-rules (keep :rule (filter :success? rule-results))
                rule-errors (keep (fn [r]
                                    (when-not (:success? r)
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
              {:success? true
               :pack pack
               :errors (when (seq rule-errors) rule-errors)}
              {:success? false
               :pack nil
               :errors (concat errors rule-errors)})))))))

;------------------------------------------------------------------------------ Layer 3
;; Auto-detect and load

(defn load-pack
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
      {:success? false :pack nil :errors [{:path path :error "Path not found"}]}

      (.isFile file)
      (load-pack-from-file path)

      (.isDirectory file)
      (load-pack-from-directory path)

      :else
      {:success? false :pack nil :errors [{:path path :error "Unknown path type"}]})))

(defn discover-packs
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

(defn load-all-packs
  "Load all packs from a packs directory.

   Arguments:
   - packs-dir - Directory containing packs

   Returns:
   - {:loaded [PackManifest...] :failed [{:path :errors}...]}

   Example:
     (load-all-packs \".miniforge/packs\")"
  [packs-dir]
  (let [discovered (discover-packs packs-dir)
        results (map (fn [{:keys [path]}]
                       (assoc (load-pack path) :path path))
                     discovered)]
    {:loaded (vec (keep :pack (filter :success? results)))
     :failed (vec (map #(select-keys % [:path :errors])
                       (remove :success? results)))}))

;------------------------------------------------------------------------------ Layer 3
;; Pack writing

(defn write-pack-to-file
  "Write a pack manifest to a single EDN file.

   Arguments:
   - pack - PackManifest
   - file-path - Output file path

   Returns:
   - {:success? bool :error string}"
  [pack file-path]
  (try
    (let [content (with-out-str
                    (clojure.pprint/pprint pack))]
      (spit file-path content)
      {:success? true :error nil})
    (catch Exception e
      {:success? false :error (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 4
;; Trust validation (N1 §2.10.2)

(defn- pack->trust-ref
  "Convert a pack manifest to a trust reference for validation."
  [pack]
  (knowledge/make-pack-ref
   (:pack/id pack)
   (:pack/trust-level pack :untrusted)
   (:pack/authority pack :authority/data)
   :dependencies (mapv :pack-id (:pack/extends pack []))))

(defn validate-pack-trust
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

(defn load-pack-with-trust-validation
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

    (if-not (:success? load-result)
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
                   :rule/severity :major
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
