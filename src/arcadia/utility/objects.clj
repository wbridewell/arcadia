(ns
 ^{:doc "Functions for operating on visual object-files."}
 arcadia.utility.objects
  (:require (arcadia.utility [general :as g]
                             [descriptors :as d])
            [arcadia.vision.regions :as reg]))

;; Threshold for determining if two objects are the same size.
(def size-similarity-threshold 0.25)

;; Threshold (in radians) for indicating that two directions are different.
(def direction-change-thresh (/ Math/PI 6))

(defn within-epsilon?
  "Returns true when x is within y +/- y*p."
  [x y p]
  (and (> x (- y (* p y)))
       (< x (+ y (* p y)))))

(defn within-percentage?
  "True when x is within p percentage +/- y or vice versa."
  [x y p]
  (or (g/between? x (* y (inc p)) (* y (- 1 p)))
      (g/between? y (* x (inc p)) (* x (- 1 p)))))


(defn similar-size-areas?
  "Returns true when two areas are equal within a given percentage."
  [area1 area2 & {:keys [threshold]
                  :or {threshold size-similarity-threshold}}]
  (or (within-epsilon? area1 area2 threshold)
      (within-epsilon? area2 area1 threshold)))

(defn similar-size-regions?
  "Returns true when two areas are equal within a given percentage."
  [region1 region2 & {:keys [threshold]
                      :or {threshold size-similarity-threshold}}]
  (similar-size-areas? (reg/area region1) (reg/area region2) :threshold threshold))

(defn similar-size-contours?
  "Returns true when two contour areas are equal within a percentage."
  [obj1 obj2 & {:keys [threshold]
                :or {threshold size-similarity-threshold}}]
  (similar-size-areas?
    (:area (:arguments obj1))
    (:area (:arguments obj2))
    :threshold threshold))

;; NOTE: probably want to move toward intersecting contours instead of intersecting regions
(defn location-match?
  "Returns true when the two objects overlap and are roughly the same size."
  ([obj1 obj2 epsilon]
   (let [region1 (:region (:arguments obj1))
         region2 (:region (:arguments obj2))]
     (and region1 region2
          (= (:world obj1) (:world obj2))
          (reg/intersect? region1 region2)
          (similar-size-regions? region1 region2 epsilon))))
  ([obj1 obj2]
   (location-match? obj1 obj2 0.05)))

;; NOTE: This function takes optional arguments in pairs, as in a hash. The objects returned will then
;; be filtered such that their :arguments hash matches these optional arguments. Common options would
;; include:
;;  :tracked? true
(defn get-vstm-objects
  "Returns the objects with the specified :arguments values that are currently in vstm."
  [content & args]
  (d/filter-matches (apply d/descriptor (concat args (list :name "object" :world "vstm"))) content))

(defn get-object
  "Returns the object associated with an object location, based on their common slot values."
  [slot-or-location content]
  (cond
    (number? slot-or-location)
    (d/first-element content :name "object" :world "vstm"
                     :slot slot-or-location)
    :else
    (d/first-element content :name "object" :world "vstm"
                     :slot (-> slot-or-location :arguments :slot))))

(defn similar-size?
  "Returns true when two areas are equal within a given percentage."
  [reg1 reg2 pct-buffer]
  (let [area1 (reg/area reg1)
        area2 (reg/area reg2)]
    (or (within-epsilon? area1 area2 pct-buffer)
        (within-epsilon? area2 area1 pct-buffer))))

(defn segment-location-match?
  ([seg1 seg2]
   (segment-location-match? seg1 seg2 0.15))
  ([seg1 seg2 epsilon]
   (and (:region seg1) (:region seg2)
        (reg/intersect? (:region seg1) (:region seg2))
        (similar-size? (:region seg1) (:region seg2) epsilon))))

(defn segment-overlap? [seg1 seg2]
  (and (:region seg1) (:region seg2)
       (reg/intersect? (:region seg1) (:region seg2))))

