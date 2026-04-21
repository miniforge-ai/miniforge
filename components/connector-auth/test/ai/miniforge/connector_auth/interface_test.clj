(ns ai.miniforge.connector-auth.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-auth.interface :as auth]))

(deftest auth-methods-test
  (testing "N2 §5.2 all methods present"
    (is (= #{:api-key :basic :certificate :none :oauth2 :vault-reference}
           auth/auth-methods))
    (is (true? (auth/valid-auth-method? :api-key)))
    (is (true? (auth/valid-auth-method? :none)))
    (is (false? (auth/valid-auth-method? :unknown)))))

(deftest create-credential-ref-test
  (testing "AUTH-01: Valid API key credential ref"
    (let [result (auth/create-credential-ref
                  {:auth/method :api-key
                   :auth/credential-id "cred-fred-api-12345"
                   :auth/credential-scope "fred.stlouisfed.org"})]
      (is (:success? result))
      (is (= :api-key (get-in result [:credential-ref :auth/method])))
      (is (= "cred-fred-api-12345" (get-in result [:credential-ref :auth/credential-id])))))

  (testing "AUTH-02: Valid OAuth2 credential ref"
    (let [result (auth/create-credential-ref
                  {:auth/method :oauth2
                   :auth/credential-id "cred-oauth-123"
                   :auth/client-id "client-abc"
                   :auth/token-endpoint "https://auth.example.com/token"})]
      (is (:success? result))))

  (testing "AUTH-03: Valid basic auth credential ref"
    (let [result (auth/create-credential-ref
                  {:auth/method :basic
                   :auth/credential-id "cred-postgres-user"})]
      (is (:success? result))))

  (testing "Valid certificate credential ref"
    (let [result (auth/create-credential-ref
                  {:auth/method :certificate
                   :auth/credential-id "cred-cert-123"
                   :auth/certificate-path "/etc/certs/client.pem"})]
      (is (:success? result))))

  (testing "Valid vault reference credential ref"
    (let [result (auth/create-credential-ref
                  {:auth/method :vault-reference
                   :auth/credential-id "cred-aws-s3-role"
                   :auth/vault-path "secret/aws/s3-user"})]
      (is (:success? result))))

  (testing "Valid :none credential ref (no credential-id needed)"
    (let [result (auth/create-credential-ref {:auth/method :none})]
      (is (:success? result))
      (is (= :none (get-in result [:credential-ref :auth/method]))))))

(deftest validation-errors-test
  (testing "AUTH-04: Missing auth method"
    (let [result (auth/create-credential-ref {:auth/credential-id "x"})]
      (is (not (:success? result)))
      (is (some #(re-find #"method" %) (:errors result)))))

  (testing "Missing credential-id"
    (let [result (auth/create-credential-ref {:auth/method :api-key})]
      (is (not (:success? result)))))

  (testing "OAuth2 missing required fields"
    (let [result (auth/create-credential-ref
                  {:auth/method :oauth2
                   :auth/credential-id "x"})]
      (is (not (:success? result)))
      (is (some #(re-find #"client-id" %) (:errors result))))))

(deftest inline-secret-detection-test
  (testing "N2 §5.1: Detect inline secrets"
    (is (true? (auth/inline-secret?
                {:auth/method :api-key
                 :auth/credential-id "x"
                 :auth/password "hunter2"})))
    (is (true? (auth/inline-secret?
                {:auth/method :api-key
                 :auth/credential-id "x"
                 :auth/secret "s3cr3t"})))
    (is (false? (auth/inline-secret?
                 {:auth/method :api-key
                  :auth/credential-id "x"
                  :auth/credential-scope "example.com"})))))

(deftest required-fields-test
  (testing "Method-specific required fields"
    (is (= #{:auth/credential-id} (auth/required-fields-for-method :api-key)))
    (is (= #{:auth/credential-id :auth/client-id :auth/token-endpoint}
           (auth/required-fields-for-method :oauth2)))
    (is (= #{:auth/credential-id :auth/vault-path}
           (auth/required-fields-for-method :vault-reference)))
    (is (= #{} (auth/required-fields-for-method :none)))))
