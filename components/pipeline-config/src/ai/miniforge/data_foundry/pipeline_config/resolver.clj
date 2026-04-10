(ns ai.miniforge.data-foundry.pipeline-config.resolver
  "Symbolic ref resolution for pipeline EDN configs.
   Pure function: takes EDN config + resolution context, returns resolved map."
  (:require [ai.miniforge.data-foundry.pipeline-config.messages :as msg])
  (:import [java.util UUID]
           [java.time Instant]))

(defn- resolve-rule
  "Resolve a rule descriptor map using the rule registry."
  [rule-registry {:rule/keys [type id] :as descriptor}]
  (case type
    :custom (get rule-registry id)
    ;; Built-in types: look up resolver fn by type keyword
    (if-let [resolver (get rule-registry type)]
      (resolver descriptor)
      nil)))

(defn- resolve-quality-pack
  "Hydrate a quality pack's rule descriptors into live rule functions."
  [rule-registry pack]
  (update pack :pack/rules
          (fn [rules] (mapv #(resolve-rule rule-registry %) rules))))

(defn- resolve-stage-config
  "Resolve quality pack rules within stage config, if present."
  [rule-registry config]
  (if-let [pack (:stage/quality-pack config)]
    (assoc config :stage/quality-pack (resolve-quality-pack rule-registry pack))
    config))

(defn resolve-pipeline
  "Resolve an EDN pipeline config into a runnable pipeline definition.
   Assigns UUIDs to stages, resolves symbolic connector/dataset refs,
   resolves stage dependencies by name→id, and hydrates quality rules.

   resolution-context keys:
     :connector-refs  — {keyword → UUID} map
     :dataset-refs    — {keyword → UUID} map
     :rule-registry   — {keyword → rule-fn-or-resolver} map
     :stage-configs   — {stage-name → config-map} map"
  [edn-config {:keys [connector-refs dataset-refs rule-registry
                       stage-configs]}]
  (let [;; Assign UUIDs to each stage
        stages-with-ids (mapv #(assoc % :stage/id (UUID/randomUUID))
                              (:pipeline/stages edn-config))

        ;; Build name→id index for dependency resolution
        name->id (into {} (map (juxt :stage/name :stage/id)) stages-with-ids)

        ;; Resolve all symbolic refs within each stage
        resolved-stages
        (mapv (fn [stage]
                (cond-> stage
                  ;; Resolve connector-ref keyword → UUID
                  (:stage/connector-ref stage)
                  (update :stage/connector-ref #(get connector-refs % %))

                  ;; Resolve dataset keywords → UUIDs
                  true
                  (update :stage/input-datasets
                          #(mapv (fn [ds] (get dataset-refs ds ds)) %))

                  true
                  (update :stage/output-datasets
                          #(mapv (fn [ds] (get dataset-refs ds ds)) %))

                  ;; Resolve dependencies from stage names → stage IDs
                  true
                  (update :stage/dependencies
                          #(mapv (fn [dep-name] (get name->id dep-name)) %))

                  ;; Merge env-specific stage config (file paths, etc.)
                  (get stage-configs (:stage/name stage))
                  (update :stage/config merge (get stage-configs (:stage/name stage)))

                  ;; Resolve quality rules in config
                  (:stage/config stage)
                  (update :stage/config #(resolve-stage-config rule-registry %))))
              stages-with-ids)

        now (Instant/now)]
    (assoc edn-config
           :pipeline/id         (UUID/randomUUID)
           :pipeline/stages     resolved-stages
           :pipeline/created-at now
           :pipeline/updated-at now)))
