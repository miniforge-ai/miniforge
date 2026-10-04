;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.store-codec :as codec]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.execution-grant.store-path :as path]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-09-27T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-09-27T00:01:00Z"))

(def ^{:stratum 0} expired (Instant/parse "2026-09-27T00:02:00Z"))

(def ^{:stratum 0} scope {:effect/id #uuid "ea386c23-b4f9-4812-bbe8-d42d0dbfcb3b"})

(def ^{:stratum 0} timeout-ms 5000)

(defn- ^{:stratum 0} tmp-dir []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "grant-store" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} record-file
  [dir g suffix]
  (io/file dir "grants" (str (:grant/id g) suffix)))

(defn- ^{:stratum 0} register-after!
  [start dir g]
  @start
  (grant/register! dir g))

(defn- ^{:stratum 0} sync-failure!
  [& _]
  (anomaly/anomaly :fault (msg/t :store/write-failed) {}))

(defn- ^{:stratum 0} sql-date
  [^Instant instant]
  (java.sql.Date. (.toEpochMilli instant)))

(defn- ^{:stratum 0} corrupt-utf8!
  [^File file]
  (let [text (slurp file)
        bytes (.getBytes ^String text StandardCharsets/UTF_8)
        index (.indexOf ^String text "workflow:test")]
    (aset-byte bytes index (unchecked-byte 255))
    (Files/write (.toPath file) bytes (into-array OpenOption []))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} usage {:effect/scope scope :usage/count 1})

(defn- ^{:stratum 1} issued
  []
  (grant/issue {:principal "workflow:test"
                :effect-class :effect/pr-create
                :scope scope
                :constraints {:constraint/max-count 1}
                :delegable? false
                :expires-at later} now))

(defn- ^{:stratum 1} corrupt!
  [dir g suffix text]
  (let [file (record-file dir g suffix)]
    (io/make-parents file)
    (spit file text)))

(defn- ^{:stratum 1} revoke-after!
  [start dir id reason]
  @start
  (grant/revoke-stored! dir id reason now))

(deftest ^{:stratum 1} missing-revocation-target-does-not-create-authority-test
  (let [dir (tmp-dir)
        id (random-uuid)]
    (is (= :not-found (:anomaly/type (grant/revoke-stored! dir id :revocation/operator now))))
    (is (empty? (seq (.listFiles (File. dir)))))
    (is (nil? (grant/current dir id)))))

(deftest ^{:stratum 1} fifo-records-are-refused-before-opening-test
  (when-not (.startsWith (System/getProperty "os.name") "Windows")
    (let [file (io/file (tmp-dir) "record.edn")]
      (is (zero? (.waitFor (.start (ProcessBuilder. ["mkfifo" (str file)])))))
      (is (false? (path/safe? file))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} fresh-directory-and-reload-test
  (let [dir (str (io/file (tmp-dir) "new-grants"))
        g (issued)
        id (:grant/id g)]
    (is (nil? (grant/current dir id)))
    (is (= g (grant/register! dir g)))
    (is (= g (grant/current (File. dir) id)))
    (is (grant/authorized? (grant/authorize (grant/current dir id) usage now)))
    (is (not (grant/authorized? (grant/authorize (grant/current dir id) usage expired))))))

(deftest ^{:stratum 2} duplicate-registration-cannot-widen-or-revive-test
  (let [dir (tmp-dir)
        g (issued)
        id (:grant/id g)
        wider (assoc g :grant/scope {})]
    (grant/register! dir g)
    (is (= :conflict (:anomaly/type (grant/register! dir wider))))
    (is (= g (grant/current dir id)))
    (let [revoked (grant/revoke-stored! dir id :revocation/operator now)]
      (is (= :conflict (:anomaly/type (grant/register! dir g))))
      (is (= revoked (grant/current dir id)))
      (is (not (grant/authorized? (grant/authorize (grant/current dir id) usage now)))))))

(deftest ^{:stratum 2} revocation-is-durable-and-first-wins-test
  (let [dir (tmp-dir)
        g (issued)
        id (:grant/id g)]
    (grant/register! dir g)
    (let [original-bytes (slurp (record-file dir g ".grant.edn"))
          revoked (grant/revoke-stored! dir id :revocation/operator now)
          retry (grant/revoke-stored! dir id :revocation/superseded later)]
      (is (= original-bytes (slurp (record-file dir g ".grant.edn"))))
      (is (= revoked retry (grant/current dir id)))
      (is (= now (:grant/revoked-at retry)))
      (is (= :revocation/operator (:grant/revocation-reason retry)))
      (is (= (:grant/scope g) (:grant/scope retry))))))

(deftest ^{:stratum 2} corrupt-authority-never-reads-as-live-test
  (doseq [suffix [".grant.edn" ".revocation.edn"]
          text ["{" "{}" "nil" "#object[malformed]"]]
    (let [dir (tmp-dir)
          g (issued)]
      (grant/register! dir g)
      (corrupt! dir g suffix text)
      (is (= :fault (:anomaly/type (grant/current dir (:grant/id g)))))
      (is (anomaly/anomaly? (grant/revoke-stored! dir (:grant/id g) :revocation/operator now))))))

(deftest ^{:stratum 2} mismatched-identity-and-widening-marker-are-refused-test
  (let [g (issued)
        marker {:grant/id (:grant/id g)
                :grant/revoked-at (str now)
                :grant/revocation-reason :revocation/operator}]
    (doseq [bad [(assoc marker :grant/id (random-uuid))
                 (assoc marker :grant/scope {})]]
      (let [dir (tmp-dir)]
        (grant/register! dir g)
        (corrupt! dir g ".revocation.edn" (pr-str bad))
        (is (= :fault (:anomaly/type (grant/current dir (:grant/id g)))))))))

(deftest ^{:stratum 2} trailing-data-is-not-ignored-test
  (let [dir (tmp-dir)
        g (issued)]
    (grant/register! dir g)
    (let [file (record-file dir g ".grant.edn")
          original (slurp file)]
      (spit file (str original " {}"))
      (is (= :fault (:anomaly/type (grant/current dir (:grant/id g))))))))

(deftest ^{:stratum 2} unsupported-edn-is-refused-before-publication-test
  (doseq [value [(Object.) identity]]
    (let [dir (tmp-dir)
          g (assoc-in (issued) [:grant/scope :unsupported] value)]
      (is (= :invalid-input (:anomaly/type (grant/register! dir g))))
      (is (empty? (seq (.listFiles (File. dir)))))
      (is (nil? (grant/current dir (:grant/id g)))))))

(deftest ^{:stratum 2} date-timestamps-normalize-on-reload-test
  (doseq [date-fn [#(Date/from %) sql-date]]
    (let [dir (tmp-dir)
          g (issued)
          dated (assoc g :grant/issued-at (date-fn now) :grant/expires-at (date-fn later))]
      (is (= g (grant/register! dir dated)))
      (is (= g (grant/current dir (:grant/id g))))
      (let [revoked (grant/revoke-stored! dir (:grant/id g) :revocation/operator (date-fn now))]
        (is (= now (:grant/revoked-at revoked)))
        (is (= revoked (grant/current dir (:grant/id g))))
        (is (= revoked (grant/revoke-stored! dir (:grant/id g) :revocation/operator later)))))))

(deftest ^{:stratum 2} registration-requires-pristine-issuance-test
  (doseq [state [{:grant/revocation-reason :revocation/operator}
                {:grant/revoked-at now}
                {:grant/revoked-at now :grant/revocation-reason :revocation/operator}]]
    (let [dir (tmp-dir)
          g (merge (issued) state)]
      (is (= :invalid-input (:anomaly/type (grant/register! dir g))))
      (is (empty? (seq (.listFiles (File. dir)))))
      (corrupt! dir g ".grant.edn" (pr-str (codec/->wire g)))
      (is (= :fault (:anomaly/type (grant/current dir (:grant/id g))))))))

(deftest ^{:stratum 2} dangling-authority-symlinks-fail-closed-test
  (doseq [suffix [".grant.edn" ".revocation.edn"]]
    (let [dir (tmp-dir)
          g (issued)
          file (record-file dir g suffix)
          missing (io/file dir "missing-target")]
      (grant/register! dir g)
      (Files/deleteIfExists (.toPath file))
      (Files/createSymbolicLink (.toPath file) (.toPath missing) (into-array FileAttribute []))
      (is (= :fault (:anomaly/type (grant/current dir (:grant/id g)))))
      (is (= :fault (:anomaly/type (grant/revoke-stored! dir (:grant/id g) :revocation/operator now)))))))

(deftest ^{:stratum 2} file-sync-failure-does-not-publish-test
  (let [dir (tmp-dir)
        g (issued)]
    (with-redefs [durability/write-new-text! sync-failure!]
      (is (= :fault (:anomaly/type (grant/register! dir g)))))
    (is (nil? (grant/current dir (:grant/id g))))
    (is (empty? (seq (.listFiles (io/file dir "grants")))))))

(deftest ^{:stratum 2} malformed-utf8-is-not-replaced-in-authority-test
  (let [dir (tmp-dir)
        g (issued)]
    (grant/register! dir g)
    (corrupt-utf8! (record-file dir g ".grant.edn"))
    (is (= :fault (:anomaly/type (grant/current dir (:grant/id g)))))
    (is (= :fault (:anomaly/type (grant/revoke-stored! dir (:grant/id g) :revocation/operator now))))))

(deftest ^{:stratum 2} malformed-unicode-is-not-published-test
  (let [dir (tmp-dir)
        g (assoc (issued) :grant/principal (str "workflow:" (char 0xd800)))]
    (is (= :fault (:anomaly/type (grant/register! dir g))))
    (is (nil? (grant/current dir (:grant/id g))))))

(deftest ^{:stratum 2} authority-records-coexist-with-breach-history-test
  (let [dir (tmp-dir)
        g (issued)
        breach {:breach/id (random-uuid)
                :breach/principal "workflow:other"
                :breach/grant-id (random-uuid)
                :breach/effect-class :effect/pr-create
                :breach/axis :constraint/max-count
                :breach/limit 1 :breach/observed 2
                :breach/detection :detected :breach/at now}]
    (is (= breach (grant/record-breach! dir breach)))
    (grant/register! dir g)
    (is (= [breach] (grant/breach-history dir)))
    (grant/revoke-stored! dir (:grant/id g) :revocation/operator now)
    (is (= [breach] (grant/breach-history dir)))
    (is (= now (:grant/revoked-at (grant/current dir (:grant/id g)))))))

(deftest ^{:stratum 2} orphaned-revocation-markers-are-storage-faults-test
  (doseq [kind [:valid :corrupt :dangling]]
    (let [dir (tmp-dir)
          g (issued)
          id (:grant/id g)
          marker (record-file dir g ".revocation.edn")]
      (grant/register! dir g)
      (grant/revoke-stored! dir id :revocation/operator now)
      (Files/delete (.toPath (record-file dir g ".grant.edn")))
      (case kind
        :valid nil
        :corrupt (spit marker "{")
        :dangling (do (Files/delete (.toPath marker))
                      (Files/createSymbolicLink (.toPath marker) (.toPath (io/file dir "missing"))
                                                (into-array FileAttribute []))))
      (is (= :fault (:anomaly/type (grant/current dir id))) (name kind))
      (is (= (if (= :valid kind) :conflict :fault) (:anomaly/type (grant/register! dir g))))
      (is (not (.exists (record-file dir g ".grant.edn"))))
      (is (= :fault (:anomaly/type (grant/revoke-stored! dir id :revocation/operator now)))))))

(deftest ^{:stratum 2} linked-directory-components-refuse-all-authority-io-test
  (doseq [location [:root :grants :ancestor] dangling? [false true]]
    (let [base (tmp-dir)
          outside (tmp-dir)
          link (io/file base (if (= :grants location) "grants" "link"))
          dir (case location :root link :grants base :ancestor (io/file link "nested"))
          target (if dangling? (io/file outside "missing") (io/file outside))
          g (issued)]
      (Files/createSymbolicLink (.toPath link) (.toPath target) (into-array FileAttribute []))
      (is (= :fault (:anomaly/type (grant/register! dir g))))
      (is (= :fault (:anomaly/type (grant/current dir (:grant/id g)))))
      (is (= :fault (:anomaly/type (grant/revoke-stored! dir (:grant/id g) :revocation/operator now))))
      (is (empty? (seq (.listFiles (File. outside))))))))

(deftest ^{:stratum 2} failed-publication-sync-cannot-acknowledge-revocation-test
  (let [dir (tmp-dir)
        g (issued)
        id (:grant/id g)]
    (grant/register! dir g)
    (with-redefs [durability/sync-ancestry! sync-failure!
                  durability/confirm! sync-failure!]
      (is (= :fault (:anomaly/type (grant/revoke-stored! dir id :revocation/operator now))))
      (is (= now (:grant/revoked-at (grant/current dir id))))
      (is (= :fault (:anomaly/type (grant/revoke-stored! dir id :revocation/superseded later)))))
    (let [confirmed (grant/revoke-stored! dir id :revocation/superseded later)]
      (is (= now (:grant/revoked-at confirmed)))
      (is (= :revocation/operator (:grant/revocation-reason confirmed)))
      (is (= confirmed (grant/current dir id))))))

(deftest ^{:stratum 2} invalid-input-cannot-address-outside-store-test
  (doseq [id [nil "../outside" 42]]
    (let [dir (tmp-dir)]
      (is (= :invalid-input (:anomaly/type (grant/current dir id))))
      (is (= :invalid-input (:anomaly/type (grant/revoke-stored! dir id :revocation/operator now))))))
  (doseq [dir [nil "" " " (File. "")]]
    (is (= :invalid-input (:anomaly/type (grant/register! dir (issued))))))
  (is (= :invalid-input (:anomaly/type (grant/register! (tmp-dir) {})))))

(deftest ^{:stratum 2} unwritable-store-does-not-report-registration-test
  (let [dir (tmp-dir)
        file (io/file dir "not-a-directory")
        g (issued)]
    (spit file "occupied")
    (is (= :fault (:anomaly/type (grant/register! file g))))
    (is (= :fault (:anomaly/type (grant/current file (:grant/id g)))))
    (is (= "occupied" (slurp file)))))

(deftest ^{:stratum 2} concurrent-registration-has-one-winner-test
  (let [dir (tmp-dir)
        g (issued)
        start (promise)
        a (future (register-after! start dir g))
        b (future (register-after! start dir g))]
    (deliver start true)
    (let [results [(deref a timeout-ms :timeout) (deref b timeout-ms :timeout)]]
      (is (= 1 (count (filter #{g} results))))
      (is (= 1 (count (filter #(= :conflict (:anomaly/type %)) results))))
      (is (= g (grant/current dir (:grant/id g)))))))

(deftest ^{:stratum 2} concurrent-revocation-preserves-first-marker-test
  (let [dir (tmp-dir)
        g (issued)
        id (:grant/id g)
        start (promise)]
    (grant/register! dir g)
    (let [a (future (revoke-after! start dir id :revocation/operator))
          b (future (revoke-after! start dir id :revocation/superseded))]
      (deliver start true)
      (let [results [(deref a timeout-ms :timeout) (deref b timeout-ms :timeout)]
            current (grant/current dir id)]
        (is (some #{current} results))
        (is (= now (:grant/revoked-at current)))
        (is (every? #(or (= current %) (= :conflict (:anomaly/type %))) results))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.execution-grant.store-test))
