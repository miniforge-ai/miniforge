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

(ns ai.miniforge.artifact.datalevin-store
  "Datalevin-backed artifact store. Datalevin is reached through the
   `ai.miniforge.datalevin` bridge — the babashka pod under bb, the JVM lib
   under Clojure — so this store runs under both runtimes and carries no
   JVM-only dep of its own."
  (:require
   [ai.miniforge.datalevin.interface :as dl]
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
      (dl/transact! conn [artifact])
      (when logger
        (log/debug logger :system :artifact/saved
                   {:data {:artifact-id id
                           :artifact-type (:artifact/type artifact)}}))
      id))

  (load-artifact [_this id]
    (dl/entity-map (dl/db conn) [:artifact/id id]))

  (query [_this criteria]
    (let [db (dl/db conn)
          ids (dl/q '[:find ?e :where [?e :artifact/id]] db)
          artifacts (mapv (fn [[eid]] (dl/entity-map db eid)) ids)]
      (if (empty? criteria)
        artifacts
        ;; Simple filter-based query (Phase 1 - good enough)
        (filter (fn [art]
                  (every? (fn [[k v]] (= (get art k) v)) criteria))
                artifacts))))

  (link [_this parent-id child-id]
    (try
      ;; Add child to parent's children list
      (dl/transact! conn [[:db/add [:artifact/id parent-id] :artifact/children child-id]])
      ;; Add parent to child's parents list
      (dl/transact! conn [[:db/add [:artifact/id child-id] :artifact/parents parent-id]])
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
    (dl/close conn)))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-datalevin-store
  "Create a new Datalevin-based artifact store.

   Options:
   - :dir      - Directory for storage (omit for a transient store)
   - :logger   - Optional logger
   - :schema   - Optional custom Datalevin schema (defaults to datalevin-schema)

   Examples:
     (create-datalevin-store)                          ; transient
     (create-datalevin-store {:dir \"data/artifacts\"})  ; persistent
     (create-datalevin-store {:logger my-logger})"
  ([] (create-datalevin-store {}))
  ([{:keys [dir logger schema] :or {schema datalevin-schema}}]
   (let [store-dir (or dir (dl/transient-dir))
         conn (dl/get-conn store-dir schema)]
     (when logger
       ;; Log the effective dir, not the requested one — a transient store is
       ;; backed by a real temp directory (store-dir), so logging `dir` (nil)
       ;; would hide where the data actually went.
       (log/info logger :system :artifact/store-created
                 {:data {:type (if dir :persistent :transient)
                         :dir store-dir}}))
     (->DatalevinStore conn logger))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create transient store
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
