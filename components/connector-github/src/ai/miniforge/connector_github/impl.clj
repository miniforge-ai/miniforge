(ns ai.miniforge.connector-github.impl
  "Implementation functions for the GitHub REST API connector."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-auth.interface :as auth]
            [ai.miniforge.connector-github.messages :as msg]
            [ai.miniforge.connector-github.resources :as resources]
            [ai.miniforge.connector-github.schema :as gh-schema]
            [ai.miniforge.connector-http.interface :as http])
  (:import [java.util UUID]))

;; -- Handle state --

(def ^:private handles (connector/create-handle-registry))

(defn get-handle [handle] (connector/get-handle handles handle))
(defn store-handle! [handle state] (connector/store-handle! handles handle state))
(defn remove-handle! [handle] (connector/remove-handle! handles handle))
(defn touch-handle! [handle] (connector/touch-handle! handles handle))

;; -- Auth --

(defn- build-auth-headers
  "Build authorization headers from auth config.
   Supports :api-key (Bearer token) and :oauth2 (Bearer token)."
  [{:auth/keys [method credential-id]}]
  (case method
    :api-key {"Authorization" (str "Bearer " credential-id)}
    :oauth2  {"Authorization" (str "Bearer " credential-id)}
    {}))

;; -- Rate limiting --
;; Uses response-header-driven rate limiting via connector-http/rate-limit.
;; GitHub returns x-ratelimit-remaining and x-ratelimit-reset on every response.

(def ^:private github-rate-headers
  {:remaining "x-ratelimit-remaining"
   :reset     "x-ratelimit-reset"
   :limit     "x-ratelimit-limit"})

(defn- update-rate-limits!
  "Parse rate limit headers from a response and store in handle state."
  [handle response-headers]
  (when-let [info (http/parse-rate-headers response-headers github-rate-headers)]
    (http/update-rate-state! handles handle info)))

;; -- Record filters --

(defn- issue-not-pr?
  "Predicate: true if a GitHub issue record is NOT a pull request.
   GitHub's /issues endpoint returns PRs mixed in — they have a :pull_request key."
  [record]
  (nil? (:pull_request record)))

(defn- filter-issues
  "Filter PRs from issues endpoint results. Only applied to :issues resource."
  [resource-key records]
  (if (= :issues resource-key)
    (filterv issue-not-pr? records)
    records))

;; -- HTTP helpers --

(defn- github-headers
  "Merge auth headers with GitHub-required headers."
  [auth-headers]
  (merge auth-headers
         {"Accept"               "application/vnd.github+json"
          "X-GitHub-Api-Version" "2022-11-28"}))

(defn- error-response
  [status resp]
  (http/error-response status resp
    {:rate-limited   (msg/t :github/rate-limited)
     :request-failed (fn [s e] (msg/t :github/request-failed {:status s :error e}))}))

(defn- do-request
  [url headers query-params]
  (http/do-request url headers query-params error-response))

(defn- response-records
  [resource-key resp]
  (filter-issues resource-key (http/coerce-records (:body resp))))

(defn- final-cursor
  [resource-def records]
  (http/last-record-cursor resource-def records))

(defn- page-fetch-result
  [records cursor]
  {:records records
   :cursor  cursor})

(defn- timestamp-value
  "Extract the most recent timestamp from a record.
   Reviews use :submitted_at; other resources use :updated_at/:created_at."
  [record]
  (or (:updated_at record)
      (:created_at record)
      (:submitted_at record)))

(defn- fetch-all-pages
  [handle resource-key resource-def headers url query-params]
  (loop [next-request-url url
         next-query-params query-params
         accumulated []]
    (http/acquire-permit! handles handle {})
    (let [resp (http/throw-on-failure! (do-request next-request-url headers next-query-params))]
      (update-rate-limits! handle (:headers resp))
      (touch-handle! handle)
      (if (:not-modified resp)
        (page-fetch-result [] nil)
        (let [page-records (response-records resource-key resp)
              all-records  (into accumulated page-records)]
          (if-let [next-link (http/next-url resp)]
            (recur next-link nil all-records)
            (page-fetch-result all-records
                               (final-cursor resource-def all-records))))))))


(defn- resource-records
  [handle resource-key resource-def config headers cursor opts]
  (let [url          (resources/build-url (:github/base-url config) resource-def config)
        query-params (resources/build-query-params resource-def cursor opts)]
    (:records (fetch-all-pages handle resource-key resource-def headers url query-params))))

