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

(ns ai.miniforge.event-stream.operator-requests
  "Writer side of the operator control channel (Phase D decision 1).

   Operator surfaces — the TUI, the web dashboard, the CLI, and the
   Rust control-plane client — ask for a workflow intervention by
   dropping a `:supervisory/intervention-requested` event into
   `{events-dir}/operator/`. The operator-event consumer reads that
   directory and routes each request through the intervention
   lifecycle. One channel, one encoding, both writers.

   This namespace is transport only. It stamps the canonical envelope,
   serializes with [[sinks/event->transit-json]] — the exact writer the
   file sink uses, so a request file is byte-compatible with every
   other event on disk — and lands it atomically under the legacy
   `{timestamp}-{uuid}.json` filename grammar the Rust client also
   writes.

   The intervention *vocabulary* (which verbs exist, which target types
   they take, whether they need approval) belongs to the operator
   component and is enforced server-side by the consumer: nothing a
   client writes here is trusted. The validation below is only the
   wire-shape floor — enough that a malformed call fails at the call
   site instead of becoming an anomaly event minutes later."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.event-stream.sinks :as sinks]
   [clojure.java.io :as io])
  (:import
   [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0
;; Request shaping

(def ^:private required-request-keys
  "Fields a client must supply. Everything else on the wire envelope is
   stamped here or re-derived server-side by the consumer."
  [:intervention/type
   :intervention/target-type
   :intervention/target-id
   :intervention/requested-by
   :intervention/request-source])

(defn- missing-request-keys
  [request]
  (remove #(some? (get request %)) required-request-keys))

(defn- request-workflow-id
  "The `:workflow/id` the envelope routes on: present only for
   workflow-targeted interventions whose target id is a UUID. Mirrors
   the consumer's `intervention-workflow-id` so the request and its
   lifecycle events land in the same run's audit trail."
  [request]
  (when (= :workflow (:intervention/target-type request))
    (let [target-id (:intervention/target-id request)]
      (if (uuid? target-id)
        target-id
        (some-> target-id str parse-uuid)))))

(defn- proposed-intervention
  "The InterventionRequest body of the wire event. `:proposed` state and
   the timestamps are the client's *claim*; the consumer rebuilds the
   record with server authority and ignores them. They are written
   anyway because the Rust client writes them and the two producers
   must emit the same shape."
  [request]
  (let [now (java.util.Date.)]
    (cond-> {:intervention/id (or (:intervention/id request) (random-uuid))
             :intervention/type (:intervention/type request)
             :intervention/target-type (:intervention/target-type request)
             :intervention/target-id (str (:intervention/target-id request))
             :intervention/requested-by (:intervention/requested-by request)
             :intervention/request-source (:intervention/request-source request)
             :intervention/state :proposed
             :intervention/requested-at now
             :intervention/updated-at now}
      (:intervention/justification request)
      (assoc :intervention/justification (:intervention/justification request))

      (:intervention/details request)
      (assoc :intervention/details (:intervention/details request)))))

;------------------------------------------------------------------------------ Layer 1
;; Event construction

(defn intervention-request-event
  "Build the `:supervisory/intervention-requested` event for `request`,
   or an `:invalid-input` anomaly when a required field is missing.

   Required keys: `:intervention/type`, `:intervention/target-type`,
   `:intervention/target-id`, `:intervention/requested-by`,
   `:intervention/request-source`. Optional:
   `:intervention/id` (defaults to a fresh UUID),
   `:intervention/justification`, `:intervention/details`.

   Pure apart from the id/timestamp stamps: no IO."
  [request]
  (if-let [missing (seq (missing-request-keys request))]
    (anomaly/anomaly :invalid-input
                     (messages/t :operator-request/missing-fields
                                 {:fields (pr-str (vec missing))})
                     {:intervention/type (:intervention/type request)
                      :missing-fields (vec missing)})
    (let [intervention (proposed-intervention request)]
      (core/intervention-requested (core/create-event-stream {:sinks []})
                                   (request-workflow-id request)
                                   intervention))))

;------------------------------------------------------------------------------ Layer 2
;; Atomic publication into the operator directory

(defn- write-event-file!
  "Serialize `event` and land it in `{base-dir}/operator/` atomically:
   write a sibling `.tmp` then `Files/move` with `ATOMIC_MOVE` — the
   repo-wide atomic-update idiom (see `archive.clj`). A reader can never
   observe a half-written request, and the `.tmp` suffix keeps the
   partial file out of the consumer's `.json` listing."
  ^java.io.File [base-dir event]
  (let [target (sinks/operator-event-file-path base-dir event)
        tmp (io/file (.getParentFile target) (str (.getName target) ".tmp"))]
    (spit tmp (sinks/event->transit-json event) :encoding "UTF-8")
    (Files/move (.toPath tmp)
                (.toPath target)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    target))

(defn request-intervention!
  "Write an intervention request into `{events-dir}/operator/` for the
   operator-event consumer to pick up.

   `request` takes the keys of [[intervention-request-event]] plus an
   optional `:events-dir` (default: the same `~/.miniforge/events` root
   the file sink and the consumer resolve, so writer and reader cannot
   drift).

   Returns the event map that was written, with `:source/file` naming
   the file it landed in — or an `:invalid-input` anomaly when the
   request is malformed. IO failures propagate: a control gesture that
   did not reach disk must never report success."
  [request]
  (let [event (intervention-request-event (dissoc request :events-dir))]
    (if (anomaly/anomaly? event)
      event
      (let [base-dir (if-let [events-dir (:events-dir request)]
                       (io/file events-dir)
                       (sinks/default-events-dir))]
        (assoc event :source/file (str (write-event-file! base-dir event)))))))
