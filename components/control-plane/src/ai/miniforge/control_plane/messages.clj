(ns ai.miniforge.control-plane.messages
  "Component-level message catalog for control-plane.

   Loads localized strings from EDN resources on the classpath.
   Falls back to message key name when a key is missing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-path "config/control-plane/messages/en-US.edn")
(def ^:private section-key :control-plane/messages)

(defn- load-catalog []
  (when-let [res (io/resource resource-path)]
    (get (edn/read-string (slurp res)) section-key {})))

(def ^:private catalog (delay (load-catalog)))

(defn t
  ([k] (t k {}))
  ([k params]
   (let [template (get @catalog k (name k))]
     (if (string? template)
       (reduce-kv (fn [s pk pv]
                    (str/replace s (str "{" (name pk) "}") (str pv)))
                  template
                  params)
       template))))
