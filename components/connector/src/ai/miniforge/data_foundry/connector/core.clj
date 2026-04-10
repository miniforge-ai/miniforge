(ns ai.miniforge.data-foundry.connector.core
  (:require [ai.miniforge.data-foundry.connector.messages :as msg])
  (:import [java.util UUID]
           [java.time Instant]))

(def connector-types
  "N2 §1 connector types"
  #{:source :sink :bidirectional})

(def connector-capabilities
  "N2 §1.4 connector capabilities"
  #{:cap/discovery :cap/incremental :cap/batch :cap/upsert
    :cap/transactions :cap/rate-limiting :cap/pagination})

(defn validate-connector
  "Validate a connector registration against N2 §7.1."
  [{:connector/keys [id name type version capabilities auth-methods
                     retry-policy maintainer]}]
  (let [errors
        (cond-> []
          (nil? id)
          (conj (msg/t :connector/id-required))

          (not (string? name))
          (conj (msg/t :connector/name-must-be-string))

          (not (contains? connector-types type))
          (conj (msg/t :connector/type-invalid {:allowed connector-types}))

          (not (string? version))
          (conj (msg/t :connector/version-must-be-string))

          (not (set? capabilities))
          (conj (msg/t :connector/capabilities-must-be-set))

          (and (set? capabilities) (empty? capabilities))
          (conj (msg/t :connector/capabilities-empty))

          (not (set? auth-methods))
          (conj (msg/t :connector/auth-methods-must-be-set))

          (and (set? auth-methods) (empty? auth-methods))
          (conj (msg/t :connector/auth-methods-empty))

          (nil? retry-policy)
          (conj (msg/t :connector/retry-policy-required))

          (and retry-policy (nil? (:retry/strategy retry-policy)))
          (conj (msg/t :connector/retry-strategy-required))

          (and retry-policy (nil? (:retry/max-attempts retry-policy)))
          (conj (msg/t :connector/retry-max-attempts-required))

          (not (string? maintainer))
          (conj (msg/t :connector/maintainer-must-be-string)))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-connector
  "Create a connector registration with defaults."
  [opts]
  (let [connector (cond-> opts
                    (nil? (:connector/id opts))
                    (assoc :connector/id (UUID/randomUUID)))
        validation (validate-connector connector)]
    (if (:success? validation)
      {:success? true :connector connector}
      {:success? false :errors (:errors validation)})))
