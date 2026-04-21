(ns ai.miniforge.connector-gitlab.resources
  "GitLab API v4 resource type registry and URL/param builders.
   Resource definitions are loaded from an EDN resource file at startup."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def ^:private resource-path "config/connector-gitlab/resources.edn")

(defn- load-resources []
  (if-let [res (io/resource resource-path)]
    (edn/read-string (slurp res))
    (throw (ex-info (str "GitLab resource definitions not found: " resource-path)
                    {:path resource-path}))))

(def gitlab-resources
  "Registry of GitLab REST API v4 resource types, loaded from EDN."
  (delay (load-resources)))

(defn get-resource
  "Look up a resource definition by key."
  [resource-key]
  (get @gitlab-resources resource-key))

;;------------------------------------------------------------------------------ Layer 1
;; URL and param builders

(defn- encode-project
  "URL-encode a project path, or pass through a numeric project-id."
  [config]
  (or (:gitlab/project-id config)
      (java.net.URLEncoder/encode (str (:gitlab/project-path config)) "UTF-8")))

(defn build-url
  "Build a GitLab API URL by substituting path parameters."
  [base-url resource-def config]
  (str base-url "/api/v4"
       (-> (:endpoint resource-def)
           (str/replace "{project}" (str (encode-project config)))
           (str/replace "{issue_iid}" (str (get config :gitlab/issue-iid "")))
           (str/replace "{noteable_kind}" (str (get config :gitlab/noteable-kind "")))
           (str/replace "{noteable_iid}" (str (get config :gitlab/noteable-iid ""))))))

(defn build-query-params
  "Build query params for a GitLab API request."
  [resource-def cursor opts]
  (let [base-params  (get resource-def :query-params {})
        per-page     (get opts :extract/batch-size (get resource-def :per-page 100))
        incr-param   (:incremental-param resource-def)
        cursor-value (get-in cursor [:cursor/value])]
    (cond-> (assoc base-params "per_page" per-page)
      (:order-by resource-def)      (assoc "order_by" (:order-by resource-def))
      (:sort resource-def)          (assoc "sort" (:sort resource-def))
      (and incr-param cursor-value) (assoc incr-param cursor-value))))

(defn resource-schemas
  "Return discover-compatible schema list for all known GitLab resources."
  []
  (mapv (fn [[k v]]
          {:schema/name     (name k)
           :schema/endpoint (:endpoint v)
           :schema/type     (:cursor-type v)})
        @gitlab-resources))

(comment
  ;; (build-url "https://gitlab.com" (get-resource :merge-requests)
  ;;            {:gitlab/project-path "engrammicai/ixi-services/services/ixi"})
  )
