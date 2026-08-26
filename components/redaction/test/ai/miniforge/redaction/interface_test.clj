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
(ns ai.miniforge.redaction.interface-test
  "N3 §8 conformance — N3.SD.1 (never emit excluded values), N3.SD.3
   (substitute the marker, never omit the key)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.redaction.interface :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private marker
  ;; Deliberately the literal from N3 §8.2, not (sut/marker). Reading the
  ;; marker from the policy under test would make every assertion below
  ;; agree with whatever the config says, including a config that has
  ;; drifted off the spec. The test below pins the two together.
  "[REDACTED]")

(deftest ^{:stratum 0} redaction-is-idempotent-test
  (testing "re-redacting a redacted value changes nothing"
    (let [event {:message "token sk-abcdefghijklmnopqrst" :password "p"}
          once  (sut/redact event)]
      (is (= once (sut/redact once)))
      (is (sut/clean? once)))))

(deftest ^{:stratum 0} clean-detects-unredacted-secrets-test
  (is (not (sut/clean? {:password "x"})))
  (is (not (sut/clean? "AKIAIOSFODNN7EXAMPLE")))
  (is (sut/clean? {:workflow/id "abc" :message "nothing secret here"})))

(deftest ^{:stratum 0} non-string-scalars-pass-through-test
  (testing "identifiers, counters and enums are :public class (N3 §8.4)"
    (let [event {:event/sequence-number 42 :event/type :workflow/started
                 :ok? true :ratio 0.5 :nothing nil}]
      (is (= event (sut/redact event))))))

(defrecord ^{:stratum 0} PayloadRecord [password note])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} marker-is-the-one-N3-mandates-test
  (testing "N3 §8.2 names the marker; the config may not choose another"
    (is (= marker (sut/marker)))))

(deftest ^{:stratum 1} secret-naming-key-redacts-its-value-test
  (testing "a password is detectable only by its key — no value pattern matches it"
    (is (= {:password marker} (sut/redact {:password "hunter2"})))
    (is (= {:api-key marker} (sut/redact {:api-key "plainish"})))
    (is (= {"AWS_SECRET_ACCESS_KEY" marker}
           (sut/redact {"AWS_SECRET_ACCESS_KEY" "abc"}))))
  (testing "the key survives — an omitted key hides that the field existed"
    (is (contains? (sut/redact {:token "x"}) :token))))

(deftest ^{:stratum 1} secret-shaped-values-redact-anywhere-test
  (testing "recognised credential shapes"
    (is (= marker (sut/redact "AKIAIOSFODNN7EXAMPLE")))
    (is (= marker (sut/redact "sk-abcdefghijklmnopqrst")))
    (is (= marker (sut/redact "ghp_abcdefghijklmnopqrstuvwxyz0123")))
    (is (str/includes? (sut/redact "-----BEGIN RSA PRIVATE KEY-----") marker)))
  (testing "a PEM block loses its key material, not just its header"
    ;; Replacing the BEGIN line alone left the base64 body — the part
    ;; that is actually the key — sitting in the event.
    (let [body "MIIEpAIBAAKCAQEAvxQ8kZ2mNqR7wTc3d4e5f6g7h8i9j0kLmNoPqRsTuVwX"]
      (doseq [pem [(str "-----BEGIN RSA PRIVATE KEY-----\n" body
                        "\n-----END RSA PRIVATE KEY-----")
                   (str "-----BEGIN PRIVATE KEY-----\n" body
                        "\n-----END PRIVATE KEY-----")
                   ;; truncated: a key without its END marker is still a key
                   (str "-----BEGIN OPENSSH PRIVATE KEY-----\n" body)]]
        (let [out (sut/redact pem)]
          (is (str/includes? out marker))
          (is (not (str/includes? out body))
              "the base64 key material must not survive")))))
  (testing "connection string with inline credentials"
    (is (str/includes? (sut/redact "postgres://user:pw@host/db") marker))))

(deftest ^{:stratum 1} free-text-keeps-its-sentence-test
  (testing "only the secret is replaced, so the message stays auditable"
    (let [out (sut/redact "Running deploy.sh with AKIAIOSFODNN7EXAMPLE now")]
      (is (str/includes? out "Running deploy.sh"))
      (is (str/includes? out "now"))
      (is (str/includes? out marker))
      (is (not (str/includes? out "AKIAIOSFODNN7EXAMPLE"))))))

