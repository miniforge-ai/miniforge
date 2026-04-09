(ns ai.miniforge.data-foundry.pipeline.stage
  (:require [ai.miniforge.data-foundry.pipeline.messages :as msg])
  (:import [java.util UUID]))

(def stage-families
  "N3 §2.2 stage families"
  #{:ingest :extract :normalize :transform :aggregate :validate :enrich :publish})

(def ^:private connector-required-families
  "Stage families that REQUIRE a :stage/connector-ref"
  #{:ingest :publish})

(def ^:private connector-forbidden-families
  "Stage families that MUST NOT have a :stage/connector-ref"
  #{:extract :normalize :transform :aggregate :validate :enrich})

(defn validate-stage
  "Validate a stage definition against N3 §2.2."
  [{:stage/keys [id name family connector-ref input-datasets output-datasets dependencies]}]
  (let [errors
        (cond-> []
          (nil? id)
          (conj (msg/t :stage/id-required))

          (not (string? name))
          (conj (msg/t :stage/name-must-be-string))

          (not (contains? stage-families family))
          (conj (msg/t :stage/family-invalid {:allowed stage-families}))

          ;; Ingest/publish require connector
          (and (contains? connector-required-families family) (nil? connector-ref))
          (conj (msg/t :stage/connector-ref-required {:family family}))

          ;; Other stages must not have connector
          (and (contains? connector-forbidden-families family) (some? connector-ref))
          (conj (msg/t :stage/connector-ref-forbidden {:family family}))

          ;; Ingest must not have input datasets
          (and (= family :ingest) (seq input-datasets))
          (conj (msg/t :stage/ingest-no-input))

          ;; Publish must not have output datasets
          (and (= family :publish) (seq output-datasets))
          (conj (msg/t :stage/publish-no-output))

          ;; Non-ingest must have input datasets
          (and (not= family :ingest) (empty? input-datasets))
          (conj (msg/t :stage/must-have-input {:family family}))

          ;; Non-publish must have output datasets
          (and (not= family :publish) (empty? output-datasets))
          (conj (msg/t :stage/must-have-output {:family family}))

          (not (vector? dependencies))
          (conj (msg/t :stage/deps-must-be-vector)))]
    (if (empty? errors)
      {:success? true}
      {:success? false :errors errors})))

(defn create-stage
  [opts]
  (let [stage (cond-> opts
                (nil? (:stage/id opts))
                (assoc :stage/id (UUID/randomUUID))

                (nil? (:stage/dependencies opts))
                (assoc :stage/dependencies [])

                (nil? (:stage/input-datasets opts))
                (assoc :stage/input-datasets [])

                (nil? (:stage/output-datasets opts))
                (assoc :stage/output-datasets []))
        validation (validate-stage stage)]
    (if (:success? validation)
      {:success? true :stage stage}
      {:success? false :errors (:errors validation)})))
