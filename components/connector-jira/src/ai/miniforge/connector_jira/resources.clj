(ns ai.miniforge.connector-jira.resources
  "Jira Cloud REST API resource type registry and URL/param builders.
   Resource definitions are loaded from an EDN resource file at startup."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def ^:private resource-path "config/connector-jira/resources.edn")

(defn- load-resources []
  (if-let [res (io/resource resource-path)]
    (edn/read-string (slurp res))
    (throw (ex-info (str "Jira resource definitions not found: " resource-path)
                    {:path resource-path}))))

(def jira-resources
  "Registry of Jira Cloud REST API resource types, loaded from EDN."
  (delay (load-resources)))

(defn get-resource
  "Look up a resource definition by key."
  [resource-key]
  (get @jira-resources resource-key))

;;------------------------------------------------------------------------------ Layer 1
;; URL and param builders

(defn build-base-url
  "Build the Jira Cloud base URL from config.
   When :jira/cloud-id is present, uses the Atlassian API gateway — required for
   scoped API tokens (tokens with explicit permission scopes).
   Falls back to the classic site URL for classic (unscoped) API tokens."
  [config]
  (if-let [cloud-id (:jira/cloud-id config)]
    (str "https://api.atlassian.com/ex/jira/" cloud-id)
    (str "https://" (:jira/site config) ".atlassian.net")))

(defn build-url
  "Build a Jira API URL by substituting path parameters."
  [base-url resource-def config]
  (str base-url
       (-> (:endpoint resource-def)
           (str/replace "{board_id}" (str (get config :jira/board-id "")))
           (str/replace "{issue_key}" (str (get config :jira/issue-key ""))))))

(defn- resolved-project-key
  "Return :jira/project-key from config only if it is a real resolved value.
   Treats nil, blank, and unresolved placeholders (${...}) as absent."
  [config]
  (let [pk (:jira/project-key config)]
    (when (and (string? pk)
               (not (str/blank? pk))
               (not (str/starts-with? pk "${")))
      pk)))

(defn build-query-params
  "Build query params for a Jira API request.
   For the issues resource, constructs JQL with optional project and cursor filters.

   The /rest/api/3/search/jql endpoint rejects unbounded queries (ORDER BY alone).
   We always include an `updated >= <date>` predicate: the cursor value when
   available, or a far-past sentinel on first run to fetch all issues."
  [resource-def cursor opts config]
  (let [per-page (get opts :extract/batch-size (get resource-def :per-page 100))
        params   (cond-> {"maxResults" per-page}
                   (:default-fields resource-def)
                   (assoc "fields" (:default-fields resource-def)))]
    (if (:uses-jql resource-def)
      (let [project-key    (resolved-project-key config)
            project-clause (when project-key (str "project = " project-key))
            cursor-val     (get-in cursor [:cursor/value])
            since-date     (or cursor-val "2000-01-01 00:00")
            since-clause   (str "updated >= \"" since-date "\"")
            clauses        (remove nil? [project-clause since-clause])
            jql            (str (str/join " AND " clauses) " ORDER BY updated DESC")]
        (assoc params "jql" jql))
      ;; Other resources: no JQL
      params)))

(defn resource-schemas
  "Return discover-compatible schema list for all known Jira resources."
  []
  (mapv (fn [[k v]]
          {:schema/name     (name k)
           :schema/endpoint (:endpoint v)
           :schema/type     (:cursor-type v)})
        @jira-resources))
