(ns ai.miniforge.connector-github.resources
  "GitHub resource type registry and URL/param builders.
   Resource definitions are loaded from an EDN resource file at startup."
  (:require [ai.miniforge.connector-github.messages :as msg]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-path "config/connector-github/resources.edn")

(defn- load-resources []
  (if-let [res (io/resource resource-path)]
    (edn/read-string (slurp res))
    (throw (ex-info (msg/t :github/resources-not-found {:path resource-path})
                    {:path resource-path}))))

(def github-resources
  "Registry of GitHub REST API v3 resource types, loaded from EDN."
  (delay (load-resources)))

(defn get-resource
  "Look up a resource definition by key."
  [resource-key]
  (get @github-resources resource-key))

(defn build-url
  "Build a GitHub API URL by substituting {org}, {owner}, {repo}, {pull_number}
   from config into the resource endpoint template."
  [base-url resource-def config]
  (let [endpoint (if (and (:user-endpoint resource-def)
                          (not (:github/org config))
                          (:github/owner config))
                   (:user-endpoint resource-def)
                   (:endpoint resource-def))]
    (str base-url
         (-> endpoint
             (str/replace "{org}" (or (:github/org config) ""))
             (str/replace "{owner}" (or (:github/owner config) ""))
             (str/replace "{repo}" (or (:github/repo config) ""))
             (str/replace "{pull_number}" (str (or (:github/pull-number config) "")))))))

(defn build-query-params
  "Build query params for a GitHub API request, merging resource defaults,
   sort/direction, per_page, and incremental cursor value."
  [resource-def cursor opts]
  (let [base-params  (get resource-def :query-params {})
        per-page     (get opts :extract/batch-size (get resource-def :per-page 100))
        sort-param   (:sort-param resource-def)
        direction    (:direction-param resource-def)
        incr-param   (:incremental-param resource-def)
        cursor-value (get-in cursor [:cursor/value])]
    (cond-> (assoc base-params "per_page" per-page)
      sort-param   (assoc "sort" sort-param)
      direction    (assoc "direction" direction)
      (and incr-param cursor-value)
      (assoc incr-param cursor-value))))

(defn resource-schemas
  "Return discover-compatible schema list for all known GitHub resources."
  []
  (mapv (fn [[resource-key resource-def]]
          {:schema/name     (name resource-key)
           :schema/endpoint (:endpoint resource-def)
           :schema/type     (:cursor-type resource-def)})
        @github-resources))
