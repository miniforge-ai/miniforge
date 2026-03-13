(ns ai.miniforge.cli.resource-config
  "Helpers for resource-backed CLI configuration and messages."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(defn resource-precedence
  "Sort classpath resources so base defaults load before component/project overrides."
  [resource]
  (let [path (str resource)]
    (cond
      (str/includes? path "/bases/") 0
      (str/includes? path "/components/") 1
      (str/includes? path "/projects/") 2
      :else 3)))

(defn read-edn-resource
  [resource]
  (-> resource slurp edn/read-string))

(defn merged-resource-config
  "Load and merge EDN resources from the classpath.

   `section-key` extracts the relevant sub-map from each resource when provided."
  ([resource-path]
   (merged-resource-config resource-path nil {}))
  ([resource-path section-key]
   (merged-resource-config resource-path section-key {}))
  ([resource-path section-key defaults]
   (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) resource-path))
        (sort-by resource-precedence)
        (map read-edn-resource)
        (map #(if section-key
                (or (get % section-key) {})
                %))
        (reduce merge defaults))))
