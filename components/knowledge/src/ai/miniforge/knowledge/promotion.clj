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

(ns ai.miniforge.knowledge.promotion
  "Pack promotion with trust level elevation and justification tracking.
   Implements N6 §2.1 pack promotion evidence requirements.")

;------------------------------------------------------------------------------ Layer 0
;; Promotion Justification Examples

(def justification-templates
  "Standard justification templates for pack promotions."
  {:safety-scan-passed
   "passed knowledge-safety scans with no violations"

   :manual-review-approved
   "manual review approved by trusted administrator"

   :signature-verified
   "verified signature from trusted key"

   :policy-compliance
   "meets all policy compliance requirements"

   :automated-validation
   "passed automated validation and security checks"})

(defn format-justification
  "Format a promotion justification with optional details.

   Arguments:
   - template-key - Keyword referencing justification-templates
   - details      - (optional) Additional context string

   Returns formatted justification string.

   Example:
     (format-justification :safety-scan-passed)
     => \"passed knowledge-safety scans with no violations\"

     (format-justification :safety-scan-passed \"3 scans completed\")
     => \"passed knowledge-safety scans with no violations (3 scans completed)\""
  [template-key & [details]]
  (let [base (get justification-templates template-key
                  (str "promoted via " (name template-key)))]
    (if details
      (str base " (" details ")")
      base)))

;------------------------------------------------------------------------------ Layer 1
;; Pack Promotion Record

(defn create-promotion-record
  "Create a pack promotion record for evidence bundle.

   Arguments:
   - pack-id              - String pack identifier
   - from-trust           - Source trust level (:untrusted, :tainted, :trusted)
   - to-trust             - Target trust level (:untrusted, :tainted, :trusted)
   - justification        - String explaining why promotion occurred (REQUIRED)

   Options:
   - :pack-type           - Pack type keyword (default :knowledge)
   - :promoted-by         - String identifier of promoter (default \"system\")
   - :promotion-policy    - Policy pack name that validated promotion (default \"knowledge-safety\")
   - :pack-hash           - Content hash for integrity verification
   - :pack-signature      - Cryptographic signature

   Returns promotion record map ready for evidence bundle.

   Example:
     (create-promotion-record
       \"my-pack-001\"
       :untrusted
       :trusted
       (format-justification :safety-scan-passed)
       :promoted-by \"admin@example.com\"
       :pack-hash \"sha256:abc123...\")"
  [pack-id from-trust to-trust justification & {:keys [pack-type
                                                        promoted-by
                                                        promotion-policy
                                                        pack-hash
                                                        pack-signature]}]
  {:pre [(string? pack-id)
         (keyword? from-trust)
         (keyword? to-trust)
         (string? justification)
         (seq justification)]}
  {:pack/id pack-id
   :pack/type (or pack-type :knowledge)
   :from-trust from-trust
   :to-trust to-trust
   :promoted-by (or promoted-by "system")
   :promoted-at (java.time.Instant/now)
   :promotion-policy (or promotion-policy "knowledge-safety")
   :promotion-justification justification
   :pack-hash (or pack-hash "")
   :pack-signature (or pack-signature "")})

;------------------------------------------------------------------------------ Layer 2
;; Trust Level Validation

(def valid-trust-levels
  "Valid trust levels per N6 spec."
  #{:untrusted :tainted :trusted})

(def valid-promotions
  "Valid trust level promotion paths.
   Prevents invalid promotions like :tainted -> :trusted without intermediate step."
  #{[:untrusted :trusted]   ; Most common: untrusted pack passes validation
    [:untrusted :tainted]    ; Pack has issues, mark as tainted
    [:tainted :untrusted]    ; Clean up tainted content
    [:trusted :untrusted]})  ; Demote if compromised

(defn valid-promotion?
  "Check if a trust level promotion is valid.

   Arguments:
   - from-trust - Source trust level
   - to-trust   - Target trust level

   Returns true if promotion is valid per N6 rules."
  [from-trust to-trust]
  (and (contains? valid-trust-levels from-trust)
       (contains? valid-trust-levels to-trust)
       (contains? valid-promotions [from-trust to-trust])))

(defn validate-promotion
  "Validate a promotion record before recording.

   Returns {:valid? bool :errors [string...]}"
  [promotion-record]
  (let [errors (cond-> []
                 (not (valid-promotion? (:from-trust promotion-record)
                                        (:to-trust promotion-record)))
                 (conj (str "Invalid promotion path: "
                            (:from-trust promotion-record) " -> "
                            (:to-trust promotion-record)))

                 (not (seq (:promotion-justification promotion-record)))
                 (conj "promotion-justification is required and cannot be empty")

                 (not (string? (:pack/id promotion-record)))
                 (conj "pack/id must be a string"))]
    {:valid? (empty? errors)
     :errors errors}))

;------------------------------------------------------------------------------ Layer 3
;; Promotion Execution

(defn promote-pack
  "Promote a pack to a new trust level with justification.

   This creates a promotion record and optionally adds it to workflow state
   for inclusion in the evidence bundle.

   Arguments:
   - pack-id       - Pack identifier
   - from-trust    - Current trust level
   - to-trust      - Target trust level
   - justification - Reason for promotion
   - opts          - Optional arguments (see create-promotion-record)

   Returns {:promotion-record map :valid? bool :errors [...]}

   Example:
     (promote-pack
       \"security-rules-v1\"
       :untrusted
       :trusted
       (format-justification :safety-scan-passed \"all 12 rules validated\")
       :promoted-by \"security-team@example.com\"
       :pack-hash \"sha256:def456...\")"
  [pack-id from-trust to-trust justification & opts]
  (let [promotion-record (apply create-promotion-record
                                pack-id from-trust to-trust justification
                                opts)
        validation (validate-promotion promotion-record)]
    (merge validation
           {:promotion-record promotion-record})))

(defn record-promotion-in-workflow
  "Record a pack promotion in workflow state for evidence collection.

   This adds the promotion record to the workflow state so it will be
   included in the evidence bundle at workflow completion.

   Arguments:
   - workflow-state   - Current workflow state map
   - promotion-record - Promotion record from promote-pack

   Returns updated workflow state."
  [workflow-state promotion-record]
  (update workflow-state :workflow/pack-promotions
          (fnil conj [])
          promotion-record))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a promotion with standard justification
  (def promo
    (promote-pack "my-pack-v1"
                  :untrusted
                  :trusted
                  (format-justification :safety-scan-passed)))

  (:promotion-record promo)
  ;; => {:pack/id "my-pack-v1"
  ;;     :pack/type :knowledge
  ;;     :from-trust :untrusted
  ;;     :to-trust :trusted
  ;;     :promoted-by "system"
  ;;     :promoted-at #inst "2026-01-25T..."
  ;;     :promotion-policy "knowledge-safety"
  ;;     :promotion-justification "passed knowledge-safety scans with no violations"
  ;;     :pack-hash ""
  ;;     :pack-signature ""}

  ;; Custom justification with details
  (promote-pack "security-rules"
                :untrusted
                :trusted
                (format-justification :manual-review-approved
                                      "reviewed by 3 security engineers")
                :promoted-by "security@example.com"
                :pack-hash "sha256:abc123...")

  ;; Validate promotion path
  (valid-promotion? :untrusted :trusted)   ;; => true
  (valid-promotion? :tainted :trusted)     ;; => false

  ;; Add to workflow state
  (def workflow-state {:workflow/status :in-progress})
  (def updated-state
    (record-promotion-in-workflow workflow-state
                                  (:promotion-record promo)))
  (:workflow/pack-promotions updated-state)

  :leave-this-here)
