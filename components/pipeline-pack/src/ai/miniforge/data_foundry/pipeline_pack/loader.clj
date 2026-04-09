(ns ai.miniforge.data-foundry.pipeline-pack.loader
  "Load pipeline packs from disk.

   Layer 0: EDN parsing, normalization
   Layer 1: Directory loader (reads manifest, resolves relative paths)
   Layer 2: Discovery and batch loading"
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.data-foundry.pipeline-pack.schema :as pack-schema]
   [ai.miniforge.data-foundry.pipeline-pack.messages :as msg]
   [ai.miniforge.data-foundry.metric-registry.interface :as metric-registry]
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; EDN parsing and normalization

(defn safe-read-edn
  "Safely read EDN from a file. Returns {:success? bool :data any :error string}."
  [file]
  (try
    (let [content (slurp file)
          data (edn/read-string content)]
      {:success? true :data data :error nil})
    (catch Exception e
      {:success? false :data nil :error (.getMessage e)})))

(defn ensure-instant
  "Convert timestamp representations to Instant."
  [value]
  (cond
    (inst? value) value
    (string? value) (try
                      (java.time.Instant/parse value)
                      (catch Exception _
                        (java.time.Instant/now)))
    :else (java.time.Instant/now)))

(defn normalize-manifest
  "Normalize a pack manifest, applying defaults."
  [manifest]
  (-> manifest
      (update :pack/trust-level #(or % :untrusted))
      (update :pack/authority #(or % :authority/data))
      (update :pack/pipelines #(or % []))
      (update :pack/envs #(or % []))
      (update :pack/extends #(or % []))
      (update :pack/created-at ensure-instant)
      (update :pack/updated-at ensure-instant)
      (cond->
        (and (:pack/connector-types manifest)
             (not (set? (:pack/connector-types manifest))))
        (update :pack/connector-types set))))

;------------------------------------------------------------------------------ Layer 0
;; Path helpers

(defn- resolve-relative-path
  "Resolve a relative path against a base directory."
  [dir relative]
  (.getPath (io/file dir relative)))

(defn- load-metric-registry-file
  "Load a metric registry relative to a pack directory."
  [dir registry-path]
  (let [reg-file (io/file dir registry-path)]
    (if (.exists reg-file)
      (metric-registry/load-registry (.getPath reg-file))
      (schema/failure :registry (msg/t :pack/registry-not-found {:path registry-path})))))

;------------------------------------------------------------------------------ Layer 1
;; Directory loader — helpers

(defn- resolve-pipelines
  "Resolve pipeline paths relative to pack directory."
  [dir manifest]
  (mapv (partial resolve-relative-path dir) (:pack/pipelines manifest)))

(defn- resolve-envs
  "Resolve env paths relative to pack directory."
  [dir manifest]
  (mapv (partial resolve-relative-path dir) (:pack/envs manifest)))

(defn- attach-registry
  "Attach metric registry result to a pack map."
  [pack registry-result]
  (cond
    (nil? registry-result)          pack
    (:success? registry-result)     (assoc pack :pack/registry (:registry registry-result))
    :else                           (assoc pack :pack/registry-error (:error registry-result))))

(defn- build-pack-from-manifest
  "Given a validated manifest and its directory, resolve paths, load registry,
   and assemble the pack map."
  [dir manifest]
  (let [registry-path   (:pack/metric-registry manifest)
        registry-result (when registry-path
                          (load-metric-registry-file dir registry-path))
        pack (-> {:pack/manifest  manifest
                  :pack/dir       (.getPath dir)
                  :pack/pipelines (resolve-pipelines dir manifest)
                  :pack/envs      (resolve-envs dir manifest)}
                 (attach-registry registry-result))]
    (schema/success :pack pack)))

(defn- read-and-validate-manifest
  "Read pack.edn, normalize, validate, and build the pack if valid."
  [dir manifest-file]
  (let [{:keys [success? data error]} (safe-read-edn manifest-file)]
    (if-not success?
      (schema/failure :pack (msg/t :pack/manifest-read-error {:error error}))
      (let [manifest (normalize-manifest data)
            {:keys [valid? errors]} (pack-schema/validate-manifest manifest)]
        (if-not valid?
          (schema/failure :pack (msg/t :pack/manifest-invalid {:errors (pr-str errors)}))
          (build-pack-from-manifest dir manifest))))))

;; Directory loader — public

(defn load-pack-from-directory
  "Load a pipeline pack from a directory.

   Expected structure:
     my-pack/
       pack.edn              — manifest
       metric-registry.edn   — optional metric registry
       pipelines/             — pipeline definitions (paths in manifest)
       envs/                  — env configs (paths in manifest)

   Returns lazy pack: manifest + registry loaded, pipeline paths resolved but not parsed.
   Caller uses pipeline-config/load-pipeline on demand."
  [dir-path]
  (let [dir (io/file dir-path)
        manifest-file (io/file dir "pack.edn")]
    (cond
      (not (.exists dir))
      (schema/failure :pack (msg/t :pack/dir-not-found {:path dir-path}))

      (not (.exists manifest-file))
      (schema/failure :pack (msg/t :pack/manifest-not-found {:path dir-path}))

      :else
      (read-and-validate-manifest dir manifest-file))))

;------------------------------------------------------------------------------ Layer 2
;; Discovery

(defn- directory? [f] (.isDirectory f))

(defn- has-manifest? [dir] (.exists (io/file dir "pack.edn")))

(defn- dir->pack-entry
  "Convert a directory File to a discovery entry."
  [d]
  {:path (.getPath d) :pack-id (.getName d)})

(defn discover-packs
  "Discover all pipeline packs in a directory.

   Looks for subdirectories containing pack.edn.

   Returns vector of {:path string :pack-id string}."
  [packs-dir]
  (let [dir (io/file packs-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter directory?)
           (filter has-manifest?)
           (mapv dir->pack-entry)))))

(defn- load-discovered-pack
  "Load a single discovered pack entry, tagging with pack-id and path."
  [{:keys [path pack-id]}]
  (assoc (load-pack-from-directory path)
         :pack-id pack-id
         :path path))

(defn- failed-pack-summary
  "Extract summary fields from a failed pack load result."
  [result]
  (select-keys result [:pack-id :path :error]))

(defn load-all-packs
  "Load all packs from a packs directory.

   Returns {:loaded [...] :failed [...]}."
  [packs-dir]
  (let [discovered (discover-packs packs-dir)
        results (mapv load-discovered-pack discovered)
        loaded (vec (keep :pack (filter :success? results)))
        failed (vec (map failed-pack-summary (remove :success? results)))]
    {:loaded loaded :failed failed}))
