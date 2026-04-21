(ns ai.miniforge.connector-jira.schema
  "Malli schemas for the Jira connector.

   Two categories of schemas:
   1. Config schemas — validated at connect time (inbound from user)
   2. Response schemas — validated at extract time (inbound from Jira API)"
  (:require [malli.core :as m]
            [malli.error :as me]))

;;------------------------------------------------------------------------------ Layer 0
;; Config schemas (user → connector boundary)

(def JiraConfig
  "Schema for Jira connector configuration.
   Requires :jira/site (the Atlassian subdomain, e.g. \"mycompany\")."
  [:map
   [:jira/site string?]
   [:jira/project-key {:optional true} [:maybe string?]]
   [:jira/board-id {:optional true} [:maybe [:or int? string?]]]
   [:jira/issue-key {:optional true} [:maybe string?]]
   [:jira/email {:optional true} [:maybe string?]]
   [:jira/cloud-id {:optional true} [:maybe string?]]])

;;------------------------------------------------------------------------------ Layer 1
;; API response schemas (Jira API → connector boundary)

(def JiraIssue
  "Schema for a Jira issue record from /rest/api/3/search."
  [:map
   [:id string?]
   [:key string?]
   [:fields [:map
             [:summary {:optional true} [:maybe string?]]
             [:status {:optional true} [:maybe [:map [:name {:optional true} string?]]]]
             [:updated {:optional true} [:maybe string?]]
             [:created {:optional true} [:maybe string?]]]]])

(def JiraProject
  "Schema for a Jira project record from /rest/api/3/project/search."
  [:map
   [:id string?]
   [:key string?]
   [:name string?]])

(def JiraBoard
  "Schema for a Jira board record from /rest/agile/1.0/board."
  [:map
   [:id int?]
   [:name string?]])

(def JiraSprint
  "Schema for a Jira sprint record from the sprints endpoint."
  [:map
   [:id int?]
   [:name string?]
   [:state string?]])

(def JiraComment
  "Schema for a Jira comment record from issue comments endpoint."
  [:map
   [:id string?]
   [:body {:optional true} :any]
   [:created {:optional true} [:maybe string?]]
   [:updated {:optional true} [:maybe string?]]])

(def JiraPaginatedResponse
  "Schema for the offset-paginated response envelope."
  [:map
   [:startAt int?]
   [:maxResults int?]
   [:total int?]])

(def ^:private resource->schema
  {:issues   JiraIssue
   :projects JiraProject
   :boards   JiraBoard
   :sprints  JiraSprint
   :comments JiraComment})

;;------------------------------------------------------------------------------ Layer 2
;; Validation

(defn validate
  "Validate value against schema."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn validate!
  "Validate and throw on failure."
  [schema value]
  (when-not (m/validate schema value)
    (throw (ex-info "Jira config validation failed"
                    {:errors (me/humanize (m/explain schema value))
                     :value value})))
  value)

(defn validate-response
  "Validate a paginated response envelope. Returns {:valid? bool :errors ...}."
  [body]
  (validate JiraPaginatedResponse body))

(defn record-schema
  "Look up the record schema for a resource keyword, or nil."
  [resource-key]
  (get resource->schema resource-key))

(defn validate-records
  "Validate a batch of records against the schema for the given resource.
   Returns records unchanged if valid; logs and filters invalid records."
  [resource-key records]
  (if-let [schema (record-schema resource-key)]
    (filterv #(m/validate schema %) records)
    records))
