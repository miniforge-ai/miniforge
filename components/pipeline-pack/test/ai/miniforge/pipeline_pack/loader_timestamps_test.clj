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
(ns ai.miniforge.pipeline-pack.loader-timestamps-test
  "The manifest timestamp boundary.

   `:pack/created-at` / `:pack/updated-at` are validated with `inst?`,
   which admits BOTH `java.time.Instant` and `java.util.Date` — packs on
   disk carry `#inst` (a Date), while every in-repo producer stamps an
   Instant. `ensure-instant` must normalize both to one type, and must
   not answer `Instant/now` for a value it cannot read: this component
   has no write path, so a manifest that will not parse is corruption
   arriving from outside, and restamping it to today erases the only
   evidence of that.

   This mirrors the policy-pack fix; only the read half applies here."
  (:require
   [ai.miniforge.pipeline-pack.loader :as loader]
   [ai.miniforge.pipeline-pack.schema :as pack-schema]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers.
(def ^{:stratum 0} now
  "A pinned instant carrying MILLISECONDS — `.789` is what proves the
   normalization is lossless rather than merely second-accurate."
  (Instant/parse "2026-08-01T12:34:56.789Z"))

(defn- ^{:stratum 0} manifest-with
  "A schema-valid manifest whose timestamps hold `t`."
  [t]
  {:pack/id          "timestamps"
   :pack/name        "Timestamps"
   :pack/version     "1.0.0"
   :pack/description "Manifest timestamp boundary"
   :pack/author      "test"
   :pack/trust-level :untrusted
   :pack/authority   :authority/data
   :pack/pipelines   []
   :pack/envs        []
   :pack/created-at  t
   :pack/updated-at  t})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} both-inst-types-are-schema-valid-test
  ;; The premise the rest of this namespace rests on. If `inst?` ever
  ;; stopped admitting Date, normalization would have nothing to do — so
  ;; assert it rather than assume it.
  (testing "the schema accepts a Date and an Instant alike"
    (is (true? (pack-schema/valid-manifest? (manifest-with (Date/from now)))))
    (is (true? (pack-schema/valid-manifest? (manifest-with now))))))

(deftest ^{:stratum 1} both-inst-types-normalize-to-the-same-instant-test
  ;; `#inst` in a pack.edn reads as a Date; every producer in this repo
  ;; stamps an Instant. Downstream should not have to know which.
  (doseq [[label t] [["Date" (Date/from now)] ["Instant" now]]]
    (testing (str "a manifest carrying a " label " normalizes to that Instant")
      (let [normalized (loader/normalize-manifest (manifest-with t))]
        (is (= now (:pack/created-at normalized)))
        (is (= now (:pack/updated-at normalized)))
        (is (instance? Instant (:pack/created-at normalized)))))))

(deftest ^{:stratum 1} iso-strings-round-trip-to-the-same-instant-test
  ;; The wire form policy-pack now writes. Milliseconds must survive.
  (testing "an ISO-8601 string normalizes to the instant it names"
    (let [normalized (loader/normalize-manifest
                      (manifest-with "2026-08-01T12:34:56.789Z"))]
      (is (= now (:pack/created-at normalized))))))

(deftest ^{:stratum 1} unreadable-timestamp-is-not-restamped-to-now-test
  ;; `ensure-instant` used to answer `Instant/now` for anything it could
  ;; not parse, so a corrupt manifest normalized clean and dated today.
  ;; It must fail validation instead.
  (testing "a garbage created-at yields an invalid manifest, not a fresh one"
    (let [normalized (loader/normalize-manifest
                      (manifest-with "not-a-timestamp"))]
      (is (nil? (:pack/created-at normalized)))
      (is (false? (pack-schema/valid-manifest? normalized)))))
  (testing "a missing created-at stays missing rather than being invented"
    (let [normalized (loader/normalize-manifest
                      (dissoc (manifest-with now) :pack/created-at))]
      (is (not (contains? normalized :pack/created-at)))
      (is (false? (pack-schema/valid-manifest? normalized)))))
  (testing "ensure-instant itself reports unreadable rather than answering now"
    (is (nil? (loader/ensure-instant "not-a-timestamp")))
    (is (nil? (loader/ensure-instant 1754051696789)))
    (is (nil? (loader/ensure-instant nil)))))
