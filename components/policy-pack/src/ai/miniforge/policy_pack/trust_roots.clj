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
(ns ai.miniforge.policy-pack.trust-roots
  "The trusted-publisher store for pack signature verification (N4 §8.2.1),
   as a value. `policy-pack.trust-roots-config` loads one from configuration.

   A pack names a key identifier in :pack/signed-by; the key material is
   resolved here, never taken from the pack itself.

   Layer 0: Schema and key decoding
   Layer 1: Entry validation and lookup
   Layer 2: Store construction"
  (:require
   [ai.miniforge.messages.interface :as messages]
   [ai.miniforge.policy-pack.crypto :as crypto]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/policy-pack/messages/system.edn"
                              :policy-pack/system))

(def ^{:stratum 0} TrustRoot
  "One trusted publisher key. No algorithm field: N4 §8.1 fixes Ed25519 for
   the pack format, so a per-key algorithm would contradict it."
  [:map
   [:trust-root/key-id
    {:description "Identifier a pack names in :pack/signed-by."}
    [:string {:min 1}]]
   [:trust-root/public-key
    {:description "Base64 of the raw 32-byte Ed25519 public key."}
    [:string {:min 1}]]])

(defn ^{:stratum 0} decode-public-key
  "Base64 raw Ed25519 public key to bytes; nil when the value is not a
   string, not base64, or not 32 bytes. `resolve-key` reaches this with
   whatever a caller-supplied store holds, so the type check is here rather
   than left to the schema."
  [base64-key]
  (when (string? base64-key)
    (try
      (let [decoded (.decode (java.util.Base64/getDecoder) ^String base64-key)]
        (when (= crypto/ed25519-public-key-bytes (alength decoded))
          decoded))
      (catch IllegalArgumentException _
        nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate-entry
  "Check one entry's shape and key material. {:valid? bool :errors map-or-nil}."
  [entry]
  (let [{:keys [valid? errors]} (schema-validation/validate TrustRoot entry)]
    (cond
      (not valid?)
      {:valid? false :errors errors}

      (nil? (decode-public-key (:trust-root/public-key entry)))
      {:valid? false
       :errors {:trust-root/public-key
                [(t :trust-roots/invalid-public-key
                    {:bytes crypto/ed25519-public-key-bytes})]}}

      :else
      {:valid? true :errors nil})))

(defn ^{:stratum 1} resolve-key
  "Resolve a :pack/signed-by identifier to its trusted public key bytes.

   Nil — the fail-closed answer — covers a pack with no identifier, an
   unknown publisher, and the empty default store alike."
  [store key-id]
  (when (and (string? key-id) (seq key-id))
    (some-> (get store key-id)
            :trust-root/public-key
            decode-public-key)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ->store
  "Build a key-identifier→entry store from publisher collections, later
   collections winning a shared identifier.

   Throws on a malformed entry rather than dropping it: a trust root that
   silently disappears reads at the gate as an untrusted publisher, sending
   the operator hunting through packs for a fault in their config."
  [& publisher-colls]
  (reduce (fn [store entry]
            (let [{:keys [valid? errors]} (validate-entry entry)
                  key-id (:trust-root/key-id entry)]
              (when-not valid?
                (throw (ex-info (t :trust-roots/invalid-entry {:key-id key-id})
                                {:policy-pack/trust-root-errors errors
                                 :policy-pack/key-id key-id})))
              (assoc store key-id entry)))
          {}
          (apply concat publisher-colls)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def store (->store [{:trust-root/key-id "acme-publisher-2026"
                        :trust-root/public-key "5Q1r...=="}]))

  (resolve-key store "acme-publisher-2026")
  (resolve-key store "unknown-publisher")
  ;; => nil

  :leave-this-here)
