(ns ai.miniforge.data-foundry.connector-gitlab.impl
  "Implementation functions for the GitLab REST API connector.
   Small composable functions organized in a stratified DAG."
  (:require [ai.miniforge.data-foundry.connector.handles :as h]
            [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-auth.core :as auth]
            [ai.miniforge.data-foundry.connector-gitlab.messages :as msg]
            [ai.miniforge.data-foundry.connector-gitlab.resources :as resources]
            [ai.miniforge.data-foundry.connector-gitlab.schema :as schema]
            [ai.miniforge.data-foundry.connector-http.cursors :as cursors]
            [ai.miniforge.data-foundry.connector-http.rate-limit :as rate]
            [ai.miniforge.data-foundry.connector-http.request :as request])
  (:import [java.util UUID]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle state

(def ^:private handles (h/create))

(defn- get-handle    [handle] (h/get-handle handles handle))
(defn- store-handle! [handle state] (h/store-handle! handles handle state))
(defn- remove-handle! [handle] (h/remove-handle! handles handle))
(defn- touch-handle! [handle] (h/touch-handle! handles handle))

(defn- require-handle!
  "Retrieve handle state or throw."
  [handle]
  (or (get-handle handle)
      (throw (ex-info (msg/t :gitlab/handle-not-found {:handle handle})
                      {:handle handle}))))

;; Auth

(defn- build-auth-headers
  "Build authorization headers for GitLab API.
   Both :api-key and :oauth2 use Bearer — GitLab accepts Bearer for
   PATs, OAuth tokens, and project/group tokens."
  [{:auth/keys [method credential-id]}]
  (case method
    :api-key {"Authorization" (str "Bearer " credential-id)}
    :oauth2  {"Authorization" (str "Bearer " credential-id)}
    {}))

(defn- resolve-auth-headers
  "Build auth headers from auth config, defaulting to empty map."
  [auth]
  (if (:auth/method auth)
    (build-auth-headers auth)
    {}))

;; Rate limiting — response-header-driven via connector-http/rate-limit.
;; GitLab returns ratelimit-remaining and ratelimit-reset on every response.

(def ^:private gitlab-rate-headers
  {:remaining "ratelimit-remaining"
   :reset     "ratelimit-reset"
   :limit     "ratelimit-limit"})

(defn- update-rate-limits!
  "Parse rate limit headers from a response and store in handle state."
  [handle response-headers]
  (when-let [info (rate/parse-rate-headers response-headers gitlab-rate-headers)]
    (rate/update-rate-state! handles handle info)))

;; Config predicates

(defn- has-project?
  "Check if config identifies a GitLab project."
  [config]
  (some? (or (:gitlab/project-id config) (:gitlab/project-path config))))

(defn- validate-auth!
  "Validate auth credential reference, throwing on failure."
  [auth]
  (when auth
    (let [result (auth/validate-credential-ref auth)]
      (when-not (:success? result)
        (throw (ex-info (msg/t :gitlab/auth-invalid {:errors (:errors result)})
                        {:errors (:errors result)}))))))

;;------------------------------------------------------------------------------ Layer 1
;; HTTP

(defn- error-response
  [status resp]
  (request/error-response status resp
    {:rate-limited   (msg/t :gitlab/rate-limited)
     :request-failed (fn [s e] (msg/t :gitlab/request-failed {:status s :error e}))}))

(defn- do-request
  [url headers query-params]
  (request/do-request url headers query-params error-response))


(defn- response-records
  [resp]
  (request/coerce-records (:body resp)))

(defn- final-cursor
  [resource-def records]
  (cursors/last-record-cursor resource-def records))

(defn- page-fetch-result
  [records cursor]
  {:records records
   :cursor  cursor})

(def ^:private note-parent-scopes
  [{:resource-key   :issues
    :noteable-kind  "issues"
    :noteable-type  "Issue"}
   {:resource-key   :merge-requests
    :noteable-kind  "merge_requests"
    :noteable-type  "MergeRequest"}])

(defn- fetch-all-pages
  [handle resource-def url headers query-params]
  (loop [next-request-url url
         next-query-params query-params
         accumulated []]
    (rate/acquire-permit! handles handle {})
    (let [resp (request/throw-on-failure! (do-request next-request-url headers next-query-params))]
      (update-rate-limits! handle (:headers resp))
      (touch-handle! handle)
      (if (:not-modified resp)
        (page-fetch-result [] nil)
        (let [page-records (response-records resp)
              all-records  (into accumulated page-records)]
          (if-let [next-link (request/next-url resp)]
            (recur next-link nil all-records)
            (page-fetch-result all-records
                               (final-cursor resource-def all-records))))))))

(defn- timestamp-value
  [record]
  (or (:updated_at record)
      (:created_at record)))

;; Resource lookup

(defn- require-resource!
  "Look up resource def or throw."
  [schema-name]
  (or (resources/get-resource (keyword schema-name))
      (throw (ex-info (msg/t :gitlab/resource-unknown {:resource schema-name})
                      {:resource schema-name}))))

(defn- resource-records
  [handle resource-def config auth-headers cursor opts]
  (let [url    (resources/build-url (:gitlab/base-url config) resource-def config)
        params (resources/build-query-params resource-def cursor opts)]
    (:records (fetch-all-pages handle resource-def url auth-headers params))))

(defn- note-parent-records
  [handle config auth-headers cursor opts]
  (mapcat (fn [{:keys [resource-key] :as scope}]
            (let [resource-def (require-resource! (name resource-key))
                  records      (resource-records handle resource-def config auth-headers cursor opts)]
              (map #(vector scope %) records)))
          note-parent-scopes))

(defn- enrich-note
  [scope parent note]
  (assoc note
         :project_id (or (:project_id note) (:project_id parent))
         :noteable_type (or (:noteable_type note) (:noteable-type scope))
         :noteable_kind (:noteable-kind scope)
         :noteable_iid (:iid parent)
         :noteable_id (:id parent)
         :noteable_title (:title parent)
         :noteable_web_url (:web_url parent)
         :noteable_state (:state parent)))

(defn- parent-note-records
  [handle notes-resource config auth-headers cursor opts [scope parent]]
  (let [note-config (assoc config
                           :gitlab/noteable-kind (:noteable-kind scope)
                           :gitlab/noteable-iid (:iid parent))
        records     (resource-records handle notes-resource note-config auth-headers nil opts)]
    (->> records
         (map #(enrich-note scope parent %))
         (filter #(cursors/after-cursor? timestamp-value cursor %)))))

(defn- extract-project-notes
  [handle config auth-headers cursor opts]
  (let [notes-resource (require-resource! "notes")
        notes         (->> (note-parent-records handle config auth-headers cursor opts)
                           (mapcat #(parent-note-records handle notes-resource config auth-headers cursor opts %))
                           (cursors/sort-by-timestamp timestamp-value)
                           vec)]
    (result/extract-result notes (cursors/max-timestamp-cursor timestamp-value notes) false)))

;;------------------------------------------------------------------------------ Layer 2
;; Lifecycle and source operations

(defn do-connect
  "Validate config at boundary, register handle."
  [config auth]
  (let [config (assoc config :gitlab/base-url
                      (get config :gitlab/base-url "https://gitlab.com"))]
    (when-not (has-project? config)
      (throw (ex-info (msg/t :gitlab/project-required) {:config config})))
    (schema/validate! schema/GitLabConfig config)
    (validate-auth! auth)
    (let [handle (str (UUID/randomUUID))]
      (store-handle! handle {:config       config
                              :auth-headers (resolve-auth-headers auth)
                              :last-request-at nil})
      (result/connect-result handle))))

(defn do-close [handle]
  (remove-handle! handle)
  (result/close-result))

(defn do-discover [handle]
  (require-handle! handle)
  (result/discover-result (resources/resource-schemas)))

(defn do-extract
  "Extract records from a GitLab API resource."
  [handle schema-name opts]
  (let [handle-state (require-handle! handle)
        resource-def (require-resource! schema-name)
        {:keys [config auth-headers]} handle-state]
    (if (= "notes" schema-name)
      (extract-project-notes handle config auth-headers (:extract/cursor opts) opts)
      (let [url     (resources/build-url (:gitlab/base-url config) resource-def config)
            params  (resources/build-query-params resource-def (:extract/cursor opts) opts)
            fetch   (fn [] (let [{:keys [records cursor]}
                                 (fetch-all-pages handle resource-def url auth-headers params)]
                             (result/extract-result records cursor false)))]
        (if (:optional? resource-def)
          (try (fetch)
               (catch clojure.lang.ExceptionInfo e
                 (when-not (= :permanent (:error-type (ex-data e))) (throw e))
                 (result/extract-result [] nil false)))
          (fetch))))))

(defn do-checkpoint [cursor-state]
  (result/checkpoint-result cursor-state))

(comment
  ;; (do-connect {:gitlab/project-path "engrammicai/ixi-services/services/ixi"} nil)
  )
