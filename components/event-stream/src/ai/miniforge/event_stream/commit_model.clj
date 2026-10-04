;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-model
  "Commit-time sequence state; callers supply the authoritative scope key."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/event-stream/messages/commit-system.edn"
                              :event-commit/messages))

(defn ^{:stratum 0} empty-state []
  {:next-sequences {}
   :committed {}})

(defn ^{:stratum 0} recorded [state event-id]
  (get-in state [:committed event-id]))

(defn- ^{:stratum 0} publication-content [event]
  (dissoc event :event/sequence-number :event/timestamp))

(defn ^{:stratum 0} accept
  "Advance only after storage acknowledges this exact candidate. No I/O."
  [state scope event]
  (let [next-sequence (inc' (:event/sequence-number event))
        record {:scope scope
                :event event}]
    (-> state
        (assoc-in [:next-sequences scope] next-sequence)
        (assoc-in [:committed (:event/id event)] record))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure [type code event]
  (anomaly/anomaly type (t code) {:event/id (:event/id event)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} candidate
  "Prepare without reserving. Retried content retains the original timestamp."
  [state scope draft]
  (let [record (recorded state (:event/id draft))
        same-scope? (= scope (:scope record))
        same-content? (= (publication-content draft) (publication-content (:event record)))
        sequence-number (get-in state [:next-sequences scope] 0)]
    (cond
      (and record same-scope? same-content?) (:event record)
      record (failure :conflict :commit/identity-conflict draft)
      (> sequence-number Long/MAX_VALUE) (failure :exhausted :commit/sequence-exhausted draft)
      :else (assoc draft :event/sequence-number sequence-number))))

(comment
  (empty-state))