(defn trace-segment? [segment csegs]
    (not-any? #(segment-location-match? segment %) csegs))

(defn trace-location? [location clocs]
    (not-any? #(location-match? location %) clocs))

(defn get-image-segments [content]
  (-> content (d/first-element :name "image-segmentation") :arguments :segments))

(defn get-segments-persistant [content]
  (let [segments (get-image-segments content)]
    (->> content
         (g/find-first #(= (:name %) "iconic-memory"))
         :arguments :segments
         (filter #(trace-segment? % segments))
         (concat segments))))

(defn old-slot
  "If an object is newly added to VSTM, this returns the slot that was associated
  with the object before it was added. This information is useful because that
  slot is the one that was used to track the object before it was added to VSTM,
  and it will be the one used to tracked the new VSTM object for one cycle, before
  object locator updates to tracking the new slot number assigned in VSTM."
  [object content]
  (-> (d/first-element content :name #{"visual-new-object" "visual-equality"}
                       :object object)
      :arguments :origin :arguments :slot))

(defn get-location
  "Returns the object location associated with an object, based on their common
  slot values."
  [object content]
  (when (-> object :arguments :tracked?)
    (d/first-element content :name "object-location" :world "vstm"
                     :slot (or (old-slot object content)
                               (-> object :arguments :slot)))))

(defn get-latest-locations
  "Returns a list of object locations, with slots numbers updated based on the
  latest information from VSTM."
  [content]
  (let [locations (d/filter-elements content :name "object-location")
        update (d/first-element content :name #{"visual-new-object" "visual-equality"})
        old-slot (-> update :arguments :origin :arguments :slot)
        new-slot (-> update :arguments :object :arguments :slot)
        old-loc (d/first-element locations :slot old-slot)
        new-loc (d/first-element locations :slot new-slot)]

    (if (and update (not= old-loc new-loc))
      (conj (remove #{old-loc new-loc} locations)
            (assoc-in old-loc [:arguments :slot] new-slot))
      locations)))

(defn make-unlocated-region
  "Creates a data structure describing a region based on a pre-existing region,
   but removes any location information (so we only have size information.)."
  [region]
  (dissoc region :x :y))

(defn get-group-region
  ([group-fixation] (get-group-region group-fixation 0))
  ([group-fixation d]
   (when (-> group-fixation :arguments :segments count pos?)
    (let [xs (mapv #(-> % :region :x) (:segments (:arguments group-fixation)))
          ys (mapv #(-> % :region :y) (:segments (:arguments group-fixation)))
          minX (apply min xs)
          minY (apply min ys)]
      (reg/prepare {:x minX :y minY
                    :width (+ d (- (apply max xs) minX))
                    :height (+ d (- (apply max ys) minY))})))))

(defn get-region
  "Returns the region associated with a fixation, object, or location using the location
  with the associated slot value when necessary."
  [element content]
  (cond
    (= (:name element) "object")
    (or (and (not (false? (-> element :arguments :tracked?)))
             (-> element (get-location content) :arguments :region))
        (-> element :arguments :region))

    (= (:name element) "fixation")
    (or (-> element :arguments :segment :region)
        (get-region (get-location (-> element :arguments :object) content) content))

    (reg/region? element) ;;This is already a region
    element

    (= (:name element) "group-fixation")
    (get-group-region element)

    (-> element :arguments :object)
    (get-region (-> element :arguments :object) content)

    (= (:name element) "scan")
    (reg/prepare
     {:x (- (-> element :arguments :x) (-> element :arguments :pixel-radius))
      :y (- (-> element :arguments :y) (-> element :arguments :pixel-radius))
      :width (* 2 (-> element :arguments :pixel-radius))
      :height (* 2 (-> element :arguments :pixel-radius))})

    (= (type element) java.awt.Rectangle)
    (reg/prepare element)

    (:segment (:arguments element))
    (get-region (:segment (:arguments element)) content)

    :else
    (or (-> element :arguments :region)
        (-> element :region))))

(defn get-tracked-region
  "Given an object, finds the location with the associated slot value (if available)
   and returns its region."
  [object content]
  (when (= (:name object) "object")
    (and (not (false? (-> object :arguments :tracked?)))
         (-> object (get-location content) :arguments :region))))

(defn get-slot
  "Returns the slot associated with a fixation, object, or location."
  [element]
  (or (and (number? element) element)
      (-> element :arguments :slot)
      (-> element :arguments :object :arguments :slot)))

(defn get-segment
  "Returns the segment associated with a fixation, object, or location, using the location
  with the associated slot value when necessary."
  [element content]
  (cond
    (= (:name element) "object")
    (:segment (:arguments (get-location element content)))
    ;;If necessary, treat the object itself as a segment. It has many of the same arguments.
    ;;EDIT: Actually better to return nil if we can't find a segment.  Otherwise, we expect
    ;;:region to work on an object, and it doesn't.

    (= (:name element) "fixation")
    (or (:segment (:arguments element))
        (get-segment (:object (:arguments element)) content))

    (:object (:arguments element))
    (get-segment (:object (:arguments element)) content)

    :else
    (:segment (:arguments element))))


(defn same-region?
  "True if the two objects are located at the same place."
  [element0 element1 locations]
  (reg/= (get-region element0 locations) (get-region element1 locations)))

(defn get-estimated-region
  "Given an object, translate its orginal region rectangle (from when it was last
  fixated) to its most recent location (based on the object-locator)."
  [object content]
  (let [original (:region (:arguments object))
        latest (get-region object content)
        latest-pt (and latest (reg/center latest))]
    (if (or (nil? original) (nil? latest) (= original latest))
      original
      (reg/translate-center-to original latest-pt))))

;; A component might point to an object record that is outdated because there is now
;; a new record in vstm for the same object. This function retrieves the most recently
;; added vstm object that is at the same location as this object. Note that when two
;; objects collide, they may exist at the same location. In such cases, this function
;; assumes you want the object that was more recently attended, and thus more recently
;; updated in vstm.
(defn updated-object
  "Returns the current object in VSTM that corresponds to the given object.
   Assumes that the object was produced one cycle earlier. If the object is older,
   then this function may not return the desired result."
  [object content]
  (if (= (:name object) "object")
    (let [slot (-> object :arguments :slot)]
      (cond
        (= (:world object) "vstm")
        (when (not= slot
                    (-> (d/first-element content :name "visual-new-object")
                        :arguments :object :arguments :slot))
          (d/first-element content :name "object" :world "vstm" :slot slot))

        (nil? (:world object))
        (-> (d/first-element content :name #{"visual-new-object" "visual-equality"}
                             :origin object)
            :arguments :object)))
    object))

(defn updated-slot
  "Returns the slot of the current object in VSTM that corresponds to this element.
  Assumes this element is one cycle old."
  [element content]
  (cond
    (= (:name element) "fixation")
    (-> element :arguments :object (updated-slot content))

    (= (:name element) "object")
    (-> (updated-object element content) :arguments :slot)

    :else
    nil))

(defn direction-difference
  "Returns the distance in radians between two directions."
  ([x1 y1 x2 y2]
   (direction-difference (Math/atan2 y1 x1) (Math/atan2 y2 x2)))
  ([a1 a2]
   (min (Math/abs (- a1 a2))
        (- (+ (min a1 a2) (* 2 Math/PI))
           (max a1 a2)))))

;;Are the differences above a threshold?
(defn directions-are-different?
  "Determines if the differences in direction are above the constant value of
  the direction change threshold."
  ([x1 y1 x2 y2]
   (> (direction-difference x1 y1 x2 y2) direction-change-thresh))
  ([a1 a2]
   (> (direction-difference a1 a2) direction-change-thresh))
  ([adiff]
   (> adiff direction-change-thresh)))
