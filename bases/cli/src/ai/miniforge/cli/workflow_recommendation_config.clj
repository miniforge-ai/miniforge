(ns ai.miniforge.cli.workflow-recommendation-config
  "Resource-driven workflow recommendation prompt configuration."
  (:require
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def recommendation-config-resource
  "Classpath resource path for workflow recommendation prompt config."
  "config/workflow/recommendation-prompt.edn")

(def default-prompt-config
  {:analysis-dimensions
   ["Task complexity (simple, medium, complex)"
    "Risk level (low, medium, high)"
    "Review requirements (minimal, standard, comprehensive)"
    "Time constraints (quick, normal, extensive)"
    "Work category and expected outputs"]
   :summary-labels
   {:has-review "Includes review checkpoints"
    :has-testing "Includes verification/testing"}})

(defn- read-recommendation-config
  "Read a single recommendation prompt config resource."
  [resource]
  (let [config (-> resource slurp edn/read-string)]
    (or (:workflow-recommendation/prompt config) {})))

(defn- merge-prompt-config
  "Merge prompt config maps, preserving nested summary labels."
  [base override]
  (-> base
      (merge override)
      (update :summary-labels #(merge (:summary-labels base) %))))

(defn recommendation-prompt-config
  "Resolve the active workflow recommendation prompt config from the classpath."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       recommendation-config-resource))
       (map read-recommendation-config)
       (reduce merge-prompt-config default-prompt-config)))
