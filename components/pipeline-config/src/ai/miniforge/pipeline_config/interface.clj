(ns ai.miniforge.pipeline-config.interface
  "Public API for the pipeline-config component.
   Bridges raw pipeline EDN definitions and runnable pipeline maps."
  (:require [ai.miniforge.pipeline-config.discovery :as discovery]
            [ai.miniforge.pipeline-config.loader :as loader]
            [ai.miniforge.pipeline-config.resolver :as resolver]
            [ai.miniforge.pipeline-config.connector-registry :as conn-reg]
            [ai.miniforge.pipeline-config.rule-registry :as rule-reg]
            [ai.miniforge.pipeline-config.env :as env]
            [ai.miniforge.schema.interface :as schema]))

;; -- Discovery --

(defn discover-pipelines
  "Scan directories for pipeline EDN files.
   Returns schema/success with :pipelines key."
  [search-paths]
  (discovery/discover-pipelines search-paths))

;; -- Loading --

(defn load-pipeline
  "Load a pipeline definition from a file path or classpath resource.
   Returns schema/success with :pipeline key or schema/failure."
  [path-or-resource]
  (loader/load-pipeline path-or-resource))

;; -- Resolution Context Factory --

(defn create-resolution-context
  "Create a resolution context for pipeline resolution.
   Args:
     connector-refs — {keyword → UUID} map of connector symbolic refs
     dataset-refs   — {keyword → UUID} map of dataset symbolic refs
     rule-registry  — flat rule lookup map (from resolve-rules)
     stage-configs  — {stage-name → config-map} of env-specific overrides
   Returns: resolution context map for resolve-pipeline."
  [{:keys [connector-refs dataset-refs rule-registry stage-configs]
    :or {connector-refs {} dataset-refs {} rule-registry {} stage-configs {}}}]
  {:connector-refs connector-refs
   :dataset-refs   dataset-refs
   :rule-registry  rule-registry
   :stage-configs  stage-configs})

;; -- Resolution --

(defn resolve-pipeline
  "Resolve an EDN pipeline config into a runnable pipeline definition.
   Assigns UUIDs, resolves connector/dataset refs, stage dependencies,
   and hydrates quality rules.
   Takes a resolution context (see create-resolution-context)."
  [edn-config resolution-context]
  (resolver/resolve-pipeline edn-config resolution-context))

(defn load-and-resolve
  "Load a pipeline from path, then resolve with the given context.
   Returns schema/success with :pipeline key or schema/failure."
  [path-or-resource resolution-context]
  (let [load-result (loader/load-pipeline path-or-resource)]
    (if (:success? load-result)
      (schema/success :pipeline
                      (resolver/resolve-pipeline (:pipeline load-result) resolution-context))
      load-result)))

;; -- Connector Registry --

(defn create-connector-registry
  "Create an empty connector registry (atom-backed)."
  []
  (conn-reg/create-connector-registry))

(defn register-connector!
  "Register a connector type with its factory function and metadata."
  [registry type-kw factory-fn metadata]
  (conn-reg/register-connector! registry type-kw factory-fn metadata))

(defn list-connectors
  "Return all registered connector types with metadata."
  [registry]
  (conn-reg/list-connectors registry))

(defn instantiate-connectors
  "Create connector instances from a {symbolic-ref → type-keyword} map.
   Returns {:connector-refs {ref → uuid} :connectors {uuid → instance}}."
  [registry connector-refs]
  (conn-reg/instantiate-connectors registry connector-refs))

;; -- Rule Registry --

(defn create-rule-registry
  "Create a rule registry with built-in rule type resolvers."
  []
  (rule-reg/create-rule-registry))

(defn register-rule!
  "Register a custom rule by id."
  [registry rule-id rule-fn]
  (rule-reg/register-rule! registry rule-id rule-fn))

(defn resolve-rules
  "Convert registry to flat lookup map for the resolver."
  [registry]
  (rule-reg/resolve-rules registry))

;; -- Environment Config --

(defn load-env-config
  "Load an environment config EDN file.
   Returns schema/success with :env-config key or schema/failure."
  [path]
  (env/load-env-config path))

(defn extract-connector-types
  "Extract {symbolic-ref → connector-type} from env config."
  [env-config]
  (env/extract-connector-types env-config))

(defn extract-stage-configs
  "Extract stage config overrides from env config."
  [env-config]
  (env/extract-stage-configs env-config))
