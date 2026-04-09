(ns ai.miniforge.data-foundry.connector-auth.core
  (:require [ai.miniforge.data-foundry.connector-auth.messages :as msg]))

(def ^:private method-required-fields
  "Required fields per auth method, per N2 §5.2"
  {:api-key          #{:auth/credential-id}
   :basic            #{:auth/credential-id}
   :certificate      #{:auth/credential-id :auth/certificate-path}
   :none             #{}
   :oauth2           #{:auth/credential-id :auth/client-id :auth/token-endpoint}
   :vault-reference  #{:auth/credential-id :auth/vault-path}})

(def ^:private forbidden-keys
  "Keys that must never appear in a credential reference (N2 §5.1)"
  #{:auth/password :auth/secret :auth/api-key-value :auth/token
    :auth/private-key :auth/secret-key :auth/access-key})

(defn required-fields-for-method
  [method]
  (get method-required-fields method #{}))

(defn inline-secret?
  "Check if credential ref contains inline secrets."
  [cred-ref]
  (boolean (some #(contains? cred-ref %) forbidden-keys)))

(defn validate-credential-ref
  "Validate a credential reference."
  [{:auth/keys [method credential-id] :as cred-ref}]
  (let [errors
        (cond-> []
          (nil? method)
          (conj (msg/t :validate/method-required))

          (and method (not (contains? (set (keys method-required-fields)) method)))
          (conj (msg/t :validate/method-unsupported
                       {:method method :allowed (keys method-required-fields)}))

          (and (not= method :none) (nil? credential-id))
          (conj (msg/t :validate/credential-id-required))

          (and (not= method :none) (string? credential-id) (empty? credential-id))
          (conj (msg/t :validate/credential-id-empty))

          (inline-secret? cred-ref)
          (conj (msg/t :validate/inline-secrets
                       {:keys (filterv #(contains? cred-ref %) forbidden-keys)})))
        ;; Check method-specific required fields
        method-errors
        (when method
          (let [required (get method-required-fields method)]
            (for [field required
                  :when (nil? (get cred-ref field))]
              (msg/t :validate/missing-field {:field field :method method}))))]
    (let [all-errors (vec (concat errors method-errors))]
      (if (empty? all-errors)
        {:success? true}
        {:success? false :errors all-errors}))))

(defn create-credential-ref
  "Create a validated credential reference."
  [opts]
  (let [cred-ref (select-keys opts
                              [:auth/method :auth/credential-id :auth/credential-scope
                               :auth/client-id :auth/token-endpoint
                               :auth/certificate-path :auth/vault-path])
        validation (validate-credential-ref cred-ref)]
    (if (:success? validation)
      {:success? true :credential-ref cred-ref}
      {:success? false :errors (:errors validation)})))