(deftest ^{:stratum 1} nested-structures-are-walked-test
  (testing "N3 §8.1 covers any event field, however deeply nested"
    (let [event {:event/type :tool/invoked
                 :tool/args  {:command "deploy.sh"
                              :env     {:API_TOKEN "sk-abcdefghijklmnopqrst"}}
                 :items      [{:secret "s"} #{"AKIAIOSFODNN7EXAMPLE"}]}
          out   (sut/redact event)]
      (is (= marker (get-in out [:tool/args :env :API_TOKEN])))
      (is (= "deploy.sh" (get-in out [:tool/args :command]))
          "non-secret values are untouched")
      (is (= marker (get-in out [:items 0 :secret])))
      (is (contains? (get-in out [:items 1]) marker)))))

(deftest ^{:stratum 1} records-survive-redaction-test
  (testing "a record in an event payload redacts without throwing"
    ;; (empty a-record) throws UnsupportedOperationException, so rebuilding
    ;; a map onto (empty x) would crash publish! for any record payload.
    (let [out (sut/redact (->PayloadRecord "hunter2" "ok"))]
      (is (instance? PayloadRecord out) "the record type is preserved")
      (is (= marker (:password out)))
      (is (= "ok" (:note out))))))

(deftest ^{:stratum 1} other-collections-are-walked-test
  (testing "a collection outside the enumerated types is still walked"
    ;; PersistentQueue is coll? but none of map?, vector?, set? or seq?.
    ;; Enumerating concrete types leaks; this pins the fallback.
    (let [q   (into clojure.lang.PersistentQueue/EMPTY
                    ["AKIAIOSFODNN7EXAMPLE" "ok"])
          out (:q (sut/redact {:q q}))]
      (is (instance? clojure.lang.PersistentQueue out)
          "the collection type is preserved")
      (is (= [marker "ok"] (vec out))))))

(deftest ^{:stratum 1} seqs-are-redacted-eagerly-test
  (testing "no lazy seq defers redaction past emission"
    ;; A lazy seq would leave the un-redacted value reachable from its
    ;; closure, so the event would carry the secret §8.1 forbids while
    ;; still looking redacted.
    (let [src (map identity ["AKIAIOSFODNN7EXAMPLE" "plain"])
          out (:items (sut/redact {:items src}))]
      (is (realized? out) "redaction is forced, not deferred")
      (is (= [marker "plain"] (vec out))))))

(deftest ^{:stratum 1} counts-are-not-secrets-test
  (testing "a token count is a number, not a bearer token"
    ;; Miniforge tracks LLM token usage on nearly every event. Replacing
    ;; 42 with a string broke the web dashboard with a ClassCastException
    ;; before this rule existed.
    (let [out (sut/redact {:metrics {:tokens 42 :max-tokens 8000}
                           :input_tokens 17
                           :password "hunter2"})]
      (is (= 42 (get-in out [:metrics :tokens])))
      (is (= 8000 (get-in out [:metrics :max-tokens])))
      (is (= 17 (:input_tokens out)))
      (is (= marker (:password out)) "textual secrets are still redacted"))))

(deftest ^{:stratum 1} references-to-secrets-are-not-secrets-test
  (testing "a qualifier suffix makes the key a reference, not the secret"
    (let [out (sut/redact {:auth/credential-id  "cred-7"
                           :auth/token-endpoint "https://idp.example/token"
                           :session-cookie-name "sid"
                           :auth/token          "ghp_abcdefghijklmnopqrstuvwxyz0123"})]
      (is (= "cred-7" (:auth/credential-id out)))
      (is (= "https://idp.example/token" (:auth/token-endpoint out)))
      (is (= "sid" (:session-cookie-name out)))
      (is (= marker (:auth/token out))
          "an unqualified secret key is still redacted wholesale"))))

(deftest ^{:stratum 1} excluded-keys-are-still-shape-scanned-test
  (testing "excluding a key from the wholesale rule does not skip its value"
    ;; This is what makes the exclusion list safe: only the key rule is
    ;; disabled, so a secret hiding inside an excluded key is still caught.
    (let [out (sut/redact {:token-endpoint "https://x/AKIAIOSFODNN7EXAMPLE"})]
      (is (str/includes? (:token-endpoint out) marker))
      (is (not (str/includes? (:token-endpoint out) "AKIAIOSFODNN7EXAMPLE"))))))
