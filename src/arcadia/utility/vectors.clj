(ns
  ^{:doc "Helper functions for vectors of numbers."}
  arcadia.utility.vectors
  (:refer-clojure :exclude [+ - *])
  (:require [clojure.math.numeric-tower :as math]))

(defn- sqr
  [x]
  (clojure.core/* x x))

(defn- int-sqr
  [x]
  (clojure.core/* (int x) (int x)))

(defn op
  "Apply an operation over vectors."
  [op & vectors]
  (when (seq vectors) (apply mapv op vectors)))

(defn +
  "Add vectors together."
  [& vectors]
  (apply op clojure.core/+ vectors))

(defn -
  "Subtract vectors from each other."
  [& vectors]
  (apply op clojure.core/- vectors))

(defn *
  "Multiply vectors by each other."
  [& vectors]
  (apply op clojure.core/* vectors))

(defn div
  "Divide vectors from each other."
  [& vectors]
  (apply op / vectors))

(defn scalar-op
  "Apply an operation to a vector and a scalar value."
  [op vec scalar]
  (mapv #(op % scalar) vec))

(defn scalar+
  "Adds scalars to a vector."
  [vec & scalars]
  (reduce (partial scalar-op clojure.core/+) vec scalars))

(defn scalar-
  "Subtracts scalars from a vector."
  [vec & scalars]
  (reduce (partial scalar-op clojure.core/-) vec scalars))

(defn scalar*
  "Multiplies a vector by scalars."
  [vec & scalars]
  (reduce (partial scalar-op clojure.core/*) vec scalars))

(defn scalar-div
  "Divides a vector by scalars."
  [vec & scalars]
  (reduce (partial scalar-op /) vec scalars))

(defn distance
  "Computes the Euclidean distance between two points (vectors with two numbers each)."
  [[x1 y1] [x2 y2]]
  (math/sqrt (clojure.core/+ (sqr (- x1 x2)) (sqr (clojure.core/- y1 y2)))))

(defn sq-distance
  "Computes the square of the Euclidean distance between two points (vectors with
   two numbers each)."
  [[x1 y1] [x2 y2]]
  (+ (sqr (clojure.core/- x1 x2)) (sqr (clojure.core/- y1 y2))))

(defn int-sq-distance
  "Computes the square of the Euclidean distance between two points"
  [[x1 y1] [x2 y2]]
  (+ (int-sqr (- (int x1) (int x2))) (int-sqr (- (int y1) (int y2)))))

(defn dot-product
  "Computes a dot product between two vectors."
  [a b]
  (reduce + (* a b)))

(defn norm-sq
  "Computes the square of a vector's norm."
  [v]
  (dot-product v v))

(defn norm
  "Computes the norm/magnitude of a vector."
  [v]
  (Math/sqrt (norm-sq v)))

(defn unit
  "Computes the unit vector of a vector."
  [v]
  (scalar-div v (norm v)))

(defn resize
  "Resize a vector to have a given magnitude."
  [v m]
  (scalar* (unit v) m))

(defn zeros
  "Creates a vector of n zeros."
  [n]
  (vec (repeat n 0)))

(defn ones
  "Creates a vector of n ones."
  [n]
  (vec (repeat n 1)))

(defn cosine-similarity
  "Computes the cosine distance/similarity between two vectors by
  dividing their dot-product by the product of their magnitudes.
  This function returns nil if either vector has a magnitude of 0."
  [v1 v2]
  (let [norm1 (norm v1)
        norm2 (norm v2)]
    (when (not (or (zero? norm1) (zero? norm2)))
      (/ (dot-product v1 v2) (clojure.core/* (norm v1) (norm v2))))))

;;From: http://www.randygaul.net/2014/07/23/distance-point-to-line-segment/
(defn pt-lineseg-sq-dist
  "Given a line segment defined by two points a and b, finds
  the square distance to the point p. Each point is in the form [x y]."
  [p a b]
  (let [seg-diff (- b a)
        pa-diff (- a p)
        pb-diff (- p b)
        c (dot-product seg-diff pa-diff)]

    (cond
      (> c 0)                       ;;Closest point is A
      (dot-product pa-diff pa-diff)

      (> (dot-product seg-diff pb-diff) 0)  ;;Closest point is B
      (dot-product pb-diff pb-diff)

      :else                         ;;Closest point is between A and B
      (let [div (/ c (dot-product seg-diff seg-diff))
            e (- pa-diff (scalar* seg-diff div))]
        (dot-product e e)))))

(defn collision-vel
  "Where c1 and c2 are [x y] points that are the centers of two circles/spheres
  at collision, and v1 and v2 are their velocities, this function
  returns a pair of their resultant velocities [v1' v2']."
  [c1 v1 c2 v2]
  (when (and (some? c1) (some? c2) (some? v1) (some? v2)
             (not-any? nil? (concat c1 v1 c2 v2)))
    (let [N (unit (- c2 c1))
          d (scalar* N (clojure.core/- (dot-product v1 N)
                          (dot-product v2 N)))]
      [(- v1 d) (+ v2 d)])))

(defn interpolate
  "Interpolate v over the line defined by [p1, p2], where each point is [x y].
  By default, finds the y value on the line where x=v.
  When horizontal?=false, finds the x value on the line where y=v."
  ([v p0 p1] (interpolate v p0 p1 true))
  ([v [x0 y0] [x1 y1] horizontal?]
   (cond
     (and horizontal? (not= x1 x0))
     (clojure.core/+ y0 (clojure.core/* (clojure.core/- y1 y0)
              (/ (clojure.core/- v x0) (clojure.core/- x1 x0))))

     (and (not horizontal?) (not= y1 y0))
     (clojure.core/+ x0 (clojure.core/* (clojure.core/- x1 x0)
              (/ (clojure.core/- v y0) (clojure.core/- y1 y0)))))))

(defn construct-line
  "Given two points defining a line segment, returns
  the slope and y-intercept of the line y=mx+b as [m,b]."
  [[x0 y0] [x1 y1]]
  (let [m (try (/ (- y1 y0) (- x1 x0)) (catch java.lang.ArithmeticException e Integer/MAX_VALUE))]
    [m (- y0 (* m x0))]))

(defn y-val
  "Returns the y-value of the line y=mx+b at x."
  [x m b]
  (+ (* m x) b))

(defn x-val
  "Returns the x-value of the line y=mx+b at y."
  [y m b]
  (/ (- y b) m))

(defn closest-point
  "Given an [x y] point p and a line segment l (or a corresponding starting point c
  and velocity vector v), finds the closest point on l to p."
  ([p [p0 p1]] (closest-point p0 (- p1 p0) p))
  ([p c v]
   (let [m (unit v)]
     (+ c (scalar* m (dot-product m (- p c)))))))