(defn- enrich-review
  [pull review]
  (assoc review
         :pull_number (:number pull)
         :pull_id (:id pull)
         :pull_title (:title pull)
         :pull_html_url (:html_url pull)
         :pull_state (:state pull)))

(defn- pull-review-records
  [handle reviews-resource config headers cursor opts pull]
  (let [review-config (assoc config :github/pull-number (:number pull))
        records       (resource-records handle :reviews reviews-resource review-config headers nil opts)]
    (->> records
         (map #(enrich-review pull %))
         (filter #(http/after-cursor? timestamp-value cursor %)))))

(defn- extract-reviews
  [handle config headers cursor opts]
  (let [pulls-resource (resources/get-resource :pulls)
        pulls          (resource-records handle :pulls pulls-resource config headers cursor opts)
        reviews        (->> pulls
                            (mapcat #(pull-review-records handle
                                                          (resources/get-resource :reviews)
                                                          config
                                                          headers
                                                          cursor
                                                          opts
                                                          %))
                            (http/sort-by-timestamp timestamp-value)
                            vec)]
    (connector/extract-result reviews (http/max-timestamp-cursor timestamp-value reviews) false)))

;; -- Helpers --

(defn- require-handle!
  "Retrieve handle state or throw."
  [handle]
  (or (get-handle handle)
      (throw (ex-info (msg/t :github/handle-not-found {:handle handle})
                      {:handle handle}))))

(defn- require-resource!
  "Look up resource def or throw."
  [schema-name]
  (or (resources/get-resource (keyword schema-name))
      (throw (ex-info (msg/t :github/resource-unknown {:resource schema-name})
                      {:resource schema-name}))))

(defn- validation-errors
  "Extract errors from a validation result, centralizing knowledge of the :errors key."
  [result]
  (:errors result))

(defn- validate-connect!
  "Validate config and auth sequentially, throwing on first failure."
  [config auth]
  (let [config-result (gh-schema/validate-config config)
        auth-result   (when auth (auth/validate-credential-ref auth))]
    (cond
      (gh-schema/invalid? config-result)
      (let [errs (validation-errors config-result)]
        (throw (ex-info (msg/t :github/config-invalid {:errors errs}) {:errors errs})))

      (and (nil? (:github/org config)) (nil? (:github/owner config)))
      (throw (ex-info (msg/t :github/owner-or-org-required) {:config config}))

      (and auth-result (not (:success? auth-result)))
      (let [errs (validation-errors auth-result)]
        (throw (ex-info (msg/t :github/auth-invalid {:errors errs}) {:errors errs}))))))

;; -- Lifecycle --
(defn do-connect
  "Validate config and auth, register handle. Returns connect-result."
  [config auth]
  (validate-connect! config auth)
  (let [handle       (str (UUID/randomUUID))
        base-url     (get config :github/base-url "https://api.github.com")
        auth-headers (if (:auth/method auth) (build-auth-headers auth) {})]
    (store-handle! handle {:config          (assoc config :github/base-url base-url)
                           :auth-headers    auth-headers
                           :last-request-at nil})
    (connector/connect-result handle)))

(defn do-close
  "Remove handle state. Returns close-result."
  [handle]
  (remove-handle! handle)
  (connector/close-result))

;; -- Source --

(defn do-discover
  "Return available resource schemas based on config."
  [handle]
  (require-handle! handle)
  (connector/discover-result (resources/resource-schemas)))

(defn do-extract
  "Extract records from a GitHub API resource."
  [handle schema-name opts]
  (let [handle-state (require-handle! handle)
        resource-def (require-resource! schema-name)
        resource-key (keyword schema-name)
        {:keys [config auth-headers]} handle-state
        cursor       (:extract/cursor opts)
        headers      (github-headers auth-headers)]
    (if (= "reviews" schema-name)
      (extract-reviews handle config headers cursor opts)
      (let [url          (resources/build-url (:github/base-url config) resource-def config)
            query-params (resources/build-query-params resource-def cursor opts)
            {:keys [records cursor]} (fetch-all-pages handle resource-key resource-def headers url query-params)]
        (connector/extract-result records cursor false)))))

(defn do-checkpoint
  "Persist cursor state. Returns checkpoint-result."
  [cursor-state]
  (connector/checkpoint-result cursor-state))
