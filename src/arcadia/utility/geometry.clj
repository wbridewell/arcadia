(ns
  ^{:author "Andrew Lovett"
    :doc
    "Supports working with regions of space in 2D images or other matrices.
     Regions may be points, rectangles, polylines or polygons, etc.
     Regions are represented as hash-maps that should include some combination
     of the following keys: :x, :y, :width, :height, :points, and :closed?
     Call geometry/type on a region to get a keyword describing what type it is,
     depending on which fields are defined (for example, a :rectangle type has :x,
     :y, :width, and :height).
     
     Included here are functions to provide information about a region, e.g.,
     width, height, area, center, min-x, max-x
     functions to transform a region, e.g.,
     translate, scale, crop,
     and functions to compare two regions, e.g.,
     distance, intersect?, constains?
     
     Some functions are not defined for some region types. Notably, there is minimal 
     support right now for comparison functions on polygons."}
  arcadia.utility.geometry
  (:refer-clojure :exclude [contains? = not= type])
  (:require [arcadia.utility.general :as gen])
  (:import java.awt.Rectangle org.opencv.core.Rect))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Helper fns

(defn- or-max
  "Takes zero or more values and returns the max of all non-nil values, or nil if
   all values are nil."
  [& values]
  (let [values (remove nil? values)]
    (when (first values)
      (apply max values))))

(defn- or-min
  "Takes zero or more values and returns the min of all non-nil values, or nil if
   all values are nil."
  [& values]
  (let [values (remove nil? values)]
    (when (first values)
      (apply min values))))

(defn- and-max
  "Takes zero or more values and returns the max of the values if all are non-nil,
   or else nil."
  [& values]
  (when (and (seq values) (every? some? values))
    (apply max values)))

(defn- and-min
  "Takes zero or more values and returns the min of the values if all are non-nil,
   or else nil."
  [& values]
  (when (and (seq values) (every? some? values))
    (apply min values)))

(defn- points-min-x
  "Gets the minimum x value from the points in an item's :points field."
  [{points :points}]
  (when (seq points)
    (apply min (map :x points))))

(defn- points-min-y
  "Gets the minimum y value from the points in an item's :points field."
  [{points :points}]
  (when (seq points)
    (apply min (map :y points))))

(defn- points-max-x
  "Gets the maximum x value from the points in an item's :points field."
  [{points :points}]
  (when (seq points)
    (apply max (map :x points))))

(defn- points-max-y
  "Gets the maximum y value from the points in a region's :points field."
  [{points :points}]
  (when (seq points)
    (apply max (map :y points))))

(defn- update-points
  "Update the points in a region's :points list either by applying functA 
   to each x and each y value or (if functB is provided) by applying functA 
   to each x and functB to each y."
  ([region functA]
   (update-points region functA functA))
  ([{points :points :as region} functA functB]
   (assoc region
          :points
          (map #(-> % (update :x functA) (update :y functB)) 
               points))))

(defn- single? 
  "Returns true if there's no more than one item in a sequence."
  [items]
  (empty? (rest items)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for determining the type of region

(defn positioned? 
  "Returns true if the region has :x and :y fields."
  [{x :x y :y}]
  (and x y))

(defn type
  "Returns a keyword describing the type of region" 
  [region]
  (when (map? region)
    (let [{x :x y :y width :width height :height points :points} region]
      (cond 
        (and (seq points) (:closed? region)) :polygon
        (seq points) :polyline
        (and x y width height) :rectangle
        (and x y (or width height)) :line-segment
        (and x y) :point 
        (or (and x height) (and y width)) nil ;;malformed
        (or (and x (nil? width)) (and y (nil? height))) :line
        (or x y) :line-with-width
        (and width height) nil)))) ;;This is a size, but sizes aren't geometric objects.

(def type-hierarchy
  "A type hierarchy for regions"
  (-> (make-hierarchy)
      (derive :rectangle :axis-aligned-points)
      (derive :line-segment :axis-aligned-points)
      (derive :point :axis-aligned-points)
      (derive :rectangle :axis-aligned)
      (derive :line-segment :axis-aligned)
      (derive :point :axis-aligned)
      (derive :line :axis-aligned)
      (derive :line-with-width :axis-aligned)
      (derive :line-with-width :any-line)
      (derive :line :any-line)
      (derive :polyline :any-poly)
      (derive :polygon :any-poly)
      (atom)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for getting attributes of regions that may or may not already be
;;;;;fields of the regions.

(defn width
  "Returns the :width of a region, or 1 if the region has an :x value but no width."
  [region]
  (or (:width region) (and (:x region) 1)
      (when-let [min-x (points-min-x region)]
        (inc (- (points-max-x region) min-x)))))

(defn height
  "Return the :height of a region, or 1 if the region has a :y value but no height."
  [region]
  (or (:height region) (and (:y region) 1)
      (when-let [min-y (points-min-y region)]
        (inc (- (points-max-y region) min-y)))))

(defn diff-x
  "Returns the difference between a region's min and max x values."
  [region]
  (some-> (width region) dec))

(defn diff-y
  "Returns the difference between a region's min and max y values."
  [region]
  (some-> (height region) dec))

(defn min-x
  "Returns a region's min x value."
  [region]
  (or (:x region) (points-min-x region)))

(defn min-y
  "Returns a region's max x value."
  [region]
  (or (:y region) (points-min-y region)))

(defn max-x
  "Returns a region's max x value."
  [region]
  (or (gen/when-let* [x (:x region)
                      dx (diff-x region)]
                     (+ x dx))
      (points-max-x region)))

(defn max-y
  "Returns a region's max y value."
  [region]
  (or (gen/when-let* [y (:y region)
                      dy (diff-y region)]
                     (+ y dy))
      (points-max-y region)))

(defn radius-x
  "Return the region's radius along the x dimension."
  [rect]
  (when-let [dx (diff-x rect)]
    (/ dx 2.0)))

(defn radius-y
  "Return the region's radius along the y dimension."
  [rect]
  (when-let [dy (diff-y rect)]
    (/ dy 2.0)))

(defn- compute-center-x
  "Computes a region's center x value."
  [region]
  (or (gen/when-let* [x (:x region)
                      dx (diff-x region)]
                     (int (+ x (/ dx 2))))
      (gen/when-let* [x0 (points-min-x region)
                      x1 (points-max-x region)]
                     (int (/ (+ x0 x1) 2)))))

(defn center-x
  "Returns a region's center x value."
  [region]
   (or (some-> region :center :x) (compute-center-x region)))

(defn- compute-center-y
  "Computes a region's center y value."
  [region]
  (or (gen/when-let* [y (:y region)
                      dy (diff-y region)]
                     (int (+ y (/ dy 2.0))))
      (gen/when-let* [y0 (points-min-y region)
                      y1 (points-max-y region)]
                     (int (/ (+ y0 y1) 2)))))

(defn center-y
  "Returns a region's center y value."
  [region]
  (or (some-> region :center :y) (compute-center-y region)))

(defn- compute-center
  "Computes a region's {:x x :y y} center."
  [region]
  (let [x (compute-center-x region)
        y (compute-center-y region)]
    (cond-> nil
            x (assoc :x x)
            y (assoc :y y))))

(defn center
  "Returns a region's {:x x :y y} center."
  [region]
  (or (:center region) (compute-center region)))

(defn- compute-radius
  "Computes a region's average radius along the x and y dimensions, or whichever
   dimensions are defined."
  [region]
  (let [rx (radius-x region)
        ry (radius-y region)]
    (or (and rx ry (/ (+ rx ry) 2.0)) rx ry)))

(defn radius
  "Returns a region's average radius along the x and y dimensions, or whichever
   dimensions are defined."
  [region]
  (or (:radius region) (compute-radius region)))

(defn- compute-area
  "Returns a region's area.
   Currently not implemented for regions with :points fields."
  [region]
  (when (:points region)
    (throw (Exception. "Area computation is not implemented for regions with :points field.")))
  (gen/when-let* [w (:width region)
                  h (:height region)]
    (* w h)))

(defn area
  "Returns a region's area.
   Currently not implemented for regions with :points fields."
  [region]
  (or (:area region) (compute-area region)))

(defn aspect-ratio
  "Returns a region's aspect ratio (/ width height)."
  [{width :width height :height :as region}]
  (gen/when-let* [width (width region)
                  height (height region)]
                 (* (/ width height) 1.0)))

(defn points 
  "Returns an ordered list of points describing a region."
  [{points :points x :x y :y width :width height :height closed? :closed?}]
  (cond 
    (and points closed?) (cons (last points) points)
    points points
    
    (and x y width height)
    [{:x x :y y} {:x (+ x (dec width)) :y y} 
     {:x (+ x (dec width)) :y (+ y (dec height))} {:x x :y (+ y (dec height))}
     {:x x :y y}] ;;Closed shape, so make the start and end point the same
    
    (and x y width)
    [{:x x :y y} {:x (+ x (dec width)) :y y}]
    (and x y height)
    [{:x x :y y} {:x x :y (+ y (dec height))}]
    (and x y)
    [{:x x :y y}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for caching useful region information

(defmulti prepare
  "Updates a region to include potentially helpful information, like its :center.
   Also converts any java rectangle object into the approprate format for regions
   (an {:x :y :width :height} hashmap)."
  (fn [region] (clojure.core/type region)))

(defmethod prepare clojure.lang.PersistentArrayMap
  [region]
  (assoc region :center (center region) :radius (radius region) :area (area region)))

(defmethod prepare Rect
  [^Rect region]
  (prepare {:x (.x region) :y (.y region) :width (.width region) :height (.height region)}))

(defmethod prepare Rectangle
  [^Rectangle region]
  (prepare {:x (.x region) :y (.y region) :width (.width region) :height (.height region)}))

(defn unprepare
  "Remove cached values from region."
  [region]
  (dissoc region :area :center :radius))

(defn update-center
  "Returns an updated version of region in which the center has been recomputed."
  [region]
  (assoc region :center (compute-center region)))

(defn update-area
  "Returns an updated version of region in which the area has been recomputed."
  [region]
  (assoc region :area (compute-area region)))

(defn update-radius
  "Returns an updated version of region in which the radius has been recomputed."
  [region]
  (assoc region :radius (compute-radius region)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for transforming regions

(defn translate
  "Moves the region the specified {:x x :y y} amount (x and y need not both be
   defined, in either the amount or the region)."
  [{x :x y :y points :points :as region} {dx :x dy :y}]
  (cond-> region
    (and x dx) (assoc :x (+ x dx))
    (and y dy) (assoc :y (+ y dy))
    points (update-points #(+ % (or dx 0)) #(+ % (or dy 0)))
    (:center region) update-center))

(defn translate-to
  "Moves the region's upper left corner to the specified {:x x :y y} location
   (x and y need not both be defined, in either the location or the region)."
  [{x :x y :y points :points :as region} {lx :x ly :y}]
  (cond-> region
    (and x lx) (assoc :x lx)
    (and y ly) (assoc :y ly)
    points (update-points #(+ % (if lx
                                  (- lx (min-x region))
                                  0))
                          #(+ % (if ly
                                  (- ly (min-y region))
                                  0)))
    (:center region) update-center))

(defn translate-center-to
  "Moves the region's center to the specified {:x x :y y} location
   (x and y need not both be defined, in either the location or the region)."
  [{x :x y :y points :points :as region} {lx :x ly :y}]
  (cond-> region
    (and x lx) (update :x #(+ % (- lx (center-x region))))
    (and y ly) (update :y #(+ % (- ly (center-y region))))
    points (update-points #(+ % (if lx
                                   (- lx (center-x region))
                                  0))
                          #(+ % (if ly
                                  (- ly (center-y region))
                                  0)))
    (:center region) update-center))

(defn translate-by-factor 
  "Moves the region such that the x and y values of its new center are equal to the 
   x and y values of its old center multiplied by the specified factor" 
  [region factor] 
  (let [{x :x y :y} (center region)]
    (translate-center-to region {:x (int (* x factor)) :y (int (* y factor))})))

(defn scale
  "Scales the region by the specified factor, while keeping it centered at the same
   point."
  [{x :x y :y width :width height :height points :points :as region} scale-factor]
  (let [center-x (center-x region)
        center-y (center-y region)]
    (cond-> region
      width (assoc :width (-> width (* scale-factor) int (max 1)))
      height (assoc :height (-> height (* scale-factor) int (max 1)))
      x (assoc :x (-> x (- center-x) (* scale-factor) (+ center-x) int))
      y (assoc :y (-> y (- center-y) (* scale-factor) (+ center-y) int))
      points (update-points #(-> % (- center-x) (* scale-factor) (+ center-x) int)
                            #(-> % (- center-y) (* scale-factor) (+ center-y) int))
      (:center region) update-center
      (:area region) update-area
      (:radius region) update-radius)))

(defn scale-world
  "Scales the region's size and location by the specified factor (or just its location 
   if no width and height are provided, as in a point). Optionally, there can be two factors,
   one for x and the other for y."
  ([region scale-factor]
   (scale-world region scale-factor scale-factor))
  ([{x :x y :y width :width height :height points :points :as region} scale-factor-x scale-factor-y]
   (cond-> region
     x (assoc :x (int (* x scale-factor-x)))
     y (assoc :y (int (* y scale-factor-y)))
     width (assoc :width (-> width (* scale-factor-x) int (max 1)))
     height (assoc :height (-> height (* scale-factor-y) int (max 1)))
     points (update-points #(* % scale-factor-x) #(* % scale-factor-y))
     (:center region) update-center
     (:area region) update-area
     (:radius region) update-radius)))

(defn crop
  "Crops a region so that it fits on an image of size {:width w :height h}.
   Currently not implemented for regions with :points fields."
  [{reg-min-x :x reg-min-y :y reg-w :width reg-h :height :as region}
   {image-width :width image-height :height}]
  (when (:points region)
    (throw (Exception. "Crop is not implemented for regions with :points field.")))
  (let [reg-max-x (dec (+ reg-min-x reg-w))
        reg-max-y (dec (+ reg-min-y reg-h))
        max-x (dec image-width)
        max-y (dec image-height)
        new-min-x (max reg-min-x 0)
        new-min-y (max reg-min-y 0)
        new-max-x (min reg-max-x (dec image-width))
        new-max-y (min reg-max-y (dec image-height))]
    (when (and (> new-max-x new-min-x) (> new-max-y new-min-y))
      (cond->
       (assoc region :width (inc (- new-max-x new-min-x))
              :height (inc (- new-max-y new-min-y))
              :x new-min-x
              :y new-min-y
        (:area region) update-area
        (:center region) update-center
        (:radius region) update-radius)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Support functions for geometric computations

(defn- pt-pt-sq-dist 
  "Returns the square distance between two points"
  [x0 y0 x1 y1]
  (+ (Math/pow (- x0 x1) 2) (Math/pow (- y0 y1) 2)))

;;https://stackoverflow.com/questions/849211/shortest-distance-between-a-point-and-a-line-segment
(defn- pt-lineseg-sq-dist
  "Returns the square distance between a point and a line segment"
  [x y x0 y0 x1 y1]
  (let [seg-sq-length (pt-pt-sq-dist x0 y0 x1 y1)]
    (if (zero? seg-sq-length)
      (pt-pt-sq-dist x y x0 y0)
      (let [t (/ (+ (* (- x x0) (- x1 x0))
                    (* (- y y0) (- y1 y0)))
                 seg-sq-length)
            t (-> t (max 0) (min 1))]
        (pt-pt-sq-dist x y 
                       (+ x0 (* t (- x1 x0)))
                       (+ y0 (* t (- y1 y0))))))))

;;Algorithm from Graphic Gems II and designed by Mukesh Prasad.
;;Later used in CogSketch
(defn- lineseg-lineseg-intersect?
  "Returns true if two linesegments intersect"
  [l0_x0 l0_y0 l0_x1 l0_y1 l1_x0 l1_y0 l1_x1 l1_y1]
  (let [a0 (- l0_y1 l0_y0) ;;line equation for comp1 is a1*x + b1*y + c1
        b0 (- l0_x0 l0_x1)
        c0 (- (* l0_x1 l0_y0) (* l0_x0 l0_y1))
        a1 (- l1_y1 l1_y0) ;;line equation for comp2 is a2*x + b2*y + c2
        b1 (- l1_x0 l1_x1)
        c1 (- (* l1_x1 l1_y0) (* l1_x0 l1_y1))
        r1 (+ (* a1 l0_x0) (* b1 l0_y0) c1)
        r2 (+ (* a1 l0_x1) (* b1 l0_y1) c1)
        r3 (+ (* a0 l1_x0) (* b0 l1_y0) c0)
        r4 (+ (* a0 l1_x1) (* b0 l1_y1) c0)]
    (not (or (and (not (zero? r3)) (not (zero? r4)) (clojure.core/= (pos? r3) (pos? r4)))
             (and (not (zero? r1)) (not (zero? r2)) (clojure.core/= (pos? r1) (pos? r2)))
             (and (zero? a0) (zero? b0)) ;;lineseg is just a point
             (and (zero? a1) (zero? b1))))))

(defn- points->lineseg 
  "Converts two points into an [x0 y0 x1 y1] line segment"
  [{x0 :x y0 :y} {x1 :x y1 :y}]
  [x0 y0 x1 y1])

(defn- points->linesegs 
  "Converts a list of points into a list of [x0 y0 x1 y1] line segments"
  [pts]
  (map points->lineseg pts (rest pts)))

(defn- points-points-intersect?
  "Returns true if the polylines described by two lists of points intersect"
  [pts0 pts1] 
  (let [linesegs1 (points->linesegs pts1)]
    (cond
      (and (single? pts0) (single? pts1)) ;;Each list is just one point
      (clojure.core/= (-> pts0 first (select-keys [:x :y])) (-> pts1 first (select-keys [:x :y])))

      (single? pts0) ;;pts0 is just one point
      (some #(zero? (apply pt-lineseg-sq-dist (concat [(:x (first pts0)) (:y (first pts0))] %))) linesegs1)

      (single? pts1) ;;pts1 is just one point
      (points-points-intersect? pts1 pts0)

      :else
      (loop [segs0 (map points->lineseg pts0 (rest pts0))
             segs1 linesegs1]
        (cond
          (empty? segs0)
          nil

          (empty? segs1)
          (recur (rest segs0) linesegs1)

          :else
          (if (apply lineseg-lineseg-intersect? (concat (first segs0) (first segs1)))
            true
            (recur segs0 (rest segs1))))))))

(defn- pt-linesegs-sq-dist 
  "Returns the square distance between a pointed defined by x y and a list of segments, 
   where each segment was created with points->lineseg"
  [x y segs]
  (->> segs
       (map #(apply pt-lineseg-sq-dist (concat [x y] %)))
       (apply or-min)))

(defn- points-points-sq-dist
  "Returns the square distance between two polylines described by lists of points"
  [pts0 pts1]
  (cond 
    (points-points-intersect? pts0 pts1) 0.0
    
    (and (single? pts0) (single? pts1)) 
    (pt-pt-sq-dist (:x (first pts0)) (:y (first pts0)) (:x (first pts1)) (:y (first pts1)))
    
    :else
    (let [segs0 (map points->lineseg pts0 (rest pts0))
          segs1 (map points->lineseg pts1 (rest pts1))]
      (or-min
       (->> pts0 (map #(pt-linesegs-sq-dist (:x %) (:y %) segs1)) (apply or-min))
       (->> pts1 (map #(pt-linesegs-sq-dist (:x %) (:y %) segs0)) (apply or-min))))))

(defn- polyline-line-dist
  "Returns the distance between a polyline and a line. The polyline will be of type
   :any-poly The line will be of type :any-line"
    [r0 r1]
    (if (:x r1)
      (let [min0 (min-x r0)
            min1 (min-x r1)
            max0 (max-x r0)
            max1 (max-x r1)]
        (cond
          (< max0 min1)  (- min1 max0)
          (> min0 max1) (- min0 max1)
          :else 0))

      (let [min0 (min-y r0)
            min1 (min-y r1)
            max0 (max-y r0)
            max1 (max-y r1)]
        (cond
          (< max0 min1) (- min1 max0)
          (> min0 max1) (- min0 max1)
          :else 0))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for comparing regions

(defmulti contains? "Returns true if region r1 contains (or is equal to) region r2"
  (fn [r0 r1] [(type r0) (type r1)])
  :hierarchy type-hierarchy)

(defmethod contains? [:polyline :polyline] 
  [r0 r1]
  nil)

(defmethod contains? [:polyline :axis-aligned]
  [r0 r1]
  nil)

(defmethod contains? [:axis-aligned :polyline]
  [r0 r1]
  (every? #(contains? r0 %) (:points r1)))

(defmethod contains? [:axis-aligned :polygon]
  [r0 r1]
  (every? #(contains? r0 %) (:points r1)))

(defmethod contains? [:axis-aligned :axis-aligned]
  [r0 r1]
  (let [min-x0 (:x r0)
        min-x1 (:x r1)
        min-y0 (:y r0)
        min-y1 (:y r1)
        max-x0 (max-x r0)
        max-x1 (max-x r1)
        max-y0 (max-y r0)
        max-y1 (max-y r1)]
    (and (or (nil? min-x0) (and min-x1 (<= min-x0 min-x1) (>= max-x0 max-x1)))
         (or (nil? min-y0) (and min-y1 (<= min-y0 min-y1) (>= max-y0 max-y1))))))



(defmulti sq-distance "Returns the square of the distance between two regions."
  (fn [r0 r1] [(type r0) (type r1)])
  :hierarchy type-hierarchy)

(defmethod sq-distance [:axis-aligned :axis-aligned]
  [r0 r1]
  (let [dx (gen/when-let* [x0 (min-x r0)
                           X0 (max-x r0)
                           x1 (min-x r1)
                           X1 (max-x r1)]
             (max (- x0 X1) (- x1 X0) 0.0))
        dy (gen/when-let* [y0 (min-y r0)
                           Y0 (max-y r0)
                           y1 (min-y r1)
                           Y1 (max-y r1)]
             (max (- y0 Y1) (- y1 Y0) 0.0))]
    (if (and dx dy (pos? dx) (pos? dy))
      (+ (* dx dx) (* dy dy))
      (or-max (some-> (or-max dx dy) (Math/pow 2))
              0.0))))

(defmethod sq-distance [:axis-aligned :any-poly]
  [r0 r1]
  (sq-distance r1 r0))

(defmethod sq-distance [:polyline :polyline]
  [{pts0 :points} {pts1 :points}]
  (if (points-points-intersect? pts0 pts1)
    0
    (points-points-sq-dist pts0 pts1)))

(defmethod sq-distance [:polyline :axis-aligned-points]
  [{pts0 :points :as r0} r1]
  (let [pts1 (points r1)]
    (cond 
      (contains? r1 r0) 0
      (points-points-intersect? pts0 pts1) 0
      :else (points-points-sq-dist pts0 pts1))))

(defmethod sq-distance [:any-poly :any-line]
  [r0 r1]
  (Math/pow (polyline-line-dist r0 r1) 2))



(defmulti distance 
  "Returns the distance between two regions."
  (fn [r0 r1] [(type r0) (type r1)])
  :hierarchy type-hierarchy)

(defmethod distance [:axis-aligned :axis-aligned]
  [r0 r1]
  (let [dx (gen/when-let* [x0 (min-x r0)
                           X0 (max-x r0)
                           x1 (min-x r1)
                           X1 (max-x r1)]
                          (max (- x0 X1) (- x1 X0) 0.0))
        dy (gen/when-let* [y0 (min-y r0)
                           Y0 (max-y r0)
                           y1 (min-y r1)
                           Y1 (max-y r1)]
                          (max (- y0 Y1) (- y1 Y0) 0.0))]
    (if (and dx dy (pos? dx) (pos? dy))
      (Math/sqrt (+ (* dx dx) (* dy dy)))
      (or-max dx dy 0.0))))

(defmethod distance [:any-line :any-poly]
  [r0 r1]
  (polyline-line-dist r1 r0))

(defmethod distance [:any-poly :any-line]
  [r0 r1]
  (polyline-line-dist r0 r1))

(defmethod distance :default
  [r0 r1]
  (Math/sqrt (sq-distance r0 r1)))



(defn =
  "Returns true if the regions r0 and r1 define the same space."
  [r0 r1]
  (clojure.core/= (select-keys r0 [:x :y :width :height :points :closed?])
                  (select-keys r1 [:x :y :width :height :points :closed?])))

(defn not=
  "Returns true if the regions r0 and r1 define the same space."
  [r0 r1]
  (clojure.core/not= (select-keys r0 [:x :y :width :height :points :closed?])
                     (select-keys r1 [:x :y :width :height :points :closed?])))



(defmulti intersect? "Returns true if the regions intersect."
  (fn [r0 r1] [(type r0) (type r1)])
  :hierarchy type-hierarchy)

(defmethod intersect? [:polyline :polyline]
  [{pts0 :points} {pts1 :points}]
  (points-points-intersect? pts0 pts1))

(defmethod intersect? [:axis-aligned :any-poly]
  [r0 r1]
  (intersect? r1 r0))

(defmethod intersect? [:polyline :axis-aligned-points]
  [{pts0 :points :as r0} r1]
  (or (contains? r1 r0) (points-points-intersect? pts0 (points r1))))

(defmethod intersect? [:any-poly :any-line]
  [r0 r1]
  (zero? (polyline-line-dist r0 r1)))

(defmethod intersect? [:axis-aligned :axis-aligned]
  [r0 r1]
  (and
   (not (gen/when-let* [x0 (min-x r0)
                        X0 (max-x r0)
                        x1 (min-x r1)
                        X1 (max-x r1)]
          (or (< X0 x1) (< X1 x0))))
   (not (gen/when-let* [y0 (min-y r0)
                        Y0 (max-y r0)
                        y1 (min-y r1)
                        Y1 (max-y r1)]
          (or (< Y0 y1) (< Y1 y0))))))



(defn intersection
  "Returns a region describing the intersection of one or more regions.
   Not defined for regions with a :points field."
  [& regions]
  (let [min-x (apply or-max (map :x regions))
        max-x (apply or-min (map max-x regions))
        min-y (apply or-max (map :y regions))
        max-y (apply or-min (map max-y regions))]
    (when (and (or (nil? min-x) (nil? max-x) (<= min-x max-x))
               (or (nil? min-y) (nil? max-y) (<= min-y max-y)))
      {:x min-x :y min-y
       :width (or (and min-x max-x (inc (- max-x min-x)))
                  (apply or-min (map width regions)))
       :height (or (and min-y max-y (inc (- max-y min-y)))
                   (apply or-min (map height regions)))})))

(defn union
  "Returns a region describing the intersection of one or more regions.
   Not defined for regions with a :points field."
  [& regions]
  (let [min-x (apply and-min (map :x regions))
        max-x (apply and-max (map max-x regions))
        min-y (apply and-min (map :y regions))
        max-y (apply and-max (map max-y regions))]
    {:x min-x :y min-y
     :width (or (and min-x max-x (inc (- max-x min-x)))
                (apply and-max (map width regions)))
     :height (or (and min-y max-y (inc (- max-y min-y)))
                 (apply and-max (map height regions)))}))

; For testing in the repl...
; (defn rf [] (refresh) (ns-unalias *ns* 'geo) (require '[arcadia.utility.geometry :as geo]))
