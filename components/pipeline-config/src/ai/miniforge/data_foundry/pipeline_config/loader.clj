(ns ai.miniforge.data-foundry.pipeline-config.loader
  "Parse pipeline EDN from file paths or classpath resources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.miniforge.data-foundry.pipeline-config.messages :as msg]
            [ai.miniforge.schema.interface :as schema]))

(defn load-pipeline
  "Load a pipeline definition from a file path or classpath resource.
   Returns schema/success with :pipeline key or schema/failure."
  [path-or-resource]
  (try
    (let [source (or (io/resource path-or-resource)
                     (let [f (io/file path-or-resource)]
                       (when (.exists f) f)))]
      (if source
        (let [content (slurp source)
              pipeline (edn/read-string content)]
          (if (and (map? pipeline) (:pipeline/name pipeline))
            (schema/success :pipeline pipeline)
            (schema/failure :pipeline (msg/t :load/parse-error {:error "Not a valid pipeline map"}))))
        (schema/failure :pipeline (msg/t :load/file-not-found {:path path-or-resource}))))
    (catch Exception e
      (schema/exception-failure :pipeline e))))
