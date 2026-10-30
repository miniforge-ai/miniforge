;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.envelope-signature
  "The runtime's signature over the decision envelope that allows an effect.

   A durable proposal carries its allowing envelope. Commit refuses a
   proposal whose envelope is missing, names another id, does not allow,
   or does not verify against the runtime key, so an effect record written
   outside the runtime cannot claim a decision the runtime never made."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.runtime-signing.interface :as signing]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} allowing-decisions #{:allow :allow-with-obligations})

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
   names; otherwise an `:envelope-unsigned` denial."
  [t]
  (let [envelope (:effect/envelope t)]
    (when-not (and (map? envelope)
                   (= (:envelope/id envelope) (:effect/envelope-id t))
                   (contains? allowing-decisions (:envelope/decision envelope))
                   (signed? envelope))
      {:grant/outcome :envelope-unsigned :effect/id (:effect/id t)})))

(comment
  (refusal {}))
