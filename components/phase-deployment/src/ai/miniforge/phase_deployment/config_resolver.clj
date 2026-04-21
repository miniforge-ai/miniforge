;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.phase-deployment.config-resolver
  "GCP Secret Manager config resolver.

   Reads non-secret infrastructure configuration from GCP Secret Manager,
   resolves template placeholders, and validates against a Malli schema.
   Produces evidence of what was resolved from where.

   Placeholder syntax: ${gcp-sm:secret-name}

   Note: This is a focused inline resolver for the pilot. If multi-cloud
   config resolution becomes needed, evaluate Data Foundry's connector
   protocol as the abstraction layer."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.schema.interface :as schema]
            [clojure.set :as set]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Placeholder parsing + schemas

(def ^:private placeholder-pattern
  "Regex for GCP Secret Manager placeholders: ${gcp-sm:secret-name}"
  #"\$\{gcp-sm:([^}]+)\}")

(def ResolutionLogEntry
  [:map
   [:secret :string]
   [:resolved? :boolean]
   [:timestamp :int]
   [:error {:optional true} :string]])

(def ResolveTemplateSuccess
  [:map
   [:success? [:= true]]
   [:resolved :string]
   [:log [:vector ResolutionLogEntry]]])

(def ResolveTemplateFailure
  [:map
   [:success? [:= false]]
   [:resolved nil?]
   [:error :string]
   [:anomaly map?]
   [:unresolved [:vector :string]]
   [:log [:vector ResolutionLogEntry]]])

(def ResolveMapSuccess
  [:map
   [:success? [:= true]]
   [:resolved-values map?]
   [:log [:vector ResolutionLogEntry]]])

(def ResolveMapFailure
  [:map
   [:success? [:= false]]
   [:resolved-values nil?]
   [:error :string]
   [:anomaly map?]
   [:unresolved [:vector :string]]
   [:log [:vector ResolutionLogEntry]]])

(declare access-secret)

(defn- validate-result!
  [result-schema result]
  (schema/validate result-schema result))

(defn- timestamp-now
  []
  (System/currentTimeMillis))

(defn- log-entry
  [secret-name resolved? & [error-message]]
  (cond-> {:secret secret-name
           :resolved? resolved?
           :timestamp (timestamp-now)}
    error-message (assoc :error error-message)))

(defn- apply-secret-resolution
  [tmpl gcp-project secret-name resolution-log errors]
  (let [result (access-secret gcp-project secret-name)]
    (if (schema/succeeded? result)
      (do
        (swap! resolution-log conj (log-entry secret-name true))
        (str/replace tmpl
                     (str "${gcp-sm:" secret-name "}")
                     (:value result)))
      (do
        (swap! resolution-log conj (log-entry secret-name false (:error result)))
        (swap! errors conj secret-name)
        tmpl))))

(defn extract-placeholders
  "Extract all ${gcp-sm:*} placeholders from a template string.

   Arguments:
     template - String with placeholders

   Returns:
     Set of secret names (without the ${gcp-sm:} wrapper)"
  [template]
  (->> (re-seq placeholder-pattern template)
       (map second)
       set))

(defn extract-placeholders-from-map
  "Extract all placeholders from a nested map of values.

   Arguments:
     m - Map (possibly nested) with string values containing placeholders

   Returns:
     Set of secret names"
  [m]
  (let [walk-fn (fn walk [v]
                  (cond
                    (string? v) (extract-placeholders v)
                    (map? v)    (apply set/union #{} (map walk (vals v)))
                    (coll? v)   (apply set/union #{} (map walk v))
                    :else       #{}))]
    (walk-fn m)))

;------------------------------------------------------------------------------ Layer 1
;; GCP Secret Manager access

(defn- access-secret
  "Access a secret version from GCP Secret Manager.

   Uses the GCP Java SDK with Application Default Credentials (ADC).
   Lazy-loads the SDK to avoid hard dependency.

   Arguments:
     gcp-project - GCP project ID
     secret-name - Secret name

   Returns:
     {:success? true :value string}
     {:success? false :error string}"
  [gcp-project secret-name]
  (try
    (let [;; Lazy-load GCP SDK classes
          client-class (Class/forName "com.google.cloud.secretmanager.v1.SecretManagerServiceClient")
          create-fn    (.getMethod client-class "create" (into-array Class []))
          access-fn    (.getMethod client-class "accessSecretVersion"
                                  (into-array Class [String]))
          ;; Build the secret version name
          version-name (str "projects/" gcp-project "/secrets/" secret-name "/versions/latest")
          ;; Access the secret
          client       (.invoke create-fn nil (into-array Object []))
          response     (.invoke access-fn client (into-array Object [version-name]))
          payload-fn   (.getMethod (.getClass response) "getPayload" (into-array Class []))
          payload      (.invoke payload-fn response (into-array Object []))
          data-fn      (.getMethod (.getClass payload) "getData" (into-array Class []))
          data         (.invoke data-fn payload (into-array Object []))
          to-string-fn (.getMethod (.getClass data) "toStringUtf8" (into-array Class []))
          value        (.invoke to-string-fn data (into-array Object []))]
      ;; Close client
      (try (.close client) (catch Exception _))
      (schema/success :value value))
    (catch Exception e
      (schema/failure :value
                      (msg/t :config-resolver/secret-access-failed
                             {:secret-name secret-name
                              :error (ex-message e)})))))

;------------------------------------------------------------------------------ Layer 2
;; Template resolution + validation

(defn resolve-template
  "Resolve all ${gcp-sm:*} placeholders in a template string.

   Arguments:
     template    - String with ${gcp-sm:secret-name} placeholders
     gcp-project - GCP project ID

   Returns:
     {:success?  true
      :resolved  string (template with placeholders replaced)
      :log       [{:secret :resolved? :error}...] (evidence of what was resolved)}
     {:success?  false
      :error     string
      :unresolved [secret-names...]}"
  [template gcp-project]
  (let [placeholders (extract-placeholders template)
        resolution-log (atom [])
        errors         (atom [])
        resolved (reduce
                  (fn [tmpl secret-name]
                    (apply-secret-resolution tmpl
                                             gcp-project
                                             secret-name
                                             resolution-log
                                             errors))
                  template
                  placeholders)]
    (validate-result!
     (if (seq @errors) ResolveTemplateFailure ResolveTemplateSuccess)
     (if (seq @errors)
       (schema/failure :resolved
                       (msg/t :config-resolver/resolve-template-failed
                              {:count (count @errors)
                               :unresolved (pr-str @errors)})
                       {:unresolved @errors
                        :log        @resolution-log})
       (schema/success :resolved resolved
                       {:log @resolution-log})))))

(defn resolve-map
  "Resolve all ${gcp-sm:*} placeholders in a nested map.

   Arguments:
     m           - Map with string values containing placeholders
     gcp-project - GCP project ID

   Returns:
     {:success?       true
      :resolved-values map (with all placeholders replaced)
      :log            [...]}
     {:success?       false
      :error          string
      :unresolved     [...]}"
  [m gcp-project]
  (let [log-acc    (atom [])
        errors-acc (atom [])
        walk-fn    (fn walk [v]
                     (cond
                       (string? v)
                       (if (seq (extract-placeholders v))
                         (let [result (resolve-template v gcp-project)]
                           (swap! log-acc into (:log result))
                           (if (schema/succeeded? result)
                             (:resolved result)
                             (do (swap! errors-acc into (:unresolved result))
                                 v)))
                         v)
                       (map? v)    (into {} (map (fn [[k val]] [k (walk val)]) v))
                       (vector? v) (mapv walk v)
                       (seq? v)    (map walk v)
                       :else       v))
        resolved (walk-fn m)]
    (validate-result!
     (if (seq @errors-acc) ResolveMapFailure ResolveMapSuccess)
     (if (seq @errors-acc)
       (schema/failure :resolved-values
                       (msg/t :config-resolver/resolve-map-failed
                              {:count (count @errors-acc)})
                       {:unresolved @errors-acc
                        :log        @log-acc})
       (schema/success :resolved-values resolved
                       {:log @log-acc})))))

;; Schema validation (uses Malli via requiring-resolve)

(defn validate-config
  "Validate resolved config against a Malli schema.

   Arguments:
     config - Resolved config map
     schema - Malli schema (vector form)

   Returns:
     {:valid?  boolean
      :errors  nil or humanized error map}"
  [config config-schema]
  (try
    (let [validate-fn (requiring-resolve 'malli.core/validate)
          explain-fn  (requiring-resolve 'malli.core/explain)
          humanize-fn (requiring-resolve 'malli.error/humanize)]
      (if (validate-fn config-schema config)
        (schema/valid)
        (schema/invalid-with-errors (humanize-fn (explain-fn config-schema config)))))
    (catch Exception e
      (schema/invalid-with-errors
       {:_schema (msg/t :config-resolver/validation-error
                        {:error (ex-message e)})}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Parse placeholders
  (extract-placeholders "host: ${gcp-sm:db-host}, port: ${gcp-sm:db-port}")
  ;; => #{"db-host" "db-port"}

  ;; Resolve (requires GCP auth)
  ;; (resolve-template "host: ${gcp-sm:db-host}" "my-project")

  ;; Validate
  ;; (validate-config {:db-host "10.0.0.1" :db-port 5432}
  ;;                  [:map [:db-host :string] [:db-port :int]])

  :leave-this-here)
