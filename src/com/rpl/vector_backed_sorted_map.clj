(ns com.rpl.vector-backed-sorted-map
  (:use [potemkin])
  )

(assert (do (println "Warning: Assertions are enabled while loading VectorBackedSortedMap. This has a gigantic performance impact and should only be allowed in testing!")
          true))

(defn apv-fast-nth [^clojure.lang.APersistentVector v ^long idx]
  (.nth v idx nil))

(declare vector-backed-sorted-map)
(declare read-only-vector-backed-sorted-map)

(declare check-integrity!)

(defprotocol VectorizableMap
  (get-comparator [this])
  (vectorize [this])
  (vectorize-from [this k])
  )

(extend-protocol VectorizableMap
  clojure.lang.PersistentTreeMap
  (vectorize [this] (vec this))
  (get-comparator [this]
                  (let [c (.comparator this)]
                    (if (identical? c clojure.lang.RT/DEFAULT_COMPARATOR)
                      compare
                      c)))
  )

(defn- binary-search [^clojure.lang.APersistentVector kv-vec comparator k]
  (loop [lower-idx 0
         upper-idx (unchecked-dec (.length kv-vec))]
    (if (= lower-idx upper-idx)
      (let [[mid-key mid-val] (.nth kv-vec lower-idx)]
        ;; either this is it, or this is where it would be if present.
        ;; return it all and let the caller decide.
        [mid-key mid-val lower-idx])
      (let [mid-idx (unchecked-add lower-idx ^long (bit-shift-right (unchecked-subtract upper-idx lower-idx) 1))
            mid-elt (.nth kv-vec mid-idx)
            mid-key (apv-fast-nth mid-elt 0)
            comparison (comparator k mid-key)]
        (if (> ^long comparison 0)
          (recur (unchecked-inc mid-idx) upper-idx)
          (recur lower-idx mid-idx)))))
  )

(def-map-type EmptyVectorBackedSortedMap [comparator meta-map]
  (get [_ k default-value]
       default-value)

  (assoc [_ k v]
         (throw (ex-info "EmptyVBSM doesn't support assoc!" {})))

  (dissoc [this k]
          (throw (ex-info "EmptyVBSM doesn't support dissoc!" {})))

  (keys [_] [])

  (with-meta [_ mta]
    (EmptyVectorBackedSortedMap. comparator mta))

  clojure.lang.Sorted
  (entryKey [this entry]
            (first entry))
  (comparator [this] comparator)

  (seq [this ascending]
       (seq []))

  (seqFrom [this k ascending]
           (seq this))

  clojure.lang.Associative
  (containsKey [this k] false)

  VectorizableMap
  (get-comparator [_] comparator)
  (vectorize [_] [])
  (vectorize-from [_ _] [])
  )

(def-map-type VectorBackedSortedMap
  [kv-vec
   comparator
   meta-map
   ^:volatile-mutable last-get-info]
  (get [_ k default-value]
       (let [[last-key last-val] last-get-info]
         (if (and (some? last-key) (= last-key k))
           (if (identical? ::not-found last-val)
             default-value
             last-val)
           (let [[found-key val _] (binary-search kv-vec comparator k)]
             (if (= found-key k)
               (do
                 (set! last-get-info [k val])
                 val)
               (do
                 (set! last-get-info [k ::not-found])
                 default-value))))))

  (assoc [_ k v]
    (throw (ex-info "Regular VBSM doesn't support assoc!" {})))

  (dissoc [this k]
    (throw (ex-info "Regular VBSM doesn't support dissoc!" {})))

  (keys [_]
    (map first kv-vec))

  (with-meta [_ mta]
    (VectorBackedSortedMap. kv-vec comparator mta last-get-info))

  ;; this is non-standard potemkin usage. if we use the provided seqable impl,
  ;; iterating the map ends up taking O (n log n)!
  clojure.lang.Seqable
  (seq [this]
    (seq (map (fn [[k v]] (clojure.lang.MapEntry. k v)) kv-vec)))

  clojure.lang.Counted
  (count [_]
    (count kv-vec))

  clojure.lang.Sorted
  (entryKey [this entry]
            (first entry))

  (comparator [this] comparator)

  (seq [this ascending]
       (if ascending
         (seq this)
         (reverse (seq this))))

  (seqFrom [this k ascending]
           (let [trimmed-vec (vectorize-from this k)
                 entryized (map (fn [[k v]] (clojure.lang.MapEntry. k v)) trimmed-vec)]
             (if ascending
               entryized
               (reverse entryized))))

  clojure.lang.Associative
  (containsKey [this k]
               (not= ::not-found (get this k ::not-found)))

  VectorizableMap
  (get-comparator [_] comparator)
  (vectorize [_] kv-vec)
  (vectorize-from [_ k]
                  ;; TODO: could this be off by one if there's not an exact match?
                  (let [[_ _ found-idx] (binary-search kv-vec comparator k)]
                    (subvec kv-vec found-idx)))
  )

