(ns ai.miniforge.event-stream.interface.approval
  "Multi-party approval API for the event stream."
  (:require
   [ai.miniforge.event-stream.approval :as approval]))

;------------------------------------------------------------------------------ Layer 0
;; Approval management

(def approval-succeeded? approval/succeeded?)
(def approval-failed? approval/failed?)
(def create-approval-request approval/create-approval-request)
(def submit-approval approval/submit-approval)
(def check-approval-status approval/check-approval-status)
(def cancel-approval approval/cancel-approval)
(def create-approval-manager approval/create-approval-manager)
(def store-approval! approval/store-approval!)
(def get-approval approval/get-approval)
(def update-approval! approval/update-approval!)
(def list-approvals approval/list-approvals)
