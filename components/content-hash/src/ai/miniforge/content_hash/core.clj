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
(ns ai.miniforge.content-hash.core
  "Implementation of content hashing primitives.

   Domain-free: no SDLC, workflow, or evidence schema is referenced here.
   Computes a canonical EDN serialization (with deterministically ordered map
   keys) and a SHA-256 digest over that serialization.

   Instants are normalized by ACTUAL type before serialization — see
   `inst->literal`."
  (:import
   [java.time Instant ZoneOffset]
   [java.time.format DateTimeFormatter DecimalStyle]
   [java.util Date Locale]))

;------------------------------------------------------------------------------ Layer 0

;; Instants
(def ^{:stratum 0} ^:private inst-seconds
  "Whole-second half of an `#inst` literal, rendered in UTC.

   `uuuu`, not `yyyy`. `yyyy` is year-of-era and renders without the
   era, so 1 BC and 2 AD both print `0002` — two distinct instants
   collapsing to one digest, which is the defect this namespace exists
   to keep out. `uuuu` is the proleptic year and is identical for every
   AD year.

   Locale and decimal style are pinned. `ofPattern` otherwise captures
   `Locale/getDefault` at load time, and a locale whose numbering system
   is not `latn` renders non-ASCII digits — which would make a content
   hash depend on the machine that computed it."
  (-> (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss" Locale/ROOT)
      (.withZone ZoneOffset/UTC)
      (.withDecimalStyle DecimalStyle/STANDARD)))

(defn- ^{:stratum 0} inst-fraction
  "Fractional-seconds digits for `nanos`.

   Three when the value is millisecond-aligned — the width
   `java.util.Date` prints — so a Date renders byte-identically to what
   it did before instants were normalized here and every hash computed
   over one still holds. That holds across the Gregorian range, which
   is every timestamp a caller will hold; `Date` renders pre-1582 dates
   on the Julian calendar and omits the ISO `+` past year 9999, so it
   disagrees with ISO-8601 there and those two hashes do move.

   Six or nine digits only when finer precision is present:
   `Instant/now` carries microseconds on current JVMs, and truncating
   them would let distinct instants collide.

   `String/format` under `Locale/ROOT`, never `clojure.core/format`,
   which uses the default locale: under a numbering system such as
   `hi-IN-u-nu-deva` the padded digits come back Devanagari and the
   digest changes with the machine. Demonstrated, not assumed —
   `rendering-is-locale-independent` pins it."
  ^String [nanos]
  (cond
    (zero? (rem nanos 1000000))
    (String/format Locale/ROOT "%03d" (object-array [(quot nanos 1000000)]))

    (zero? (rem nanos 1000))
    (String/format Locale/ROOT "%06d" (object-array [(quot nanos 1000)]))

    :else
    (String/format Locale/ROOT "%09d" (object-array [nanos]))))

;; SHA-256 digest
(defn- ^{:stratum 0} sha256-hex
  "Compute the SHA-256 digest of the given UTF-8 string and return it as a
   lowercase hex string (64 characters)."
  [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; Comparator
(defn- ^{:stratum 0} type-rank
  "Rank disparate map-key types so canonical EDN can sort heterogeneous keys
   without throwing. Lower ranks sort first.

   A normalized instant ranks where a raw Date used to, so a map keyed
   by timestamps sorts exactly as it did before normalization. The raw
   Date and Instant ranks stay as defense for direct comparator use;
   `->canonical` no longer reaches them. The `#inst` test is inlined
   rather than named so this stays a leaf — a named predicate would
   push every layer above it up one, on a file already over budget."
  [x]
  (cond
    (nil? x) 0
    (boolean? x) 1
    (number? x) 2
    (char? x) 3
    (string? x) 4
    (keyword? x) 5
    (symbol? x) 6
    (uuid? x) 7
    (instance? java.util.Date x) 8
    (and (tagged-literal? x) (= 'inst (:tag x))) 8
    (instance? java.time.Instant x) 9
    :else 10))

(defn- ^{:stratum 0} refuse-collapse!
  "Throw when instant normalization merged two entries of a container.

   Only `#inst` literals are considered, and they are compared under
   `=`. Narrowing to them is what keeps this from firing on a collision
   this namespace did not cause: `mapv` renders a list and a vector of
   the same items to one vector, so a container whose own comparator is
   inconsistent with `=` — a `sorted-set-by` ordering `[1 2]` before
   `(1 2)`, say — can hold two entries that normalize alike for reasons
   that have nothing to do with timestamps. That collapsed silently
   before this namespace handled instants and still does.

   Checking normalized values rather than which inputs got rewritten is
   what catches a caller who already holds an `#inst` literal: that key
   is not rewritten, so a guard keyed on rewriting would let it silently
   overwrite an `Instant` for the same moment, or not, by iteration
   order. The literal test is inlined for the reason given on
   `type-rank` — naming it would cost a layer on a file already over
   the SL003 budget."
  [what normalized]
  (let [insts (filterv #(and (tagged-literal? %) (= 'inst (:tag %))) normalized)]
    (when-let [dup (some (fn [[v n]] (when (> n 1) v)) (frequencies insts))]
      (throw (IllegalArgumentException.
              ;; pr-str, not str: a TaggedLiteral's toString is its identity
              ;; hash — the very thing this namespace exists to keep out.
              (str what " that normalize to one instant: " (pr-str dup)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} inst->literal
  "An instant as an `#inst` tagged literal, dispatched on ACTUAL type.

   `clojure.core/inst?` admits BOTH `java.time.Instant` and
   `java.util.Date`, and `pr-str` — which renders the canonical EDN —
   treats them as unrelated. A Date prints readable `#inst \"…\"`. An
   Instant prints `#object[java.time.Instant 0x1f2e3d4c \"…\"]`, which
   is not readable EDN and embeds the object's identity hash — so the
   same instant rendered differently on every call, making
   `content-hash` non-deterministic. That is the one property the
   component exists to provide: hashing one evidence bundle twice gave
   two digests.

   Anything else throws rather than being hashed under a rendering
   nothing has checked — the same refusal the `->iso` helpers in
   effect-transaction and policy-pack make.

   `inst?` is open, not a closed pair: it is satisfied by any
   `clojure.core/Inst` implementation, and this namespace's own test
   builds one to prove the refusal fires. So the two branches below
   are exhaustive over the types actually SUPPORTED — the two the JDK
   and Clojure's own readers produce — rather than over everything
   `inst?` will say yes to. Reaching the third branch means a caller
   handed a timestamp representation nobody here has a rendering for,
   which is the programmer error rule 005 names, hence
   `IllegalArgumentException` rather than the siblings' `ex-info`; this
   component also carries no dependencies, so `throw+` is not on the
   table."
  [v]
  (let [^Instant i (cond
                     (instance? Instant v) v
                     (instance? Date v)    (.toInstant ^Date v)
                     :else
                     (throw (IllegalArgumentException.
                             (str "Not a supported instant type: "
                                  (some-> v class .getName)))))]
    (tagged-literal 'inst (str (.format inst-seconds i)
                               "."
                               (inst-fraction (.getNano i))
                               "-00:00"))))

(defn- ^{:stratum 1} canonical-compare
  "Total-order comparator for canonical EDN. Same-type values use natural
   compare; cross-type values fall back to string representation comparison
   under a stable type rank."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (if (not= ra rb)
      (compare ra rb)
      (try
        (compare a b)
        (catch Exception _
          (compare (pr-str a) (pr-str b)))))))

;------------------------------------------------------------------------------ Layer 2

;; Canonical EDN
(defn- ^{:stratum 2} ->canonical
  "Walk a value into a canonical form: maps become sorted-maps, sets become
   sorted-sets, sequential collections become vectors, instants become
   `#inst` literals. Other scalars pass through.

   Map keys are canonicalized only when they are instants; every other
   key is left exactly as it was, so no existing map's ordering or
   rendering moves.

   A map holding two instant keys for the same moment — one `Instant`,
   one `Date` — is refused, and so is a set holding two such elements.
   They are two distinct keys (or elements) to Clojure but one once
   normalized, so one would quietly swallow the other. In a map, which
   one survived would follow the source map's iteration order:
   `{i :a, d :b}` and `{d :b, i :a}` are `=` and would hash
   differently. In a set it is worse — `#{i d}` and `#{i}` are distinct
   values that would produce the identical digest. Refusing keeps both
   halves of `canonical-edn`'s contract: equal inputs give equal
   output, and distinct inputs do not silently converge.

   Both containers detect that through `refuse-collapse!`, which
   compares the NORMALIZED values rather than which inputs were
   rewritten, and looks only at `#inst` literals — see its docstring
   for why each half matters. Every other collapse this walk can
   produce is left exactly as it was, silent: the comparator equating
   `1` and `1.0`, and `mapv` rendering a list and a vector alike.

   One self-recursive walk, replacing the mutually-recursive
   `->canonical`/`canonicalize-map`/`canonicalize-coll` trio that
   referred to each other through a `declare`. Stratum-lint reports
   that as SL007 — a reference cycle no layer ordering can express and
   `--fix` cannot resolve, so the file was uncommittable while it
   stood. Same tests in the same order, so behavior is unchanged;
   `map-entry?` precedes `coll?`, and `set?` precedes both, because a
   map entry and a set are also collections."
  [x]
  (cond
    (map? x)       (let [entries (mapv (fn [[k v]]
                                         [(if (inst? k) (inst->literal k) k)
                                          (->canonical v)])
                                       x)]
                     (refuse-collapse! "Map has two keys" (mapv first entries))
                     (into (sorted-map-by canonical-compare) entries))
    (set? x)       (let [normalized (mapv ->canonical x)]
                     (refuse-collapse! "Set has two elements" normalized)
                     (into (sorted-set-by canonical-compare) normalized))
    (map-entry? x) [(->canonical (key x)) (->canonical (val x))]
    (coll? x)      (mapv ->canonical x)
    (inst? x)      (inst->literal x)
    :else          x))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} canonical-edn
  "Return a deterministic EDN string for `x`.

   Maps are emitted with keys sorted under a stable comparator; sets are
   sorted; sequential collections preserve order; instants render as
   `#inst` literals. The output is suitable as input to a content hash
   because logically equal values produce identical strings regardless
   of original insertion order.

   Throws `IllegalArgumentException` on an unsupported `inst?` type, and
   on a map or set whose entries would collapse onto one instant — see
   `inst->literal` and `refuse-collapse!`."
  [x]
  (pr-str (->canonical x)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} content-hash
  "Compute the SHA-256 digest of the canonical EDN of `x`.

   Returns a lowercase 64-character hex string. Logically equal values
   (e.g. maps with identical keys/values regardless of insertion order)
   produce identical hashes.

   Throws whatever `canonical-edn` throws — it is the same walk."
  [x]
  (sha256-hex (canonical-edn x)))
