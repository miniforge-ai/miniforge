(ns ai.miniforge.connector-auth.interface
  (:require [ai.miniforge.connector-auth.core :as core]))

;; -- Auth Methods --
(def auth-methods
  "N2 §5.2 supported authentication methods"
  #{:api-key :basic :certificate :none :oauth2 :vault-reference})

(defn valid-auth-method?
  "Returns true if method is a supported authentication method."
  [method]
  (contains? auth-methods method))

;; -- Credential Reference Model --
(defn create-credential-ref
  "Create a credential reference (never contains actual secrets).
   Required: :auth/method, :auth/credential-id
   Optional: :auth/credential-scope, :auth/client-id, :auth/token-endpoint,
             :auth/certificate-path, :auth/vault-path"
  [opts]
  (core/create-credential-ref opts))

(defn validate-credential-ref
  "Validate a credential reference. Returns {:success? true} or {:success? false :errors [...]}"
  [cred-ref]
  (core/validate-credential-ref cred-ref))

(defn required-fields-for-method
  "Returns set of required fields for a given auth method."
  [method]
  (core/required-fields-for-method method))

(defn inline-secret?
  "Returns true if the credential reference appears to contain inline secrets (violation of N2 §5.1).
   Checks for common secret-like keys."
  [cred-ref]
  (core/inline-secret? cred-ref))