(defn- linear-merge-overlay [start-key regular-vbsm overlay-map]
  (let [a-vec (if start-key
                (vectorize-from regular-vbsm start-key)
                (vectorize regular-vbsm))
        b-vec (if start-key
                (vec (.seqFrom ^clojure.lang.PersistentTreeMap overlay-map start-key true))
                (vec overlay-map))
        comparator (get-comparator regular-vbsm)]
    (loop [results (transient [])
           a-idx 0
           b-idx 0]
      (let [
            a-elt (apv-fast-nth a-vec a-idx)
            a-key (if a-elt (apv-fast-nth a-elt 0))
            b-elt (apv-fast-nth b-vec b-idx)
            b-key (if b-elt (apv-fast-nth b-elt 0)) b-val (if b-elt (apv-fast-nth b-elt 1))
            ]
        (cond
          (nil? a-key)
          (persistent! (reduce conj! results (subvec b-vec b-idx)))

          (nil? b-key)
          (persistent! (reduce conj! results (subvec a-vec a-idx)))

          :else
          (let [comparison (comparator a-key b-key)]
            (cond
              (< ^long comparison 0)
              ;; consume a (no match)
              (recur
                (conj! results a-elt)
                (unchecked-inc a-idx)
                b-idx)

              (> ^long comparison 0)
              ;; consume b (no match)
              (recur
                (conj! results b-elt)
                a-idx
                (unchecked-inc b-idx))

              (identical? ::tombstone b-val)
              ;; keys match, and the b-val is a tombstone, so don't add anything to results
              (recur
                results
                (unchecked-inc a-idx)
                (unchecked-inc b-idx))

              :else
              ;; keys match, not a tombstone, take later value
              (recur
                (conj! results [a-key b-val])
                (unchecked-inc a-idx)
                (unchecked-inc b-idx))))))))
  )

