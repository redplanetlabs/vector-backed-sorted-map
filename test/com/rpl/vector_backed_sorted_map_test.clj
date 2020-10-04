(ns com.rpl.vector-backed-sorted-map-test
  (:use [clojure test]
        [clojure.test.check.clojure-test])
  (:require [com.rpl.vector-backed-sorted-map :as vbsm]
            [clojure.test.check
             [generators :as gen]
             [properties :as prop]])
  )


(deftest vbsm-basic-test
  (let [m (vbsm/read-only-vector-backed-sorted-map [[1 1] [2 2] [3 3]])]
    (is (= 1 (get m 1)))
    (is (= 2 (get m 2)))
    (is (= 3 (get m 3)))
    (is (nil? (get m 4)))
    (is (= :default (get m 4 :default)))
    (is (= [1 2 3] (keys m)))
  ))

(deftest updatable-vbsm-basic-test
  (let [m (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])]
    (is (= 1 (get m 1)))
    (is (= 2 (get m 2)))
    (is (= 3 (get m 3)))
    (is (nil? (get m 4)))
    (is (= 3 (count m)))
    (is (= :default (get m 4 :default)))
    (is (= [1 2 3] (keys m)))

    (let [m (assoc m 2 5)]
      (is (= 3 (count m)))
      (is (= 1 (get m 1)))
      (is (= 5 (get m 2)))
      (is (= 3 (get m 3)))
      (is (nil? (get m 4)))
      (is (= [1 2 3] (keys m)))

      (let [ m (assoc m 4 4)]
        (is (= 4 (count m)))
        (is (= 1 (get m 1)))
        (is (= 5 (get m 2)))
        (is (= 3 (get m 3)))
        (is (= 4 (get m 4)))
        (is (= [1 2 3 4] (keys m)))

        (let [m (assoc m 0 0 -1 -1)]
          (is (= 6 (count m)))
          (is (= 0 (get m 0)))
          (is (= -1 (get m -1)))
          (is (= 1 (get m 1)))
          (is (= 5 (get m 2)))
          (is (= 3 (get m 3)))
          (is (= 4 (get m 4)))
          (is (= [-1 0 1 2 3 4] (keys m)))

          (let [ m1 (dissoc m 10)]
            (is (identical? m m1)))

          (let [ m (dissoc m 0)]
            (is (= 5 (count m)))
            (is (nil? (get m 0)))
            (is (= -1 (get m -1)))
            (is (= 1 (get m 1)))
            (is (= 5 (get m 2)))
            (is (= 3 (get m 3)))
            (is (= 4 (get m 4)))
            (is (= [-1 1 2 3 4] (keys m)))

            (let [ m (dissoc m -1 4)]
              (is (= 3 (count m)))
              (is (nil? (get m 0)))
              (is (nil? (get m -1)))
              (is (= 1 (get m 1)))
              (is (= 5 (get m 2)))
              (is (= 3 (get m 3)))
              (is (nil? (get m 4)))
              (is (= [1 2 3] (keys m))))))))
    ))

(deftest empty-vec-test
  (let [ m (vbsm/vector-backed-sorted-map [])]
    (is (nil? (get m 1)))
    (is (nil? (keys m))))

  (let [m (vbsm/vector-backed-sorted-map [])]
    (is (nil? (get m 1)))
    (is (nil? (keys m)))
    (let [ m (assoc m 1 1)]
      (is (= [1] (keys m)))))

  (let [m (vbsm/vector-backed-sorted-map [])]
    (is (nil? (keys m)))
    (let [m (dissoc m 1)]
      (is (nil? (keys m)))))
  )

(defspec regular-map-equivalence-test
  1000
  (prop/for-all [init-state (gen/vector
                              (gen/tuple
                                gen/int
                                gen/int)
                              0 25)
                 actions (gen/vector
                           (gen/tuple
                             (gen/elements #{:assoc :dissoc})
                             gen/int
                             gen/int)
                           1 1000)]
    (let [regular-map (into (sorted-map) init-state)
          vbsm (vbsm/vector-backed-sorted-map (vec (into (sorted-map) init-state)))
          [final-regular-map final-vbsm]
            (reduce
              (fn [[r-map vb-map] [action-typ k v]]
                (if (= action-typ :assoc)
                  [(assoc r-map k v) (assoc vb-map k v)]
                  [(dissoc r-map k) (dissoc vb-map k)]))
              [regular-map vbsm]
              actions)]
      (if (= final-regular-map final-vbsm)
        true
        (do
          (println "regular:" final-regular-map)
          (println "vbsm:   " final-vbsm)
          (println "count regular:" (count final-regular-map))
          (println "count vbsm:   " (count final-vbsm))
          (println "keys regular:" (keys final-regular-map))
          (println "keys vbsm:" (keys final-vbsm))
          false)))))

(deftest vbsm-sorted-test
  (let [ m (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])]
    (is (= [1 2 3] (keys m)))
    (let [ headmap (subseq m <= 2)]
      (is (= [1 2] (keys headmap))))

    (let [tailmap (subseq m >= 2)]
      (is (= [2 3] (keys tailmap)))))
  )

(deftest updatable-sorted-test
  (let [ m (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])]
    (is (= [1 2 3] (keys m)))
    (let [headmap (subseq m <= 2)]
      (is (= [1 2] (keys headmap))))

    (let [tailmap (subseq m >= 2)]
      (is (= [2 3] (keys tailmap)))))


(deftest check-integrity-test
  (is (thrown? Error (vbsm/vector-backed-sorted-map [[3 1] [2 2] [1 3]])))
  ))

(deftest merge-sorted-basic-test
  (let [vbsm1 (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])
        vbsm2 (vbsm/vector-backed-sorted-map [[4 4] [5 5] [6 6]])
        merged-vbsm (vbsm/merge-sorted-optimal vbsm1 vbsm2)]
    (is (= merged-vbsm (sorted-map 1 1 2 2 3 3 4 4 5 5 6 6))))

  (let [vbsm1 (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])
        vbsm2 (vbsm/vector-backed-sorted-map [[1 2] [2 1] [6 6]])
        merged-vbsm (vbsm/merge-sorted-optimal vbsm1 vbsm2)]
    (is (= merged-vbsm (sorted-map 1 2 2 1 3 3 6 6))))

  (let [vbsm1 (vbsm/vector-backed-sorted-map [[1 1] [2 2] [3 3]])
        vbsm2 (vbsm/vector-backed-sorted-map [[1 2] [2 1] [6 6]])
        merged-vbsm (vbsm/merge-sorted-optimal vbsm1 vbsm2 +)]
    (is (= merged-vbsm (sorted-map 1 3 2 3 3 3 6 6))))
  )

(defn- mk-vbsm [kvs]
  (vbsm/vector-backed-sorted-map (vec (into (sorted-map) kvs))))

(defspec merge-sorted-generative-test
  1000
  (prop/for-all [a-vals (gen/vector
                         (gen/tuple
                          gen/int
                          gen/int)
                         0 2000)
                 b-vals (gen/vector
                         (gen/tuple
                          gen/int
                          gen/int)
                         0 2000)]
                (let [vbsm-a (mk-vbsm a-vals)
                      vbsm-b (mk-vbsm b-vals)
                      merge-sorted-result (vbsm/merge-sorted-optimal vbsm-a vbsm-b)
                      standard-merge-result (merge vbsm-a vbsm-b)]
                  (if (= merge-sorted-result standard-merge-result)
                    true
                    (do
                      (println "merge-sorted:" merge-sorted-result)
                      (println "clj merge:   " standard-merge-result)
                      false)))))
