
(ns test
  (:require [schema :as schema]))

(declare validate-no-rule-id-collisions)
(declare validate-taxonomy-refs)
(declare apply-overrides)

(defn- resolved-pack [inherited-rules overlay-rules overlay-pack base-packs]
  (let [;; Combine: inherited + overlay rules
        combined-rules (into inherited-rules overlay-rules)

        ;; Apply overrides last
        overrides      (:pack/overrides overlay-pack [])
        final-rules    (apply-overrides combined-rules overrides)

        ;; Inherit taxonomy-ref from base if overlay doesn't have one
        base-tax-ref   (some :pack/taxonomy-ref (filterv some? base-packs))
        final-tax-ref  (or (:pack/taxonomy-ref overlay-pack) base-tax-ref)

        resolved-pack  (cond-> (assoc overlay-pack :pack/rules final-rules)
                         final-tax-ref (assoc :pack/taxonomy-ref final-tax-ref))]

    (schema/success :pack resolved-pack {})))

(defn- pack-id [extends]
  (fn [i bp]
    (when (nil? bp)
      (str "Base pack not found: "
           (:pack-id (nth extends i))))))

(defn resolve-overlay
  "Resolve an overlay pack by merging inherited rules from base packs.

   Resolution order (per N4 §2.5):
   1. Inherited rules merged from all :pack/extends entries in declaration order
   2. Overlay :pack/rules appended (IDs MUST NOT collide with inherited)
   3. :pack/overrides apply last (only :rule/severity and :rule/enabled?)
   4. Taxonomy ref inherited from base pack(s); conflicting refs invalid

   Arguments:
   - overlay-pack - The overlay pack with :pack/extends
   - pack-store   - Map of pack-id -> PackManifest for base pack resolution

   Returns:
   - {:success? true :pack <resolved PackManifest>}
   - {:success? false :errors [...]}"
  [overlay-pack pack-store]
  (let [extends (:pack/extends overlay-pack [])

        ;; Load base packs in declaration order
        base-packs (mapv (fn [{:keys [pack-id]}]
                           (get pack-store pack-id))
                         extends)
        missing    (keep-indexed (pack-id extends) base-packs)]

    (cond 
      (seq missing) (schema/failure-with-errors :pack (vec missing))
      )
    
    (if (seq missing)
      (schema/failure-with-errors :pack (vec missing))

      (let [;; Taxonomy ref validation
            tax-errors (validate-taxonomy-refs (filterv some? base-packs) overlay-pack)]

        (if (seq tax-errors)
          (schema/failure-with-errors :pack tax-errors)

          (let [;; Merge inherited rules (declaration order)
                inherited-rules (vec (mapcat :pack/rules (filterv some? base-packs)))
                inherited-ids   (set (map :rule/id inherited-rules))

                ;; Check for collisions with overlay's own rules
                overlay-rules   (:pack/rules overlay-pack [])
                collision-errors (validate-no-rule-id-collisions inherited-ids overlay-rules)]

            (if (seq collision-errors)
              (schema/failure-with-errors :pack collision-errors)
              (resolved-pack inherited-rules overlay-rules overlay-pack base-packs))))))))