(def-map-type OverlayVectorBackedSortedMap [regular-vbsm overlay-map comparator cached-count meta-map]
  (get [_ k default-value]
       (let [overlay-value (get overlay-map k ::not-found)]
         (if-not (identical? ::not-found overlay-value)
           overlay-value
           (if-not (identical? ::tombstone overlay-value)
             (get regular-vbsm k default-value)
             default-value))))

  (assoc [_ k v]
         (let [overlay-value (get overlay-map k ::not-found)
               new-count
               (if (contains? regular-vbsm k)
                 (if (identical? ::tombstone overlay-value)
                   (unchecked-inc ^long cached-count)
                   cached-count)
                 (if (identical? ::not-found overlay-value)
                   (unchecked-inc ^long cached-count)
                   cached-count))
               new-overlay (assoc overlay-map k v)]
           (OverlayVectorBackedSortedMap. regular-vbsm
                                          new-overlay
                                          comparator
                                          new-count
                                          meta-map)))

  (dissoc [this k]
          (let [overlay-value (get overlay-map k ::not-found)
                vbsm-value (get regular-vbsm k ::not-found)]
            (cond
              (= ::tombstone overlay-value)
              ;; already deleted this key. no-op.
              this

              (and (= ::not-found overlay-value)
                   (= ::not-found vbsm-value))
              ;; the key isn't present in the map at either level. no-op.
              this

              (and (not= ::not-found overlay-value)
                   (= ::not-found vbsm-value))
              ;; overlay has an actual value for k, but regular vbsm doesn't,
              ;; which means this key was added only to overlay.
              ;; we can just delete the tombstone itself in the overlay.
              (OverlayVectorBackedSortedMap. regular-vbsm
                                             (dissoc overlay-map k)
                                             comparator
                                             (unchecked-dec ^long cached-count)
                                             meta-map)

              :else
              ;; vbsm has a value, and overlay may or may not have a value.
              ;; either way, set a tombstone.
              (OverlayVectorBackedSortedMap. regular-vbsm
                                             (assoc overlay-map k ::tombstone)
                                             comparator
                                             (unchecked-dec ^long cached-count)
                                             meta-map)
              )))
  (keys [this] (map first (seq this)))

  (with-meta [_ mta]
    (OverlayVectorBackedSortedMap. regular-vbsm
                                   overlay-map
                                   comparator
                                   cached-count
                                   meta-map))

  clojure.lang.Seqable
  (seq [this]
       (seq (map (fn [[k v]]
                   (clojure.lang.MapEntry. k v))
                 (vectorize this))))

  clojure.lang.Counted
  (count [_]
         cached-count)

  clojure.lang.Sorted
  (entryKey [this entry]
            (first entry))

  (comparator [this] comparator)

  (seq [this ascending]
       (if ascending
         (seq this)
         (reverse (seq this))))

  (seqFrom [this k ascending]
           (let [merged-entryized (map (fn [[k v]] (clojure.lang.MapEntry. k v))
                                       (linear-merge-overlay k regular-vbsm overlay-map))]
             (if ascending
               merged-entryized
               (reverse merged-entryized))))

  clojure.lang.Associative
  (containsKey [this k]
               (not= ::not-found (get this k ::not-found)))

  VectorizableMap
  (get-comparator [_] comparator)
  (vectorize [_]
             (if (empty? overlay-map)
               (vectorize regular-vbsm)
               (linear-merge-overlay nil regular-vbsm overlay-map)))
  (vectorize-from [_ k]
                  (if (empty? overlay-map)
                    (vectorize-from regular-vbsm k)
                    (linear-merge-overlay k regular-vbsm overlay-map)))
  )

(defn read-only-vector-backed-sorted-map
  "Create a SortedMap implementation backed by a vector of K-V pairs. Creation
   of this map from an already-sorted vector of pairs is O(1). Lookups are
   O(log n). assoc / dissoc are not allowed."
  ([kv-pairs-vec]
    (read-only-vector-backed-sorted-map compare kv-pairs-vec))
  ([comparator kv-pairs-vec]
   (if (empty? kv-pairs-vec)
     (EmptyVectorBackedSortedMap. comparator {})
     (check-integrity! (VectorBackedSortedMap. kv-pairs-vec comparator {} nil)))))

(defn vector-backed-sorted-map
  "Create a SortedMap implementation backed by a vector of K-V pairs. Creation
   of this map from an already-sorted vector of pairs is O(1). Lookups are
   O(log n) if the map is never modified; assoc / dissoc calls modify a standard
   (sorted-map) overlay which is queried first. "
  ([kv-pairs-vec]
   (vector-backed-sorted-map compare kv-pairs-vec))
  ([comparator kv-pairs-vec]
   (OverlayVectorBackedSortedMap. (read-only-vector-backed-sorted-map comparator kv-pairs-vec)
                                  (sorted-map-by comparator)
                                  comparator
                                  (count kv-pairs-vec)
                                  {})))

