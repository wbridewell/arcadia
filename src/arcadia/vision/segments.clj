(ns
  ^{:doc
    "Provides support for working with segments. Segments represent locations of
     interest in the 2D visual field that might corespond to objects in the world.
     Segments are represented as hash-maps that should include, at a minimum, a
     :region field. The region should itself be a hash-map of the form
     {:x min-x :y min-y :width width :height height}, describing a 2D rectangle,
     although regions can be incomplete, with some information missing. For example,
     {:x x} would indicate that only an x-value is known. Other fields that might be
     found in a segment (but are not required):
     mask: A mask matrix indicating which pixels within the specified region are
           part of this segment
     image: A matrix (usually 8UC3) indicating the colors of the pixels within the
            specified region that are part of this segment
     view-transform: A vector indicating the sequence of transformations that were
                     made to the original visual input to create the input image
                     in which this segment was found
     input: The input image in which this segment was found"}
  arcadia.vision.segments
  (:require [arcadia.vision [features :as f] [regions :as reg]
                            [view-transform :as xform]]
            [arcadia.utility [general :as g] [opencv :as cv]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Helper fns for view transormations on segments

(defn- crop-segment
  "Takes a segment and crops it so that it fits on an image of size [w h] image-size.
   This means returning a new segment with arguments
   {:region :mask :image}, where each of these fields is cropped if it is non-nil."
  [segment image-size]
  (let [{old-min-x :x old-min-y :y old-w :width old-h :height :as reg} (:region segment)
        {new-min-x :x new-min-y :y new-w :width new-h :height :as new-reg}
        (reg/crop reg image-size)]
    (cond
      ;;We've cropped out the entire segment.
      (nil? new-min-x)
      nil

      ;;segment has neither a :mask nor an :image
      (not (or (:mask segment) (:image segment)))
      {:region new-reg}

      :else
      (let [img-min-x (- new-min-x old-min-x)
            img-min-y (- new-min-y old-min-y)
            img-region {:x img-min-x :y img-min-y :width new-w :height new-h}]
        {:region new-reg :mask (some-> (:mask segment) (cv/submat img-region))
         :image (some-> (:image segment) (cv/submat img-region))}))))

(declare prepare)
(defn- apply-transform-to-segment
  "Applies a series of transform operations to a segment. If scale-factor
   is provided, then scale the segment by this amount after performing
   all other operations."
  [segment all-ops scale-factor]
  (let [{x :x y :y w :width h :height} (:region segment)]
    (when (and x y w h)
      (loop [x x
             y y
             w w
             h h
             image-w nil
             image-h nil
             ops all-ops]
        (cond
          (empty? ops)
          (let [{w :width h :height :as region}
                (cond-> {:x (int x) :y (int y) :width (int w) :height (int h)}
                        scale-factor (reg/scale scale-factor))]
            (when (and (pos? w) (pos? h))
              (cond-> {:region region}
                      (:mask segment) (assoc :mask (cv/resize (:mask segment) [w h]))
                      (:image segment) (assoc :image (cv/resize (:image segment) [w h]))
                      image-w (crop-segment [image-w image-h])
                      true prepare)))

          (vector? (first ops))
          (let [[[old_width _] [new_width new_height]] (first ops)
                scale (/ new_width old_width)]
            (recur (* x scale) (* y scale) (* w scale) (* h scale)
                   new_width new_height (rest ops)))

          (map? (first ops))
          (let [{xdiff :x ydiff :y new_width :width new_height :height} (first ops)]
            (recur (- x xdiff) (- y ydiff) w h new_width new_height (rest ops))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;On-demand computation of some optional fields for segments. For example,
;;;;;when you call (segments/area seg), this will return the :area field, or it
;;;;;will compute the area if there is no area field. When you call
;;;;;(segments/add-area seg), you'll get a new version of the segment that includes
;;;;;the area field.

(defn area
  "Returns a segment's area, if available."
  [segment]
  (or (:area segment)
      (some-> (:mask segment) cv/count-non-zero)
      (reg/area (:region segment))))

(defn add-area
  "Updates a segment to include an :area field."
  [segment]
  (assoc segment :area (area segment)))

(defn input-size
  "Returns the [w h] size of the input image from which this segment was taken,
   if available."
  [segment]
  (or (:input-size segment)
      (some-> (or (:input segment) (:view-transform segment)) xform/final-size)))

(defn add-input-size
  "Updates a segment to include an :input-size field."
  [segment]
  (assoc segment :input-size (input-size segment)))

(defn base-segment
  "Given a segment that was computed under some viewing transform, returns the
   corresponding segment for the untransformed input. Requires that the
   segment have :view-transform defined--otherwise, we assume that this segment
   already is a base-segment. If the result is cached in :base-segment, simply
   returns that. Note that the base-segment will have an :original-segment field
   that points back to the original segment."
  [{base-segment :base-segment region :region view-transform :view-transform :as seg}]
  (or base-segment
      (when-let [vt (and view-transform (xform/invert-transform view-transform))]
        (some-> (apply-transform-to-segment seg vt nil)
                (assoc :original-segment seg :input-size (xform/final-size vt))))
      seg))

(defn add-base-segment
  "Updates a segment to include a :base-segment field."
  [seg]
  (assoc seg :base-segment (base-segment seg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;View transformation functions for segments.

(defn base-region
  "Returns the region of the segment's base-segment."
  [seg]
  (-> seg (select-keys [:base-segment :region :view-transform])
      base-segment :region))

(defn apply-transform
  "Applies a view transform to a segment. If the segment already has a view-transform
   defined, then first inverts this to get the base-segment, and then applies the new
   view-transform to that. If scale-factor is provided, then scale the segment by this
   amount after performing all other operations."
  ([segment view-transform]
   (apply-transform segment view-transform nil))
  ([{base-segment :base-segment old-view-transform :view-transform :as seg}
    view-transform scale-factor]
   (cond base-segment
     (some-> (apply-transform-to-segment base-segment view-transform scale-factor)
             (assoc :view-transform view-transform))
     old-view-transform
     (some-> (apply-transform-to-segment
              seg
              (concat (xform/invert-transform old-view-transform) view-transform)
              scale-factor)
             (assoc :view-transform view-transform))

     :else
     (some-> (apply-transform-to-segment seg view-transform scale-factor)
             (assoc :view-transform view-transform)))))

(defn apply-transform-to-region
  "Returns the result of applying the specified view-transform to the segment's region.
   Takes the same keyword arguments as apply-transform."
  ([seg view-transform]
   (-> seg (select-keys [:base-segment :region :view-transform])
       (apply-transform-to-segment view-transform nil) :region))
  ([seg view-transform scale-factor]
   (-> seg (select-keys [:base-segment :region :view-transform])
       (apply-transform-to-segment view-transform scale-factor) :region)))

(defn apply-transform-to-mask
  "Returns the result of applying the specified view-transform to the segment's mask.
    Takes the same keyword arguments as apply-transform."
  ([seg view-transform]
   (-> seg (select-keys [:base-segment :region :mask :view-transform])
       (apply-transform-to-segment view-transform nil) :mask))
  ([seg view-transform scale-factor]
   (-> seg (select-keys [:base-segment :region :mask :view-transform])
       (apply-transform-to-segment view-transform scale-factor) :mask)))

(defn scale
  "Scales a segment by the specified scale factor. If the segment's :input or
   :view-transform is defined, then crops the resulting segment to fit in the image."
  [segment scale-factor]
  (let [{new-w :width new-h :height :as new-region}
        (-> (:region segment) reg/unprepare (reg/scale scale-factor))
        input-size (input-size segment)]
    (cond-> {:region new-region
             :input (:input segment)
             :input-size input-size
             :view-transform (:view-transform segment)}
            (:mask segment) (assoc :mask (cv/resize (:mask segment) [new-w new-h]))
            (:image segment) (assoc :image (cv/resize (:image segment) [new-w new-h]))
            input-size (crop-segment input-size)
            true prepare)))

(defn scale-region
  "Returns the result of scaling the segment's region by the specified factor."
  [segment scale-factor]
  (-> segment (select-keys [:region :view-transform :input :input-size])
      (scale scale-factor) :region))

(defn translate
  "Returns the result of translating the segment by the specified {:x x :y y}
   amount."
  [segment amount]
  (let [input-size (input-size segment)]
    (cond-> {:region (-> (:region segment) reg/unprepare (reg/translate amount))
             :mask (:mask segment)
             :image (:image segment)
             :input (:input segment)
             :input-size input-size
             :view-transform (:view-transform segment)}
            input-size (crop-segment input-size)
            true prepare)))

(defn translate-region
  "Returns the result of translate the segment's region by the specified {:x x :y y}
   amount."
  [segment amount]
  (-> segment (select-keys [:region :view-transform :input :input-size])
      (translate amount) :region))

(defn translate-to
  "Returns the result of translating the segment's upper left corner to the
   specified {:x x :y y} location."
  [segment location]
  (let [input-size (input-size segment)]
    (cond-> {:region (-> (:region segment) reg/unprepare (reg/translate-to location))
             :mask (:mask segment)
             :image (:image segment)
             :input (:input segment)
             :input-size input-size
             :view-transform (:view-transform segment)}
            input-size (crop-segment input-size)
            true prepare)))

(defn translate-region-to
  "Returns the result of translate the segment's region's upper left corner to
   the specified {:x x :y y} location."
  [segment location]
  (-> segment (select-keys [:region :view-transform :input :input-size])
      (translate-to location) :region))

(defn translate-center-to
  "Returns the result of translating the segment's center to the
   specified {:x x :y y} location."
  [segment location]
  (let [input-size (input-size segment)]
    (cond-> {:region (-> (:region segment) reg/unprepare
                         (reg/translate-center-to location))
             :mask (:mask segment)
             :image (:image segment)
             :input (:input segment)
             :input-size input-size
             :view-transform (:view-transform segment)}
            input-size (crop-segment input-size)
            true prepare)))

(defn translate-region-center-to
  "Returns the result of translate the segment's region's center to
   the specified {:x x :y y} location."
  [segment location]
  (-> segment (select-keys [:region :view-transform :input :input-size])
      (translate-center-to location) :region))

(declare copy)
(defn prepare
  "Takes a {:region region :mask mask} segment created by segmentation, and adds
   in the fields that will support vision/segment functionality, including
   :input, :view-transform, :image (if input is non-nil), and :base-segment
   (if base-segment? is true)."
  [segment & {:keys [input view-transform base-segment?]}]
  (cond-> (update segment :region reg/prepare)
          input (assoc :input input :image (copy input segment))
          view-transform (assoc :view-transform view-transform)
          base-segment? add-base-segment))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Geometry functions for segments.

(defn distance
  "Distance between two segments' regions."
  [s1 s2]
  (reg/distance (:region s1) (:region s2)))

(defn intersect?
  "Returns true if two segments' regions intersect."
  [s1 s2]
  (reg/intersect? (:region s1) (:region s2)))

(defn bases-intersect?
  "Returns true if two segments' base-regions intersect."
  [s1 s2]
  (reg/intersect? (base-region s1) (base-region s2)))

(defn union
  "Computes a new segment representing the union of one or more segments, including
   the union of their :region, :mask, and :image, if those fields are available.
   For all other fields, simply takes the values of the first segment in the arguments."
  [seg0 & segments]
  (if (empty? segments)
    seg0
    (let [{x :x y :y w :width h :height :as region}
          (apply reg/union (map :region (cons seg0 segments)))
          mask0 (:mask seg0)
          image0 (:image seg0)
          mask (when (and mask0 x y) (cv/zeros mask0 :size [w h]))
          image (when (and image0 x y) (cv/zeros image0 :size [w h]))]
      (when (or mask image)
        (doseq [s (cons seg0 segments)]
          (let [{sx :x sy :y sw :width sh :height} (:region s)]
            (when (and mask sx sy)
              (cv/bitwise-or! (cv/submat mask (- sx x) (- sy y) sw sh) (:mask s)))
            (when (and image sx sy)
              (cv/set-to (cv/submat image (- sx x) (- sy y) sw sh) (:image s))))))
      (assoc seg0 :region region :mask mask :image image))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for interacting with opencv matrices. When possible, these have
;;;;;the same name as the corresponding opencv methods.
;;;;;
;;;;;Aside from functions for interacting with masks, these functions can
;;;;;work on feature maps, in addition to pure opencv matrices.

(defn add!
  "Adds a segment's :image to src at the locations specified by segment's :region
   and :mask. Or, if a value (number or vector of numbers) is specified, then
   add that number instead. Input options are
  [src segment]
  [src segment value]"
  ([src seg]
   (if (:matrix src)
     (do (cv/add! (cv/submat (:matrix src) (:region seg)) (:image seg)
                  :mask (:mask seg))
       src)
     (cv/add! (cv/submat src (:region seg)) (:image seg) :mask (:mask seg))))
  ([src seg value]
   (if (:matrix src)
     (do (cv/add! (cv/submat (:matrix src) (:region seg)) value :mask (:mask seg))
       src)
     (cv/add! (cv/submat src (:region seg)) value :mask (:mask seg)))))

(defn bitwise-and!
  "Set src to be a bitwise-and with segment's :mask, within the bounds of
   segment's :region. Input is [src segment]."
  [src seg]
  (cv/bitwise-and! (cv/submat src (:region seg)) (:mask seg)))

(defn bitwise-or!
  "Set src to be a bitwise-or with segment's :mask, within the bounds of
   segment's :region. Input is [src segment]."
  [src seg]
  (cv/bitwise-or! (cv/submat src (:region seg)) (:mask seg)))

(defn copy
  "Copy src at the locations specified by segment's :region and :mask. Any elements
   not covered by the mask will be 0 in the copy. Input is [src segment]."
  [src seg]
  (cv/copy (cv/submat (or (:matrix src) src) (:region seg)) :mask (:mask seg)))

(defn max-value
  "Returns the max value of src (a single-channel matrix), across the locations
   specified by segment's :region and :mask. Input is [src segment]."
  [src seg]
  (-> (cv/submat (or (:matrix src) src) (:region seg))
      (cv/max-value :mask (:mask seg))))

(defn mean-intensity
  "Returns the mean intensity of src, which should be a feature map, across the
   locations specified by segment's :region and :mask. Input is [src segment]."
  [src seg]
  (-> (f/submat src (:region seg)) (f/mean-intensity :mask (:mask seg))))

(defn mean-value
  "Returns the mean value (number or vector of numbers for multiple channels) of
   src, across the locations specified by segment's :region and :mask. Input is
   [src segment]."
  [src seg]
  (if (:matrix src)
    (-> (f/submat src (:region seg)) (f/mean-value :mask (:mask seg)))
    (-> (cv/submat src (:region seg)) (cv/mean-value :mask (:mask seg)))))

(defn min-value
  "Returns the min value of src (a single-channel matrix), across the locations
   specified by segment's :region and :mask. Input is [src segment]."
  [src seg]
  (-> (cv/submat (or (:matrix src) src) (:region seg))
      (cv/min-value :mask (:mask seg))))

(defn set-to
  "Sets src to segment's :image at the locations specified by the segment's
   :region and :mask. Or, if a value (number or vector of numbers), is specified,
   then set src to that value at those locations. Input options are
   [src segment]
   [src segment value]"
  ([src seg]
   (cv/copy (:image seg) :dst (cv/submat (or (:matrix src) src) (:region seg))
            :mask (:mask seg))
   src)
  ([src seg value]
   (cv/set-to (cv/submat (or (:matrix src) src) (:region seg)) value
              :mask (:mask seg))
   src))

(declare submat)
(defn subfeature-scores
  "Takes a feature-map and a segment and returns a hashmap of scores, one for
   each subfeature of the feature-map (for example, a :color feature-map's
   subfeatures would be :red, :green, :blue, and :yellow). If min-mean is provided,
   then the mean (an intermediate value in calculating a score) must be at least this
   large, or the score will be returned as 0. If min-value is provided, then any
   individual values lower than this threshold will be set to 0 before computing
   a score."
  [src-original seg & {:keys [min-mean min-value]}]
  (let [src (submat src-original seg)
        mask (:mask seg)
        area-sqrt (Math/sqrt (area seg))]
    (g/update-all (f/subfeature-maps src) f/score :mask mask :area-sqrt area-sqrt
                  :min-mean min-mean :min-value min-value)))

(defn submat
  "Takes a submat of src, using the segment's :region. Input is [src segment]."
  [src seg]
  (if (:matrix src)
    (f/submat src (:region seg))
    (cv/submat src (:region seg))))

(defn sum-elems
  "Returns of the sum of all values (number or vector of numbers, one for each channel)
   for src, across the locations specified by segment's :region and :mask. Input is
   [src segment]."
  [src seg]
  (-> (cv/submat (or (:matrix src) src) (:region seg)) (cv/copy :mask (:mask seg))
      cv/sum-elems))

(defn zeros
  "If the segment's input-size and image or mask is known, returns a blank canvas
   of size input-size and the type of image or mask."
  [seg]
  (when-let [size (input-size seg)]
    (or (some-> (:image seg) (cv/zeros :size size))
        (some-> (:mask seg) (cv/zeros :size size)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for interacting with feature maps.

(defn histogram
  "Computes a histogram over the portion of a feature map that overlaps a segment."
  [fmap segment & {:keys [bins] :or {bins 41}}]
  (f/histogram
   (-> fmap
       (update :matrix submat segment)
       (cond-> (:intensity fmap) (update :intensity submat segment)))
   :bins bins :mask (:mask segment) :divisor (area segment)))
