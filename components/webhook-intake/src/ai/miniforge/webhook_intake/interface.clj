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
(ns ai.miniforge.webhook-intake.interface
  "Inbound webhook intake: verify a GitHub delivery's signature and turn it
   into an event-stream event that workflow triggers can match."
  (:require
   [ai.miniforge.webhook-intake.github :as github]
   [ai.miniforge.webhook-intake.signature :as signature]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} valid-signature?
  "`(valid-signature? secret body-bytes x-hub-signature-256)`: true when the
   header is GitHub's HMAC-SHA256 signature of the raw body under the
   webhook secret, compared in constant time. False for a blank secret."
  signature/valid-signature?)

(def ^{:stratum 0} delivery->events
  "`(delivery->events {:event-name :delivery-id :payload})`: event-stream
   events for a GitHub delivery (:pr/merged, :pr/opened, :pr/ci-passed,
   :pr/ci-failed), each carrying :pr/repo and :pr/branch for trigger
   matching. Empty for deliveries miniforge does not act on, including
   pull requests from forks."
  github/delivery->events)