(defn check-integrity!
  "Make sure the vbsm is actually in sorted order. Throws an exception if it's not."
  [vbsm]
  (assert (let [backing-v (vectorize vbsm)
                tuple-sizes (group-by count backing-v)]
            (= (keys tuple-sizes) [2]))
          "There were some weird sized tuples in the backing vector!!!")
  (assert (let [backing-v (vectorize vbsm)
                comparator (or (get-comparator vbsm) compare)
                sorted-keys (->> backing-v
                                 (into (sorted-map-by comparator))
                                 (mapv first))]
            (= (map first backing-v) sorted-keys))
          (str "Backing vector keys were not in sorted order!" (map first vbsm)))
  vbsm)

(defn ensure-vbsm
  ([maybe-vbsm]
   (if (instance? VectorBackedSortedMap maybe-vbsm)
     maybe-vbsm
     (vector-backed-sorted-map (get-comparator maybe-vbsm) (vectorize maybe-vbsm))))
  ([maybe-vbsm comparator]
   (if (and (instance? VectorBackedSortedMap maybe-vbsm)
            (identical? comparator (get-comparator maybe-vbsm)))
     maybe-vbsm
     (vector-backed-sorted-map comparator (vectorize maybe-vbsm)))))

(defn- latest [a b] b)

(defn merge-sorted-large
  ([vbsm1 vbsm2] (merge-sorted-large vbsm1 vbsm2 latest))
  ([vbsm1 vbsm2 f]
   (let [a-vec (vectorize vbsm1)
         b-vec (vectorize vbsm2)
         comparator (get-comparator vbsm1)]
     (loop [results (transient [])
            a-idx 0
            b-idx 0]
       (let [
             a-elt (apv-fast-nth a-vec a-idx)
             a-key (if a-elt (apv-fast-nth a-elt 0))
             b-elt (apv-fast-nth b-vec b-idx)
             b-key (if b-elt (apv-fast-nth b-elt 0))
             ]
         (cond
           (nil? a-key)
           (vector-backed-sorted-map comparator
                                     (persistent! (reduce conj! results (subvec b-vec b-idx))))

           (nil? b-key)
           (vector-backed-sorted-map comparator
                                     (persistent! (reduce conj! results (subvec a-vec a-idx))))

           :else
           (let [comparison (comparator a-key b-key)]
             (cond
               (< ^long comparison 0)
               ;; consume a (no match)
               (recur
                 (conj! results a-elt)
                 (unchecked-inc a-idx)
                 b-idx)

               (> ^long comparison 0)
               ;; consume b (no match)
               (recur
                 (conj! results b-elt)
                 a-idx
                 (unchecked-inc b-idx))

               :else
               ;; it's a match! consume both and merge with the provided fn
               (recur
                 (conj! results [a-key (f (apv-fast-nth a-elt 1) (apv-fast-nth b-elt 1))])
                 (unchecked-inc a-idx)
                 (unchecked-inc b-idx)))))))))
  )

(defn merge-sorted-small
  ([vbsm1 vbsm2] (merge-sorted-small vbsm1 vbsm2 latest))
  ([vbsm1 vbsm2 f]
   (merge-with f vbsm1 vbsm2))
  )

(defn merge-sorted-optimal
  ([vbsm1 vbsm2] (merge-sorted-optimal vbsm1 vbsm2 latest))
  ([vbsm1 vbsm2 f]
   (cond
     (empty? vbsm1)
     vbsm2

     (empty? vbsm2)
     vbsm1

     (< (count vbsm2) (* 0.10 (count vbsm1)))
     (merge-sorted-small vbsm1 vbsm2 f)

     :else
     (merge-sorted-large vbsm1 vbsm2 f)))
  )
