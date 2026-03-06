(ns ai.miniforge.event-stream.approval
  "Multi-party approval state machine for N8 OCI compliance.

   Implements structured approval requests requiring quorum-based
   signing from authorized signers. Approval states flow:
   pending → approved | rejected | expired | cancelled.

   Layer 0: Approval request creation
   Layer 1: Approval signing and status checking
   Layer 2: Approval manager (atom-backed store)
   Layer 3: Approval event constructors"
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Response predicates for builder responses

(defn succeeded?
  "Check if a builder response (from response/success) succeeded."
  [r]
  (= :success (:status r)))

(defn failed?
  "Check if a builder response (from response/failure) failed."
  [r]
  (false? (:success r)))

;------------------------------------------------------------------------------ Layer 0
;; Approval request creation

(def ^:const default-expiry-hours
  "Default approval request expiry in hours."
  24)

(defn create-approval-request
  "Create a new approval request.

   Arguments:
   - action-id - UUID of the control action requiring approval
   - required-signers - Vector of authorized signer identifiers (strings)
   - quorum - Number of approvals needed
   - opts - Optional map:
     - :expires-in-hours - Hours until expiry (default: 24)
     - :metadata - Additional metadata map

   Returns: Approval request map."
  [action-id required-signers quorum & [opts]]
  (let [hours (get opts :expires-in-hours default-expiry-hours)
        now (java.util.Date.)
        expires-at (java.util.Date. (+ (.getTime now) (* hours 60 60 1000)))]
    (cond-> {:approval/id (random-uuid)
             :approval/action-id action-id
             :approval/status :pending
             :approval/required-signers (vec required-signers)
             :approval/quorum quorum
             :approval/signatures []
             :approval/created-at now
             :approval/expires-at expires-at}
      (:metadata opts) (assoc :approval/metadata (:metadata opts)))))

;------------------------------------------------------------------------------ Layer 1
;; Approval signing

(defn signer-authorized?
  "Check if a signer is in the required-signers list."
  [approval-request signer]
  (some #{signer} (:approval/required-signers approval-request)))

(defn already-signed?
  "Check if a signer has already signed."
  [approval-request signer]
  (some #(= signer (:signer %)) (:approval/signatures approval-request)))

(defn expired?
  "Check if an approval request has expired."
  [approval-request]
  (let [now (java.util.Date.)
        expires-at (:approval/expires-at approval-request)]
    (when expires-at
      (.after now expires-at))))

(defn submit-approval
  "Submit an approval or rejection for a request.

   Arguments:
   - approval-request - Approval request map
   - signer - Signer identifier string
   - decision - :approve or :reject
   - opts - Optional map with :reason string

   Returns:
   - response/success with updated request on success
   - response/failure with anomaly on error"
  [approval-request signer decision & [opts]]
  (cond
    ;; Must be pending
    (not= :pending (:approval/status approval-request))
    (response/failure "Approval request is not pending"
                      {:data {:status (:approval/status approval-request)}})

    ;; Check expiry
    (expired? approval-request)
    (response/failure "Approval request has expired"
                      {:data {:expires-at (:approval/expires-at approval-request)}})

    ;; Must be authorized signer
    (not (signer-authorized? approval-request signer))
    (response/failure "Signer not authorized"
                      {:data {:signer signer
                              :required (:approval/required-signers approval-request)}})

    ;; No duplicate signatures
    (already-signed? approval-request signer)
    (response/failure "Signer has already signed"
                      {:data {:signer signer}})

    ;; Valid decision
    (not (#{:approve :reject} decision))
    (response/failure "Invalid decision" {:data {:decision decision}})

    :else
    (let [signature {:signer signer
                     :decision decision
                     :timestamp (java.util.Date.)
                     :reason (:reason opts)}
          updated (update approval-request :approval/signatures conj signature)
          ;; Check for immediate rejection
          rejected? (= :reject decision)
          ;; Check for quorum
          approve-count (count (filter #(= :approve (:decision %))
                                       (:approval/signatures updated)))
          approved? (>= approve-count (:approval/quorum updated))
          ;; Update status
          new-status (cond rejected? :rejected
                           approved? :approved
                           :else :pending)
          final (assoc updated :approval/status new-status)]
      (response/success final))))

;------------------------------------------------------------------------------ Layer 1
;; Status checking

(defn check-approval-status
  "Check the current status of an approval request, accounting for expiry.

   Arguments:
   - approval-request - Approval request map

   Returns: :pending | :approved | :rejected | :expired | :cancelled"
  [approval-request]
  (let [status (:approval/status approval-request)]
    (if (and (= :pending status) (expired? approval-request))
      :expired
      status)))

(defn cancel-approval
  "Cancel a pending approval request.

   Arguments:
   - approval-request - Approval request map
   - canceller - Identifier of the person cancelling
   - reason - Reason for cancellation

   Returns:
   - response/success with updated request on success
   - response/failure if not pending"
  [approval-request canceller reason]
  (if (not= :pending (:approval/status approval-request))
    (response/failure "Can only cancel pending approvals"
                      {:data {:status (:approval/status approval-request)}})
    (response/success
     (assoc approval-request
            :approval/status :cancelled
            :approval/cancelled-by canceller
            :approval/cancel-reason reason
            :approval/cancelled-at (java.util.Date.)))))

;------------------------------------------------------------------------------ Layer 2
;; Approval manager (atom-backed store)

(defn create-approval-manager
  "Create an atom-backed approval manager.

   Returns: Atom containing {:approvals {uuid approval-request}}"
  []
  (atom {:approvals {}}))

(defn store-approval!
  "Store an approval request in the manager.

   Arguments:
   - manager - Approval manager atom
   - approval - Approval request map

   Returns: The stored approval."
  [manager approval]
  (let [id (:approval/id approval)]
    (swap! manager assoc-in [:approvals id] approval)
    approval))

(defn get-approval
  "Get an approval request by ID.

   Arguments:
   - manager - Approval manager atom
   - approval-id - UUID

   Returns: Approval request or nil."
  [manager approval-id]
  (get-in @manager [:approvals approval-id]))

(defn update-approval!
  "Update an approval request in the manager.

   Arguments:
   - manager - Approval manager atom
   - approval - Updated approval request map

   Returns: The updated approval."
  [manager approval]
  (let [id (:approval/id approval)]
    (swap! manager assoc-in [:approvals id] approval)
    approval))

(defn list-approvals
  "List all approval requests, optionally filtered.

   Arguments:
   - manager - Approval manager atom
   - opts - Optional map with :status keyword to filter by

   Returns: Vector of approval requests."
  [manager & [opts]]
  (let [approvals (vals (:approvals @manager))]
    (if-let [status (:status opts)]
      (vec (filter #(= status (check-approval-status %)) approvals))
      (vec approvals))))

;------------------------------------------------------------------------------ Layer 3
;; Approval event constructors

(defn approval-requested
  "Create an approval/requested event."
  [stream workflow-id approval-id action-id required-signers]
  (-> (core/create-envelope stream :approval/requested workflow-id
                            (str "Approval requested for action " action-id))
      (assoc :approval/id approval-id
             :approval/action-id action-id
             :approval/required-signers required-signers)))

(defn approval-signed
  "Create an approval/signed event."
  [stream workflow-id approval-id signer decision]
  (-> (core/create-envelope stream :approval/signed workflow-id
                            (str "Approval " (name decision) " by " signer))
      (assoc :approval/id approval-id
             :approval/signer signer
             :approval/decision decision)))

(defn approval-completed
  "Create an approval/completed event."
  [stream workflow-id approval-id final-status]
  (-> (core/create-envelope stream :approval/completed workflow-id
                            (str "Approval " (name final-status)))
      (assoc :approval/id approval-id
             :approval/final-status final-status)))

(defn approval-expired
  "Create an approval/expired event."
  [stream workflow-id approval-id]
  (-> (core/create-envelope stream :approval/expired workflow-id
                            "Approval request expired")
      (assoc :approval/id approval-id)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create and sign an approval
  (def req (create-approval-request
            (random-uuid) ["alice" "bob" "carol"] 2))

  ;; Alice approves
  (def r1 (submit-approval req "alice" :approve))
  ;; => success with updated request (still pending)

  ;; Bob approves → quorum met
  (def r2 (submit-approval (:output r1) "bob" :approve))
  ;; => success with status :approved

  ;; Test rejection
  (def req2 (create-approval-request
             (random-uuid) ["alice" "bob"] 2))
  (def r3 (submit-approval req2 "alice" :reject {:reason "Too risky"}))
  ;; => success with status :rejected (immediate)

  ;; Manager usage
  (def mgr (create-approval-manager))
  (store-approval! mgr req)
  (get-approval mgr (:approval/id req))

  :leave-this-here)
