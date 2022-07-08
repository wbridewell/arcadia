(ns
  ^{:doc
    "Provides support for working with regions. Segments represent locations of
     interest in a 2D plane. They are represented as hash-maps that include
     some combination of :x, :y, :width, and :height, as well as potentially other
     information."}
  arcadia.vision.regions
  (:refer-clojure :exclude [contains? = not=])
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for determining the type of region

(defn point?
  "Returns true if region is a 2D point without width or height."
  [region]
  (if (and (:x region) (:y region) (nil? (:width region)) (nil? (:height region)))
    true false))

(defn rect?
  "Returns true if region is a 2D rectangle with width and height."
  [region]
  (if (and (:width region) (:height region))
    true false))

(defn positioned?
  "Returns true if region has an :x and :y location."
  [region]
  (if (and (:x region) (:y region))
    true false))

(defn positioned-rect?
  "Returns true if region has :x, :y, :width, and :height."
  [region]
  (if (and (:x region) (:y region) (:width region) (:height region))
    true false))

(defn region?
  "Returns true if this item is some kind of region."
  [region]
  (or (:x region) (:y region) (and (:width region) (:height region))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for getting attributes of regions that may or may not already be
;;;;;fields of the regions.

(defn width
  "Returns the :width of a region, or 1 if the region has an :x value but no width."
  [region]
  (or (:width region) (and (:x region) 1)))

(defn height
  "Return the :height of a region, or 1 if the region has a :y value but no height."
  [region]
  (or (:height region) (and (:y region) 1)))

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
  (:x region))

(defn min-y
  "Returns a region's max x value."
  [region]
  (:y region))

(defn max-x
  "Returns a region's max x value."
  [region]
  (gen/when-let* [x (:x region)
                  dx (diff-x region)]
   (+ x dx)))

(defn max-y
  "Returns a region's max y value."
  [region]
  (gen/when-let* [y (:y region)
                  dy (diff-y region)]
    (+ y dy)))

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
  (gen/when-let* [x (:x region)
                  dx (diff-x region)]
                 (int (+ x (/ dx 2.0)))))

(defn center-x
  "Returns a region's center x value."
  [region]
   (or (some-> region :center :x) (compute-center-x region)))

(defn- compute-center-y
  "Computes a region's center y value."
  [region]
  (gen/when-let* [y (:y region)
                  dy (diff-y region)]
                 (int (+ y (/ dy 2.0)))))

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
  "Returns a region's area."
  [region]
  (gen/when-let* [w (:width region)
                  h (:height region)]
    (* w h)))

(defn area
  "Returns a region's area."
  [region]
  (or (:area region) (compute-area region)))

(defn aspect-ratio
  "Return's a region's aspect ratio (/ width height)."
  [{width :width height :height :as region}]
  (when (and  width height)
    (* (/ width height) 1.0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for caching useful region information

(defmulti prepare
  "Updates a region to include potentially helpful information, like its :center.
   Also converts any java rectangle object into the approprate format for regions
   (an {:x :y :width :height} hashmap)."
  (fn [region] (type region)))

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
  [{x :x y :y :as region} {dx :x dy :y}]
  (cond-> region
          (and x dx) (assoc :x (+ x dx))
          (and y dy) (assoc :y (+ y dy))
          (:center region) update-center))

(defn translate-to
  "Moves the region's upper left corner to the specified {:x x :y y} location
   (x and y need not both be defined, in either the location or the region)."
  [{x :x y :y :as region} {lx :x ly :y}]
  (cond-> (assoc region :x (or lx x) :y (or ly y))
          (:center region) update-center))

(defn translate-center-to
  "Moves the region's center to the specified {:x x :y y} location
   (x and y need not both be defined, in either the location or the region)."
  [region {lx :x ly :y}]
  (let [radx (radius-x region)
        rady (radius-y region)]
    (cond-> region
            (and radx lx) (assoc :x (int (- lx radx)))
            (and rady ly) (assoc :y (int (- ly rady)))
            (:center region) update-center)))

(defn scale
  "Scales the region by the specified factor, while keeping it centered at the same
   point."
  [region scale-factor]
  (let [new-w (some-> (width region) (* scale-factor))
        new-h (some-> (height region) (* scale-factor))]
    (cond->
     (assoc region
            :width (some-> new-w int) :height (some-> new-h int)
            :x (some-> (center-x region) (- (/ (dec new-w) 2.0)) int)
            :y (some-> (center-y region) (- (/ (dec new-h) 2.0)) int))
     (:area region) update-area
     (:radius region) update-radius)))

(defn crop
  "Crops a region so that it fits on an image of size [w h]."
  [{reg-min-x :x reg-min-y :y reg-w :width reg-h :height :as region}
   [image-width image-height]]
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
              :y new-min-y)
       (:area region) update-area
       (:center region) update-center
       (:radius region) update-radius))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;Functions for comparing regions

(defn distance
  "Returns the distance between two regions."
  [r1 r2]
  (let [dx (gen/when-let* [x1 (min-x r1)
                           X1 (max-x r1)
                           x2 (min-x r2)
                           X2 (max-x r2)]
             (max (- x1 X2) (- x2 X1) 0.0))
        dy (gen/when-let* [y1 (min-y r1)
                           Y1 (max-y r1)
                           y2 (min-y r2)
                           Y2 (max-y r2)]
             (max (- y1 Y2) (- y2 Y1) 0.0))]
    (if (and dx dy (pos? dx) (pos? dy))
      (Math/sqrt (+ (* dx dx) (* dy dy)))
      (or-max (or-max dx dy) 0.0))))

(defn sq-distance
  "Returns the square of the distance between two regions."
  [r1 r2]
  (let [dx (gen/when-let* [x1 (min-x r1)
                           X1 (max-x r1)
                           x2 (min-x r2)
                           X2 (max-x r2)]
             (max (- x1 X2) (- x2 X1) 0.0))
        dy (gen/when-let* [y1 (min-y r1)
                           Y1 (max-y r1)
                           y2 (min-y r2)
                           Y2 (max-y r2)]
             (max (- y1 Y2) (- y2 Y1) 0.0))]
    (if (and dx dy (pos? dx) (pos? dy))
      (+ (* dx dx) (* dy dy))
      (or-max (some-> (or-max dx dy) (Math/pow 2))
              0.0))))

(defn contains?
  "Returns true if region r1 contains (or is equal to) region r2."
  [r1 r2]
  (let [min-x1 (:x r1)
        min-x2 (:x r2)
        min-y1 (:y r1)
        min-y2 (:y r2)
        max-x1 (max-x r1)
        max-x2 (max-x r2)
        max-y1 (max-y r1)
        max-y2 (max-y r2)]
    (and (or (nil? min-x1) (and min-x2 (<= min-x1 min-x2) (>= max-x1 max-x2)))
         (or (nil? min-y1) (and min-y2 (<= min-y1 min-y2) (>= max-y1 max-y2))))))

(defn =
  "Returns true if the regions r1 and r2 define the same space."
  [r1 r2]
  (clojure.core/= (select-keys r1 [:x :y :width :height])
                  (select-keys r2 [:x :y :width :height])))

(defn not=
  "Returns true if the regions r1 and r2 define the same space."
  [r1 r2]
  (clojure.core/not= (select-keys r1 [:x :y :width :height])
                     (select-keys r2 [:x :y :width :height])))

(defn intersect?
  "Returns true if two regions intersect."
  [r1 r2]
  (and
   (not (gen/when-let* [x1 (min-x r1)
                        X1 (max-x r1)
                        x2 (min-x r2)
                        X2 (max-x r2)]
          (or (< X1 x2) (< X2 x1))))
   (not (gen/when-let* [y1 (min-y r1)
                        Y1 (max-y r1)
                        y2 (min-y r2)
                        Y2 (max-y r2)]
          (or (< Y1 y2) (< Y2 y1))))))

(defn intersection
  "Returns a region describing the intersection of one or more regions."
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
  "Returns a region describing the union of one or more regions."
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
