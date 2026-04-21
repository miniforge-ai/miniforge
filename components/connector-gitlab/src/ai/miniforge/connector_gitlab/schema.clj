(ns ai.miniforge.connector-gitlab.schema
  "Malli schemas for the GitLab connector."
  (:require [malli.core :as m]
            [malli.error :as me]))

;;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def GitLabConfig
  "Schema for GitLab connector configuration.
   Requires either :gitlab/project-id or :gitlab/project-path."
  [:map
   [:gitlab/base-url {:optional true} string?]
   [:gitlab/project-id {:optional true} [:maybe [:or int? string?]]]
   [:gitlab/project-path {:optional true} [:maybe string?]]
   [:gitlab/issue-iid {:optional true} int?]])

;;------------------------------------------------------------------------------ Layer 1
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
    (throw (ex-info "GitLab config validation failed"
                    {:errors (me/humanize (m/explain schema value))
                     :value value})))
  value)

(comment
  ;; (validate GitLabConfig {:gitlab/project-path "mygroup/myrepo"})
  )
