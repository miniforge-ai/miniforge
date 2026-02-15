(ns ai.miniforge.config.governance
  "Governance config loading with aero profiles, regex compilation,
   digest verification, and merge chain.

   Loads governance EDN files from resources/config/governance/ using
   aero for profile-based overrides. Supports a merge chain:

     1. Resource defaults (*.edn with aero tags)
     2. Profile resolution via MINIFORGE_GOVERNANCE_PROFILE env var
     3. User overrides (~/.miniforge/config.edn [:governance <key>])
     4. Trusted pack overrides (safety-checked)
     5. Regex compilation (risk + knowledge-safety)

   Layer 0: Helpers and constants
   Layer 1: Regex compilation
   Layer 2: Config loading and merge chain"
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [ai.miniforge.config.digest :as digest]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and helpers

(def ^:private config-key->filename
  "Map from config key to EDN filename."
  {:readiness        "readiness.edn"
   :risk             "risk.edn"
   :tiers            "tiers.edn"
   :knowledge-safety "knowledge-safety.edn"})

(def ^:private user-config-path
  "Default path to user config file."
  (str (fs/home) "/.miniforge/config.edn"))

(defn- resolve-profile
  "Resolve governance profile from env var or opts.
   Defaults to :default."
  [opts]
  (or (:profile opts)
      (some-> (System/getenv "MINIFORGE_GOVERNANCE_PROFILE") keyword)
      :default))

(defn- deep-merge
  "Recursively merge maps. Later values win for non-map keys."
  [& maps]
  (reduce (fn [acc m]
            (reduce-kv (fn [a k v]
                         (if (and (map? (get a k)) (map? v))
                           (assoc a k (deep-merge (get a k) v))
                           (assoc a k v)))
                       acc m))
          {} maps))

;------------------------------------------------------------------------------ Layer 1
;; Regex compilation

(defn compile-risk-patterns
  "Compile regex pattern strings in risk config under :critical-files :patterns.
   Returns config with compiled patterns."
  [config]
  (if-let [patterns (get-in config [:critical-files :patterns])]
    (assoc-in config [:critical-files :patterns]
              (mapv re-pattern patterns))
    config))

(defn compile-injection-patterns
  "Compile all regex pattern string vectors under :injection-patterns.
   Returns config with compiled patterns."
  [config]
  (if-let [categories (:injection-patterns config)]
    (assoc config :injection-patterns
           (reduce-kv (fn [acc k patterns]
                        (assoc acc k (mapv re-pattern patterns)))
                      {} categories))
    config))

;------------------------------------------------------------------------------ Layer 2
;; Config loading and merge chain

(defn- load-resource-config
  "Load a governance EDN file from classpath using aero for profile resolution."
  [config-key profile]
  (when-let [filename (get config-key->filename config-key)]
    (when-let [resource (io/resource (str "config/governance/" filename))]
      (aero/read-config resource {:profile profile}))))

(defn- load-user-overrides
  "Load governance overrides from user config file.
   Returns the map at [:governance <config-key>] or nil."
  [config-key]
  (try
    (let [path user-config-path]
      (when (fs/exists? path)
        (let [user-cfg (edn/read-string (slurp path))]
          (get-in user-cfg [:governance config-key]))))
    (catch Exception _e
      nil)))

(defn- verify-and-warn
  "Verify governance file content against digest manifest.
   For :knowledge-safety, mismatch throws. For others, logs a warning."
  [config-key content]
  (let [result (digest/verify-governance-file config-key content)]
    (case result
      :ok        nil
      :no-manifest nil
      :no-entry    nil
      :mismatch  (if (= config-key :knowledge-safety)
                   (throw (ex-info (str "Governance config integrity check failed for "
                                        (name config-key)
                                        ". Config file may have been tampered with.")
                                   {:config-key config-key
                                    :verification :failed}))
                   (binding [*out* *err*]
                     (println (str "WARNING: Governance config digest mismatch for "
                                   (name config-key)
                                   ". File may have been modified.")))))))

(defn- needs-regex-compilation?
  "Return true if this config key has regex strings that need compilation."
  [config-key]
  (contains? #{:risk :knowledge-safety} config-key))

(defn- compile-patterns
  "Compile regex patterns for configs that need it."
  [config-key config]
  (case config-key
    :risk             (compile-risk-patterns config)
    :knowledge-safety (compile-injection-patterns config)
    config))

(defn apply-pack-overrides
  "Apply pack config overrides with safety checks.

   Safety rules:
   - Only :trusted packs can carry config overrides
   - For :knowledge-safety, only additive overrides are allowed (patterns
     cannot be removed, only added)

   Arguments:
   - config-key  - Keyword like :readiness, :risk, etc.
   - base-config - The current config map
   - pack        - Pack manifest with :pack/config-overrides and :pack/trust-level

   Returns: merged config map

   Throws: If pack is not :trusted, or if knowledge-safety patterns would shrink."
  [config-key base-config pack]
  (let [trust-level (:pack/trust-level pack)
        overrides   (get-in pack [:pack/config-overrides config-key])]
    (if-not overrides
      base-config
      (do
        (when (not= :trusted trust-level)
          (throw (ex-info "Only :trusted packs can carry config overrides"
                          {:pack-id (:pack/id pack)
                           :trust-level trust-level})))
        (if (= config-key :knowledge-safety)
          ;; Safety check: count patterns before/after, reject if any category shrinks
          (let [before-categories (:injection-patterns base-config)
                merged (deep-merge base-config overrides)
                after-categories (:injection-patterns merged)]
            (doseq [[cat-key before-patterns] before-categories]
              (let [after-patterns (get after-categories cat-key)]
                (when (and after-patterns
                           (< (count after-patterns) (count before-patterns)))
                  (throw (ex-info (str "Knowledge-safety pattern category " (name cat-key)
                                       " would shrink from " (count before-patterns)
                                       " to " (count after-patterns) " patterns. "
                                       "Only additive overrides are allowed.")
                                  {:category cat-key
                                   :before-count (count before-patterns)
                                   :after-count (count after-patterns)})))))
            merged)
          ;; Non-safety configs: merge freely
          (deep-merge base-config overrides))))))

(defn load-governance-config
  "Load governance config with full merge chain.

   Merge chain (precedence lowest -> highest):
   1. Resource defaults (resources/config/governance/*.edn)
   2. Aero profile resolution
   3. User overrides (~/.miniforge/config.edn [:governance <key>])
   4. Pack overrides (optional, safety-checked)
   5. Regex compilation (risk + knowledge-safety)

   Arguments:
   - config-key - Keyword: :readiness, :risk, :tiers, :knowledge-safety
   - opts       - Optional map:
                   :profile - Override aero profile (default from env var)
                   :pack    - Pack manifest with :pack/config-overrides
                   :skip-digest? - Skip digest verification (default false)

   Returns: Fully resolved config map."
  ([config-key] (load-governance-config config-key {}))
  ([config-key opts]
   (let [profile (resolve-profile opts)
         ;; Step 1+2: Load resource config with aero profile
         resource-config (load-resource-config config-key profile)]
     (when-not resource-config
       (throw (ex-info (str "Governance config not found: " (name config-key))
                       {:config-key config-key})))
     ;; Digest verification (before any merging)
     (when-not (:skip-digest? opts)
       (let [filename (get config-key->filename config-key)
             resource (io/resource (str "config/governance/" filename))]
         (when resource
           (verify-and-warn config-key (slurp resource)))))
     (let [;; Step 3: User overrides
           user-overrides (load-user-overrides config-key)
           merged (if user-overrides
                    (deep-merge resource-config user-overrides)
                    resource-config)
           ;; Step 4: Pack overrides
           merged (if-let [pack (:pack opts)]
                    (apply-pack-overrides config-key merged pack)
                    merged)
           ;; Step 5: Regex compilation
           final (if (needs-regex-compilation? config-key)
                   (compile-patterns config-key merged)
                   merged)]
       final))))
