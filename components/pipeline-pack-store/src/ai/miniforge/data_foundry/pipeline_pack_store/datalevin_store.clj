(ns ai.miniforge.data-foundry.pipeline-pack-store.datalevin-store
  "Datalevin implementation of PackStore protocol."
  (:require
   [datalevin.core :as d]
   [ai.miniforge.data-foundry.pipeline-pack-store.protocol :as proto]
   [ai.miniforge.data-foundry.pipeline-pack-store.schema :as store-schema])
  (:import [java.util UUID]))

(defn- pack->datoms
  "Convert a loaded pack to Datalevin transaction datoms."
  [pack]
  (let [manifest (:pack/manifest pack)
        pack-id (:pack/id manifest)]
    (into
     ;; Pack manifest datom
     [{:pack/id          pack-id
       :pack/name        (:pack/name manifest)
       :pack/version     (:pack/version manifest)
       :pack/description (:pack/description manifest "")
       :pack/author      (:pack/author manifest "")
       :pack/trust-level (name (:pack/trust-level manifest :untrusted))
       :pack/authority   (name (:pack/authority manifest :authority/data))}]
     ;; Metric datoms (from registry if present)
     (when-let [registry (:pack/registry pack)]
       (for [family (:registry/families registry)
             metric (:family/metrics family)]
         {:metric/id          (:metric/id metric)
          :metric/name        (:metric/name metric)
          :metric/family-id   (name (:family/id family))
          :metric/source-type (name (:metric/source-type metric))
          :metric/pack-id     pack-id})))))

(defn- entity->map
  "Convert a Datalevin entity to a plain map."
  [entity]
  (when entity
    (into {} entity)))

(defrecord DatalevinPackStore [conn]
  proto/PackStore

  (save-pack [_this pack]
    (let [datoms (pack->datoms pack)]
      (d/transact! conn datoms)
      (get-in pack [:pack/manifest :pack/id])))

  (load-pack [_this pack-id]
    (let [db (d/db conn)
          result (d/entity db [:pack/id pack-id])]
      (entity->map result)))

  (list-packs [_this]
    (let [db (d/db conn)
          ids (d/q '[:find ?id :where [?e :pack/id ?id]] db)]
      (mapv (fn [[id]] (entity->map (d/entity db [:pack/id id]))) ids)))

  (save-snapshot [_this snapshot]
    (let [id (or (:snapshot/id snapshot) (str (UUID/randomUUID)))
          datom (assoc snapshot :snapshot/id id)]
      (d/transact! conn [datom])
      id))

  (latest-snapshots [_this pack-id]
    (let [db (d/db conn)
          results (d/q '[:find ?sid ?mid ?val ?as-of
                         :in $ ?pack-id
                         :where
                         [?e :snapshot/pack-id ?pack-id]
                         [?e :snapshot/id ?sid]
                         [?e :snapshot/metric-id ?mid]
                         [?e :snapshot/value ?val]
                         [?e :snapshot/as-of ?as-of]]
                       db pack-id)]
      ;; Group by metric-id, take latest by as-of
      (->> results
           (group-by second)
           (map (fn [[metric-id rows]]
                  (let [latest (last (sort-by #(nth % 3) rows))]
                    {:snapshot/id        (nth latest 0)
                     :snapshot/metric-id metric-id
                     :snapshot/value     (nth latest 2)
                     :snapshot/as-of     (nth latest 3)})))
           vec)))

  (save-run [_this run]
    (let [id (or (:run/id run) (str (UUID/randomUUID)))
          datom (assoc run :run/id id)]
      (d/transact! conn [datom])
      id))

  (runs-for-pack [_this pack-id]
    (let [db (d/db conn)
          results (d/q '[:find ?e
                         :in $ ?pack-id
                         :where [?e :run/pack-id ?pack-id]]
                       db pack-id)]
      (mapv (fn [[eid]] (entity->map (d/entity db eid))) results)))

  (close [_this]
    (d/close conn)))

(defn create-store
  "Create a Datalevin-backed pack store.
   Opts:
     :dir - directory for persistent storage (nil = in-memory)"
  ([] (create-store {}))
  ([{:keys [dir schema] :or {schema store-schema/datalevin-schema}}]
   (let [conn (d/get-conn dir schema)]
     (->DatalevinPackStore conn))))
