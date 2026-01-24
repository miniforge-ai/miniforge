(ns ai.miniforge.artifact.datalevin-store
  "Datalevin-based artifact store implementation (JVM only).

   This namespace is separate from artifact.core to avoid pulling
   in JVM-only dependencies (datalevin/nippy) when only the protocol
   or transit store is needed (Babashka compatibility)."
  (:require
   [datalevin.core :as d]
   [ai.miniforge.artifact.core :as core]
   [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Datalevin schema

(def datalevin-schema
  "Datalevin schema for artifact storage."
  {:artifact/id       {:db/unique :db.unique/identity}
   :artifact/type     {}
   :artifact/version  {}
   :artifact/parents  {:db/cardinality :db.cardinality/many}
   :artifact/children {:db/cardinality :db.cardinality/many}})

;------------------------------------------------------------------------------ Layer 1
;; DatalevinStore implementation

(defrecord DatalevinStore [conn logger]
  p/ArtifactStore
  (save [_this artifact]
    (let [id (:artifact/id artifact)]
      (d/transact! conn [artifact])
      (when logger
        (log/debug logger :system :artifact/saved
                   {:data {:artifact-id id
                           :artifact-type (:artifact/type artifact)}}))
      id))

  (load-artifact [_this id]
    (let [db (d/db conn)
          result (d/entity db [:artifact/id id])]
      (when result
        (into {} result))))

  (query [_this criteria]
    (let [db (d/db conn)]
      (if (empty? criteria)
        ;; Return all artifacts if no criteria
        (let [all-ids (d/q '[:find ?e :where [?e :artifact/id]] db)]
          (mapv (fn [[eid]] (into {} (d/entity db eid))) all-ids))
        ;; Simple filter-based query (Phase 1 - good enough)
        (let [all-artifacts (d/q '[:find ?e :where [?e :artifact/id]] db)
              artifacts (mapv (fn [[eid]] (into {} (d/entity db eid))) all-artifacts)]
          (filter (fn [art]
                    (every? (fn [[k v]] (= (get art k) v)) criteria))
                  artifacts)))))

  (link [_this parent-id child-id]
    (try
      ;; Add child to parent's children list
      (d/transact! conn [[:db/add [:artifact/id parent-id] :artifact/children child-id]])
      ;; Add parent to child's parents list
      (d/transact! conn [[:db/add [:artifact/id child-id] :artifact/parents parent-id]])
      (when logger
        (log/debug logger :system :artifact/linked
                   {:data {:parent-id parent-id
                           :child-id child-id}}))
      true
      (catch Exception e
        (when logger
          (log/error logger :system :artifact/link-failed
                     {:message (.getMessage e)
                      :data {:parent-id parent-id
                             :child-id child-id}}))
        false)))

  (close [_this]
    (d/close conn)))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-datalevin-store
  "Create a new Datalevin-based artifact store (JVM only).

   Options:
   - :dir      - Directory for storage (nil for in-memory)
   - :logger   - Optional logger
   - :schema   - Optional custom Datalevin schema (defaults to datalevin-schema)

   Examples:
     (create-datalevin-store)                          ; in-memory
     (create-datalevin-store {:dir \"data/artifacts\"})  ; persistent
     (create-datalevin-store {:logger my-logger})"
  ([] (create-datalevin-store {}))
  ([{:keys [dir logger schema] :or {schema datalevin-schema}}]
   (let [conn (if dir
                (d/get-conn dir schema)
                (d/get-conn nil schema))]
     (when logger
       (log/info logger :system :artifact/store-created
                 {:data {:type (if dir :persistent :in-memory)
                         :dir dir}}))
     (->DatalevinStore conn logger))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create in-memory store
  (def store (create-datalevin-store))

  ;; Create persistent store
  (def store (create-datalevin-store {:dir "data/artifacts"}))

  ;; Save an artifact
  (def art-id (random-uuid))
  (p/save store {:artifact/id art-id
                    :artifact/type :code
                    :artifact/version "1.0.0"
                    :artifact/content {:file "foo.clj" :code "(defn hello [] \"world\")"}
                    :artifact/origin {:intent-id (random-uuid)
                                      :agent-id (random-uuid)
                                      :task-id (random-uuid)}
                    :artifact/parents []
                    :artifact/metadata {:language :clojure}})

  ;; Load an artifact
  (p/load-artifact store art-id)

  ;; Query artifacts
  (p/query store {:artifact/type :code})

  ;; Link artifacts
  (def parent-id (random-uuid))
  (def child-id (random-uuid))
  (p/save store {:artifact/id parent-id :artifact/type :spec :artifact/version "1.0.0" :artifact/content "spec content"})
  (p/save store {:artifact/id child-id :artifact/type :code :artifact/version "1.0.0" :artifact/content "code content"})
  (p/link store parent-id child-id)

  ;; Close store
  (p/close store)

  :end)
