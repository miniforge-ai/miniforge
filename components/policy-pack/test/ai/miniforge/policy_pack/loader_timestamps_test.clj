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
(ns ai.miniforge.policy-pack.loader-timestamps-test
  "The pack.edn timestamp boundary.

   `inst?` admits BOTH `java.time.Instant` and `java.util.Date`, so a
   schema-VALID pack reaches `write-pack-to-file` holding either, and
   `pprint` renders them differently — a Date as `#inst`, an Instant as
   `#object[...]` that `edn/read-string` refuses."
  (:require
   [ai.miniforge.policy-pack.loader :as loader]
   [ai.miniforge.policy-pack.schema :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers.
(def ^{:stratum 0} now
  "A pinned instant carrying MILLISECONDS — `.789` proves losslessness."
  (Instant/parse "2026-08-01T12:34:56.789Z"))

(defn- ^{:stratum 0} pack-with
  "A pack manifest whose timestamps hold `t`. Valid for every other field,
   so validity turns on `t` alone — which is what the refusal cases rely on."
  [t]
  {:pack/id          "timestamps"
   :pack/name        "Timestamps"
   :pack/version     "1.0.0"
   :pack/description "Pack timestamp boundary"
   :pack/author      "test"
   :pack/categories  []
   :pack/rules       []
   :pack/created-at  t
   :pack/updated-at  t})

(defn- ^{:stratum 0} out-file
  "A fresh, non-existent pack path under the JVM temp directory."
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "policy-pack-ts-" (System/nanoTime) ".pack.edn"))
    .deleteOnExit))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} written-timestamps
  "Write `pack` and read the raw EDN back, without normalization."
  [pack]
  (let [f (out-file)]
    (loader/write-pack-to-file pack (.getPath f))
    (select-keys (edn/read-string (slurp f)) [:pack/created-at :pack/updated-at])))

(deftest ^{:stratum 1} both-inst-types-are-schema-valid-test
  ;; The premise the rest of this namespace rests on. If `inst?` ever
  ;; stopped admitting Date, the write boundary would not need to
  ;; normalize — so assert it rather than assume it.
  (testing "the schema accepts a Date and an Instant alike"
    (is (:valid? (schema/validate-pack (pack-with (Date/from now)))))
    (is (:valid? (schema/validate-pack (pack-with now))))))

(deftest ^{:stratum 1} both-inst-types-round-trip-through-the-loader-test
  ;; A reader sharing no memory with the writer must recover the same
  ;; instant — to the millisecond — from either input type.
  (doseq [[label t] [["Date" (Date/from now)] ["Instant" now]]]
    (testing (str "a pack written from a " label " survives the disk boundary")
      (let [f      (out-file)
            _      (loader/write-pack-to-file (pack-with t) (.getPath f))
            result (loader/load-pack-from-file (.getPath f))]
        (is (:success? result))
        (is (= now (get-in result [:pack :pack/created-at])))
        (is (= now (get-in result [:pack :pack/updated-at])))))))

(deftest ^{:stratum 1} unsupported-timestamp-is-refused-not-written-test
  ;; Persisting a timestamp nothing can read back does not fail here; it
  ;; fails later, when someone is trying to establish when a pack was
  ;; authored. Refuse at the boundary, and leave no file behind.
  (doseq [[label t] [["a number" 1754051696789]
                     ["a string" "2026-08-01T12:34:56.789Z"]
                     ["an explicit nil" nil]]]
    (testing (str label " is refused")
      (let [f      (out-file)
            result (loader/write-pack-to-file (assoc (pack-with now)
                                                     :pack/created-at t)
                                              (.getPath f))]
        (is (false? (:success? result)))
        (is (not (.exists f))
            "nothing is written when a timestamp is refused")))))

(deftest ^{:stratum 1} unreadable-timestamp-is-not-restamped-to-now-test
  ;; `ensure-instant` used to answer `Instant/now` for anything it could
  ;; not parse, so a corrupt pack loaded clean and dated today. It must
  ;; fail validation instead.
  (testing "a garbage created-at yields an invalid pack, not a fresh one"
    (let [f (out-file)]
      (spit f (pr-str (assoc (pack-with now) :pack/created-at "not-a-timestamp")))
      (let [result (loader/load-pack-from-file (.getPath f))]
        (is (false? (:success? result)))
        (is (nil? (get-in result [:pack :pack/created-at]))))))
  (testing "a missing created-at yields an invalid pack, not a fresh one"
    (let [f (out-file)]
      (spit f (pr-str (dissoc (pack-with now) :pack/created-at)))
      (is (false? (:success? (loader/load-pack-from-file (.getPath f)))))))
  (testing "ensure-instant itself reports unreadable rather than answering now"
    (is (nil? (loader/ensure-instant "not-a-timestamp")))
    (is (nil? (loader/ensure-instant 1754051696789)))
    (is (nil? (loader/ensure-instant nil)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} both-inst-types-serialize-to-the-same-iso-string-test
  ;; The defect itself is in the `edn/read-string` inside the helper: on
  ;; the unfixed writer, the Instant pack renders as `#object[...]` and
  ;; the loader's own reader throws `No reader function for tag object`,
  ;; so the file cannot be loaded at all. Past that, one wire form —
  ;; whichever inst type the caller happened to hold.
  (testing "both types converge on the same ISO-8601 string"
    (is (= (written-timestamps (pack-with now))
           (written-timestamps (pack-with (Date/from now)))
           {:pack/created-at "2026-08-01T12:34:56.789Z"
            :pack/updated-at "2026-08-01T12:34:56.789Z"}))))

(deftest ^{:stratum 2} optional-signed-at-is-normalized-too-test
  ;; `:pack/signed-at` is the same `inst?` declaration on the same map.
  ;; Nothing in the repo stamps it yet, so it is latent rather than
  ;; default — but one Instant there makes the whole file unreadable.
  (testing "a signed pack reads back, and an unsigned one gains no key"
    (let [plain (out-file)]
      (is (= {:pack/created-at "2026-08-01T12:34:56.789Z"
              :pack/updated-at "2026-08-01T12:34:56.789Z"}
             (written-timestamps (assoc (pack-with now) :pack/signed-at now))))
      (loader/write-pack-to-file (pack-with now) (.getPath plain))
      (is (not (contains? (edn/read-string (slurp plain)) :pack/signed-at))))))
