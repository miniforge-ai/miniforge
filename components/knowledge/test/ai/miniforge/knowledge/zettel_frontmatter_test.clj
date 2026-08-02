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
(ns ai.miniforge.knowledge.zettel-frontmatter-test
  "The frontmatter timestamp boundary.

   `:zettel/created` / `:zettel/modified` are validated with `inst?`,
   which admits BOTH `java.time.Instant` and `java.util.Date`. A zettel
   the schema calls VALID can therefore arrive at serialization holding
   either, and `create-zettel` stamps a Date — so the Date path is the
   default one, not an edge case.

   These tests pin the write boundary: both inst types must reach disk
   as the same ISO-8601 string, and a value that is neither must be
   refused rather than persisted in a shape `parse-inst` cannot read."
  (:require
   [ai.miniforge.knowledge.schema :as schema]
   [ai.miniforge.knowledge.zettel :as zettel]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]
   [java.util Date Locale]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers.
(def ^{:stratum 0} now
  "A pinned instant carrying MILLISECONDS. `Date.toString` has no
   millisecond field, so `.789` is what proves the old `str` path was
   lossy and the new one is not."
  (Instant/parse "2026-08-01T12:34:56.789Z"))

(defn- ^{:stratum 0} zettel-with
  "A schema-valid zettel whose timestamps hold `t`."
  [t]
  {:zettel/id      (random-uuid)
   :zettel/uid     "210-frontmatter"
   :zettel/title   "Frontmatter"
   :zettel/content "Body."
   :zettel/type    :rule
   :zettel/created t
   :zettel/modified t
   :zettel/author  "user"})

(defn- ^{:stratum 0} frontmatter-line
  "The `key: value` frontmatter line for `k`, or nil."
  [markdown k]
  (->> (str/split-lines markdown)
       (filter #(str/starts-with? % (str (name k) ":")))
       first))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} both-inst-types-are-schema-valid-test
  ;; The premise the rest of this namespace rests on. If `inst?` ever
  ;; stopped admitting Date, the write boundary would not need to
  ;; normalize — so assert it rather than assume it.
  (testing "the schema accepts a Date and an Instant alike"
    (is (m/validate schema/Zettel (zettel-with (Date/from now))))
    (is (m/validate schema/Zettel (zettel-with now)))))

(deftest ^{:stratum 1} both-inst-types-serialize-to-the-same-iso-string-test
  ;; `str` on a Date yields `Sat Aug 01 05:34:56 PDT 2026` — the writing
  ;; machine's timezone, no milliseconds, and unreadable by
  ;; `Instant/parse`. Both types must converge on one wire form.
  (let [from-date (zettel/zettel->markdown (zettel-with (Date/from now)))
        from-inst (zettel/zettel->markdown (zettel-with now))]
    (testing ":created is ISO-8601 regardless of which inst type was passed"
      (is (= (frontmatter-line from-inst :created)
             (frontmatter-line from-date :created)))
      (is (str/includes? (frontmatter-line from-date :created)
                         "2026-08-01T12:34:56.789Z")))
    (testing ":modified gets the same treatment — it is the same defect twice"
      (is (= (frontmatter-line from-inst :modified)
             (frontmatter-line from-date :modified)))
      (is (str/includes? (frontmatter-line from-date :modified)
                         "2026-08-01T12:34:56.789Z")))))

(deftest ^{:stratum 1} both-inst-types-round-trip-through-markdown-test
  ;; A reader sharing no memory with the writer must recover the same
  ;; instant — to the millisecond — from either input type.
  (doseq [[label t] [["Date" (Date/from now)] ["Instant" now]]]
    (testing (str "a zettel created with a " label " survives the markdown boundary")
      (let [back (-> (zettel-with t) zettel/zettel->markdown zettel/markdown->zettel)]
        (is (= now (.toInstant ^Date (:zettel/created back))))
        (is (= now (.toInstant ^Date (:zettel/modified back))))))))

(deftest ^{:stratum 1} round-trip-does-not-depend-on-the-default-locale-test
  ;; The `Date.toString` recovery path in `parse-inst` is built from
  ;; `EEE`/`MMM`, which do not parse under a non-English default Locale
  ;; — there it returns nil and `markdown->zettel` substitutes
  ;; `(java.util.Date.)`, silently re-dating the note to the moment it
  ;; was read. Writing ISO-8601 is what takes that path out of play.
  (let [original (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag "de-DE"))
      (let [back (-> (zettel-with (Date/from now))
                     zettel/zettel->markdown
                     zettel/markdown->zettel)]
        (is (= now (.toInstant ^Date (:zettel/created back)))
            "the creation date is preserved, not silently re-stamped to now"))
      (finally
        (Locale/setDefault original)))))

(deftest ^{:stratum 1} pre-fix-recovery-path-is-locale-independent-test
  ;; Reading records written BEFORE `->iso` landed. `Date.toString`
  ;; always emits English `EEE`/`MMM` regardless of locale, so a
  ;; default-locale `SimpleDateFormat` cannot parse its own producer's
  ;; output on a non-English host — it returns nil, and callers stamp
  ;; now instead. Recovery has to work on exactly the hosts most likely
  ;; to hold unreadable records.
  (let [original (Locale/getDefault)
        legacy (str (Date/from now))]                    ; the pre-fix wire shape
    (try
      (doseq [tag ["de-DE" "ja-JP" "en-US"]]
        (Locale/setDefault (Locale/forLanguageTag tag))
        (testing (str "a pre-fix Date.toString frontmatter value still parses under " tag)
          (is (some? (zettel/parse-inst legacy)))
          ;; To the second — Date.toString carries no milliseconds. That
          ;; loss is why this is a recovery path and not a wire format.
          (is (= (.getTime (Date/from (.minusMillis now 789)))
                 (.getTime ^Date (zettel/parse-inst legacy))))))
      (finally
        (Locale/setDefault original)))))

(deftest ^{:stratum 1} unsupported-timestamp-throws-rather-than-persisting-test
  ;; Persisting a timestamp nothing can read back does not fail here; it
  ;; fails later, when someone is trying to establish when a note was
  ;; written. Refuse at the boundary instead.
  (testing "a number is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (zettel/zettel->markdown (zettel-with 1754051696789)))))
  (testing "a string is not waved through — one that does not parse would only fail on the way out"
    (is (thrown? clojure.lang.ExceptionInfo
                 (zettel/zettel->markdown (zettel-with "2026-08-01T12:34:56.789Z")))))
  (testing "nil is refused rather than written as an empty timestamp"
    (is (thrown? clojure.lang.ExceptionInfo
                 (zettel/->iso nil)))))
