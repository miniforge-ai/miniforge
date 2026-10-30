;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.envelope-signature
  "The runtime's signatures over the decision envelope that allows an
   effect and over the record that binds it.

   The envelope is signed when it is minted. When the runtime proposes the
   effect, it also signs the binding: effect id, class, grant id, envelope
   and proposal together. Commit refuses a record whose envelope is
   missing, names another id, does not allow, or does not verify, and one
   whose binding does not verify, so a signed envelope copied into another
   record, or a proposal changed after it was recorded, does not commit."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.runtime-signing.interface :as signing]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} allowing-decisions #{:allow :allow-with-obligations})

(def ^{:stratum 0} binding-keys
  "The record fields the runtime signs when it proposes an effect."
  [:effect/id :effect/class :effect/grant-id :effect/envelope-id
   :effect/envelope :effect/proposal])

(defn- ^{:stratum 0} unsigned-fields [envelope]
  (dissoc envelope :envelope/signature))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sign
  "`envelope` carrying the runtime signature. When the runtime key cannot
   load, the envelope is returned unsigned and commit refuses it."
  [envelope]
  (let [key-bytes (signing/load-or-create-key!)]
    (if (or (anomaly/anomaly? key-bytes) (not (map? envelope)) (anomaly/anomaly? envelope))
      envelope
      (assoc envelope :envelope/signature
             (signing/sign key-bytes (unsigned-fields envelope))))))

(defn ^{:stratum 1} bind
  "`t` carrying the runtime signature over its binding fields, or `t`
   unchanged when the key cannot load (commit then refuses it). A binding
   the signer cannot encode losslessly (a char, a path, a lone surrogate,
   nesting past its depth bound) is an `:invalid-input` anomaly."
  [t]
  (let [key-bytes (signing/load-or-create-key!)]
    (if (anomaly/anomaly? key-bytes)
      t
      (try
        (assoc t :effect/signature (signing/sign key-bytes (select-keys t binding-keys)))
        (catch Exception e
          (anomaly/anomaly :invalid-input (msg/t :record/unencodable)
                           {:effect/id (:effect/id t) :error (ex-message e)}))))))

(defn ^{:stratum 1} binding-signed?
  "True only when `t` carries this runtime's signature over its binding."
  [t]
  (let [key-bytes (signing/load-or-create-key!)]
    (and (not (anomaly/anomaly? key-bytes))
         (signing/signed? key-bytes (select-keys t binding-keys) (:effect/signature t)))))

(defn ^{:stratum 1} signed?
  "True only when `envelope` carries this runtime's signature."
  [envelope]
  (let [key-bytes (signing/load-or-create-key!)]
    (and (map? envelope)
         (not (anomaly/anomaly? key-bytes))
         (signing/signed? key-bytes (unsigned-fields envelope)
                          (:envelope/signature envelope)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} refusal
  "Nil when `t` carries a runtime-signed, allowing envelope with the id it
   names and a runtime signature over its binding; otherwise an
   `:envelope-unsigned` denial."
  [t]
  (let [envelope (:effect/envelope t)]
    (when-not (and (map? envelope)
                   (= (:envelope/id envelope) (:effect/envelope-id t))
                   (contains? allowing-decisions (:envelope/decision envelope))
                   (signed? envelope)
                   (binding-signed? t))
      {:grant/outcome :envelope-unsigned :effect/id (:effect/id t)})))

(comment
  (refusal {}))
