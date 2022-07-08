(ns
  ^{:doc
    "Provides support for viewing transforms for 2D visual input. Currently,
     they are two possible types of transforms:
     * resize, where an image is scaled to a larger or smaller size
     * submat, where some submatrix of the overall image is taken
     Viewing transforms are represented as a sequence of transform operations,
     where resize operations appear as
     [[old-width old-height] [new-width new-height]]
     and submat operations appear as
     {:x min-x :y min-y :width width :height height
      :old-width old-width :old-height old-height}
     There can be any number of operations in a sequence. For example, there might
     be a resize, then a submat, then another resize.

     Viewing transforms can be used to sample from the visual input. They can also
     be applied to segments to get a new version of segment (it's :region, :mask,
     and :image), as it would appear under that viewing transform. Additionally,
     viewing transforms can be inverted, and the inverse can be applied to a segment
     that was discovered under that transforms to produce a new version of the segment,
     as it would appear in the original, untransformed visual input."}
  arcadia.vision.view-transform
  (:require [arcadia.utility [general :as gen] [opencv :as cv]]
            [arcadia.vision.regions :as reg]
            [clojure.math.numeric-tower :as math]))

(defn- add-operation
  "Given either a sequence of transform operations so far, or a matrix for which we will
   begin generating transform operations, return the result of adding the next transform
   operation."
  [ops-or-mat new-op]
  (if (and (sequential? ops-or-mat) (coll? (first ops-or-mat)))
    (concat ops-or-mat (list new-op))
    (list new-op)))

(defn final-size
  "Given either a sequence of transform operations so far, or a matrix for which
   we will begin generating tranform operations, return the [w h] image size at the point
   where the next operation will be applied."
  [ops-or-mat]
  (let [last-op (when (sequential? ops-or-mat) (last ops-or-mat))]
    (or (and (vector? last-op) (second last-op))
        (and (map? last-op) [(:width last-op)
                             (:height last-op)])
        (cv/size ops-or-mat))))

(defn sample
  "Sample from a src image by applying the specified sequence of viewing transform
   operations."
  [src ops]
  (cond
    (empty? ops)
    src

    (vector? (first ops))
    (let [[old-size new-size] (first ops)]
      (sample (cv/resize src new-size) (rest ops)))

    (and (map? (first ops)) (not (neg? (:x (first ops)))))
    (sample (cv/submat src (first ops)) (rest ops))

    (map? (first ops))
    (throw (IllegalArgumentException.
            "Can't use an inverted submat operation for sampling."))))

(defn add-submat
  "Takes 1) A sequence of transform operations, matrix, or a [w h] starting size.
         2) The [w h] size of the desired submatrix.
         3) A {:x x :y y} point where the desired submatrix should be centered.
         4) A Boolean indicated whether we can adjust-to-fit?
   Computes a new submat operation and adds it to the end of the sequence of
   operations. Or if, the first argument was a matrix, makes a new sequence
   containing only this operation, as it would be applied to the matrix.

   If adjust-to-fit? is true, the submat's location will be moved if necessary to
   ensure that it fits entirely within the image on which it will be operating.
   If not, the submat will not be moved, but part of it may be cropped if it doesn't
   fit."
  [ops-or-mat [width height] {x :x y :y} adjust-to-fit?]
  (let [[old-width old-height] (final-size ops-or-mat)
        region {:x (math/round (- x (/ (dec width) 2.0)))
                :y (math/round (- y (/ (dec height) 2.0)))
                :width width :height height
                :old-width old-width :old-height old-height}]
    (if adjust-to-fit?
      (if (and (<= width old-width) (<= height old-height))
        (add-operation
          ops-or-mat
          (-> region (update :x #(-> % (max 0) (min (- old-width width))))
              (update :y #(-> % (max 0) (min (- old-height height))))))
        (throw (IllegalArgumentException.
                "add-submat failed. submatrix cannot be larger than original matrix.")))
      (when-let [new-region (reg/crop region [old-width old-height])]
        (add-operation
         ops-or-mat
         (assoc new-region :old-width old-width :old-height old-height))))))

(defn add-resize
  "Takes 1) A sequence of transform operations or a matrix.
         2) The [w h] size of the desired new size, or a scaling factor.
   Computes a new resize operation and adds it to the end of the sequence of
   operations. Or if, the first argument was a matrix, makes a new sequence
   containing only this operation, as it would be applied to the matrix."
  [ops-or-mat new-size]
  (let [[old-width old-height] (final-size ops-or-mat)]
    (if (vector? new-size)
      (add-operation ops-or-mat
                     [[old-width old-height] new-size])
      (add-operation ops-or-mat
                     [[old-width old-height]
                      (mapv int [(* old-width new-size) (* old-height new-size)])]))))

(defn invert-transform
  "Takes a sequence of viewing transform operations, reverses the order, and
   inverts each one."
  [ops]
  (map #(cond
          (vector? %) (vector (second %) (first %))
          (map? %) (-> % (assoc :width (:old-width %) :height (:old-height %)
                                :old-width (:width %) :old-height (:height %))
                       (update :x -) (update :y -)))
       (reverse ops)))

(defn get-scale
  "Gets the overall change in scale across any resize operations in a sequence
   of transforms."
  [all-ops]
  (let [ops (filter vector? all-ops)
        [[x1 _] _] (first ops)
        [_ [x2 _]] (last ops)]
    (if x1
      (float (/ x2 x1))
      1.0)))

(defn get-region
  "Gets the region from the last submat operation in a sequence of transforms."
  [ops]
  (gen/seek map? (reverse ops)))
