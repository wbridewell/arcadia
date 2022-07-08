(ns arcadia.component.object-locator
  "This component implements an approach to tracking objects that are stored
  in visual short-term memory (VSTM). The implementation is based on ideas
  from Zenon Pylyshyn's work regarding \"fingers of instantion\" (FINSTs),
  which serve as mental pointers to an object in the visual field. These
  pointers are updated continuously and preattentively for any object that
  is stored in VSTM. Importantly, this provides a major source for determining
  object identity over time.

  The maintenance of FINSTs is hypothesized to be related to the process that
  causes apparant motion. In
     Dawson, M. R. (1991). The how and why of what went where in apparent
     motion: modeling solutions to the motion correspondence problem.
     Psychological Review, 98(4), 569–603.
     http://doi.org/10.1037/0033-295X.98.4.569
  the following information is used to solve the motion correspondence problem.
    (1) minimize distance travelled (nearest neighbor)
    (2) minimize the relative velocity of objects
    (3) object didn't fuse or break apart (allowed, but not preferred)

  More recent literature suggests that neuronal activation that creates
  center-surround suppression may be involved in tracking object positions. This
  component merges those ideas with the FINST and nearest-neighbor approaches
  to maintain object identity over time.

  The implementation of center-surround suppression assumes all objects are
  circles. Optionally, this approach can be turned off, in which the component
  will fall back to a nearest-neighbor approach. The key argument is use-hats?
  (center-surround suppression is implemented via Mexican hats).

  Focus Responsive
    * fixation

  When a fixation request is in focus, find out where the corresponding object
  is stored in VSTM or grab a new slot.

  Default Behavior
  For each object in VSTM determine which proto-object from the set of
  available image segments corresponds to that object, update the location,
  and draw an object-location map that shows where the tracked objects are
  located and how their suppressive fields interact.

  Produces
   * object-location
   * object-location-map"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            (arcadia.utility [objects :as obj]
                             [object-location-map :as olm]
                             [gaze :as gaze] [general :as g]
                             [descriptors :as d])
            [arcadia.vision.regions :as reg]
            [arcadia.sensor.stable-viewpoint :as sensor]
            [clojure.core.matrix]
            [clojure.set :refer [difference]])
  (:import java.util.Random))

(def ^:private old-mexhats
  "Cache of mexican hats that can be optionally used instead of remaking them from scratch."
  (atom {}))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)
(def ^:parameter segmentation-type "the name of the segmentation element to use on each cycle" "image-segmentation")
(def ^:parameter use-hats? "use mexican hats" false)
(def ^:parameter use-old-hats?
  "use cached hats instead of recomputing when we start this component"
  false)
(def ^:parameter noise-center "center of Gaussian used to compute noise" 0.2)
(def ^:parameter noise-width "std dev of Gaussian used to compute noise" 0.15)
(def ^:parameter hat-radius "radius of the mexhat image" 240)
(def ^:parameter hat-k "multiplier for the positive region's amplitude" 1.0)
(def ^:parameter hat-neg-k "multiplier for the negative region's amplitude" 1.0)
(def ^:parameter hat-neg-l "addendum for the negative region" 0)
(def ^:parameter small-hat-k "relative amplitude for a smaller mexhat on all objects" 0)
(def ^:parameter num-hats "number of different scales for mexhats" 5)
(def ^:parameter width-thresh "threshold for determining that two widths are different" 0.3)
(def ^:parameter ar-thresh "threshold for determining that two aspect ratios are different" 0.2)

(def ^:parameter max-divisor "max divisor for downscaling mexican hats" 10)
(def ^:parameter min-w "min enhance region radius for downscaling mexican hats" 5)

;; Fixed suppressive cost applied to the entire visual field for each
;; target. Not applied to the positive portion of a target's own hat.
;; some values used previously include: 0, 0.04, 0.07
(def ^:parameter target-cost "fixed amount subtracted globally for each tracked object" 0)
(def ^:parameter hat-l "addendum for the positive region" 0)

(def ^:parameter hat-pos-radius-multi "size of the positive region's radius relative to the object" 1.5)
(def ^:parameter pos-bias-radius-multi "size of small bias at an object's extrapolated position" 1)
(def ^:parameter pos-bias-strength-multi "relative strength of the bias" 2.5)

(def ^:parameter center-fn "function used to get a segment's center"
  #(-> % :region reg/center))

(def ^:parameter radius-fn "function used to get a segment's radius"
  #(-> % :region reg/radius))

(def ^:parameter sq-distance-fn "function used to compute the sq-distance between points"
  reg/sq-distance)

(def ^:parameter max-distance "If mex-hats are not used, this is the maximum distance
 allowed between a region on one cycle and a region on the following cycle, measuring
 between the centers of the regions." nil)

(def ^:parameter max-normed-distance "This is similar to max-distance, but the distance
 is normalized by dividing it by the radius of the previous region." nil)

(def ^:parameter prefer-1-to-1? "If this is true and use-hats? is false, then
 this component will find 1-to-1 mappings between regions on the previous cycle and
 regions on the current cycle whenever possible, allowing two regions on the previous
 cycle to map to one on the current cycle only when there are no other mappings
 available." false)

(def scores
  "List of scores that can be used for gathering statistics."
  (atom ()))

(defn reset-mexhats
  "Reset the cache of mexican hats used by the object locator."
  []
  (reset! old-mexhats {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Simple helper fns

(defn- point-avg
  "Computes the point directly between pt0 and pt1."
  [{x0 :x y0 :y} {x1 :x y1 :y}]
  {:x (/ (+ x0 x1) 2) :y (/ (+ y0 y1) 2)})

(defn- get-distance-to-segment
  "Get the distance from a segment to a location's center. If the location includes
   a :bias, take the average of the center and the bias."
  [{region :region bias :bias :as location} segment center-fn sq-distance-fn]
  (let [center (if bias
                 (point-avg (reg/center region) (reg/center bias))
                 (reg/center region))]
    (some-> segment center-fn (sq-distance-fn center))))

(defn- get-nearest-segment
  "Get the nearest segment to a location's center. If the location includes a
   :bias, take the average of the center and the bias."
  [{region :region bias :bias :as location}
   segments center-fn sq-distance-fn max-distance max-normed-distance]
  (let [center (if bias
                 (point-avg (reg/center region) (reg/center bias))
                 (reg/center region))
        [segment sq-dist]
        (first (sort-by second <
                        (map #(vector % (sq-distance-fn center (center-fn %)))
                             segments)))]
    (when (and (number? sq-dist)
               (or (nil? max-distance)
                   (< (Math/sqrt sq-dist) max-distance))
               (or (nil? max-normed-distance)
                   (< (/ (Math/sqrt sq-dist) (reg/radius region)) max-normed-distance)))
      segment)))

(defn- remove-redundant-regions
  "The input list can contain both simple regions and lists of the form
  (region expected-region). This function removes any simple regions if there
  is also a list of the form (region expected-region) whose first element is
  equal to them."
  [regions]
  (filter (fn [x]
            (empty? (filter #(and (seq? %) (= (first %) x))
                            regions)))
          regions))

(defn- get-untracked-regions
  "Removes the tracked regions from the list of all regions."
  [all-regions tracked-regions]
  (seq (difference (set all-regions)
                   (set (map #(if (seq? %) (first %) %) tracked-regions)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for setting up the region map, which maps VSTM slots to enhanced
;; regions.

(defn- make-location-hash
  "Maps slots in VSTM to locations in the location-list. Performs the following
  updates to the location-list from last cycle:
   1) If VSTM indicates that an object has been newly added at a particular slot
      number, find the location used to track that object before it was added to VSTM,
      and make sure that location is assigned to the particular slot number.
   2) Drop the locations for any VSTM objects that are no longer tracked.
   3) Add a new location whose slot is :unallocated if there is fixation to a new
   region (not to an existing object) in the focus."
  [focus content locations params]
  (let [objects (obj/get-vstm-objects content)
        hmap (-> (zipmap (map #(-> % :arguments :slot) locations)
                         (map #(-> % :arguments) locations))
                 (g/filter-keys #(or (and (= % :unallocated)
                                          (d/element-matches? focus :slot :unallocated))
                                     (-> (obj/get-object % objects) :arguments :tracked?)))
                 (g/filter-vals :region))]
    (if (and (d/element-matches? focus :name "fixation" :object nil? :segment some?)
             (not-any? #{(-> focus :arguments :segment :region)} (map :region (vals hmap))))
      (let [segment (-> focus :arguments :segment)]
        (assoc hmap :unallocated
               {:region (:region segment)}))
      hmap)))

(defn- update-location-with-bias
  "Updates the region map to reflect extrapolations from a maintenance
  fixation. The input should be a map of slots to locations, a maintenance fixation,
  and the content and parameters."
  [slot->location fixation content params]
  ;; if fixation has no associated region then we're done.
  (let [object (-> fixation :arguments :object (obj/updated-object content))
        region (obj/get-region object content)
        slot (obj/get-slot object)]
    (if (or (nil? (-> fixation :arguments :expected-region))
            (nil? region)
            (not (= region (-> slot slot->location :region))))
      slot->location
      (assoc-in slot->location [slot :bias] (-> fixation :arguments :expected-region)))))
      ; (let [bias (-> fixation :arguments :expected-region)]
      ;   (assoc slot->location :bias (-> fixation :arguments :expected-region))
      ;   (assoc-in slot->location [slot :bias]
      ;             {:center ((:center-fn params) bias)
      ;              :radius ((:radius-fn params) bias)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for computing an object location map.

(defn- region->center-and-neg-mexhat
  "Given a region, the gaze center, whether you want a big mexhat or a small one,
  and the full set of mexhats, returns a vector containing a list of points and
  a list of mexhats to be placed at each point. Lists are used because there
  might be two mexhats if the region includes an extrapolated location."
  [region gaze-center big? mexhats params]
  (if (seq? region)
    [(reg/center (first region))
     (olm/get-neg-mexhat-for-region
      region mexhats big? (reg/distance (reg/center (first region)) gaze-center) params)]
    [(reg/center region)
     (olm/get-neg-mexhat-for-region region mexhats big?
                                    (reg/distance (reg/center region) gaze-center) params)]))

(defn- region->enhanced-region
  "Returns the bounding box for the enhancement associated with a region, given the object location map. Also
  provides an image of the enhanced region as a second return value."
  [region mexhats olmap params]
  (when region
    (let [mexhat (olm/get-pos-mexhat-for-region region mexhats params)]
      (olm/hat->enhanced-region mexhat (olm/get-center-for-region region) olmap))))


(defn- draw-negative-object-location-map
  "Given the display dimensions, an OpenCV matrix, a list of tracked regions, a list
  of untracked regions, the list of mexhats, and the gaze center (if any),
  computes the object location map and places it in the matrix. This function adds only
  the negative portions of each mexhat (big hats for tracked regions, small or no hats for
  untracked region."
  [dims mat tracked-regions untracked-regions mexhats gaze params]
  (let [gaze-center (if gaze
                      (vector (:pixel-x (:arguments gaze)) (:pixel-y (:arguments gaze)))
                      (vector (/ (first dims) 2) (/ (second dims) 2)))
        filtered-tracked-regions (remove-redundant-regions tracked-regions)

        ;;Default behavior is to take off a fixed cost for the
        ;;number of regions attended to.
        base (* (- (:target-cost params)) (count filtered-tracked-regions))

        [tracked_centers tracked_hats] (reduce (fn ([a b] (mapv conj a b))) (vector nil nil)
                                        (map #(region->center-and-neg-mexhat % gaze-center true mexhats params)
                                             filtered-tracked-regions))

        [untracked_centers untracked_hats] (reduce (fn ([a b] (mapv conj a b))) (vector nil nil)
                                            (map #(region->center-and-neg-mexhat % gaze-center false mexhats params)
                                                 untracked-regions))]

    (olm/make-object-location-map
      (olm/make-object-location-map mat tracked_centers tracked_hats nil base true)
      untracked_centers untracked_hats nil nil true)))

(defn- location->bias
  "If there is a bias (from extrapolation) associated with this location, return
  [center bias], where center is a point and bias is a matrix containing the image
  of the bias."
  [location mexhats params]
  (if (nil? (:bias location))
    [nil nil]
    [(-> location :bias reg/center)
     (olm/get-bias-for-region (list (:region location) (:bias location)) mexhats params)]))

(defn- add-eregions-to-object-location-map
  "Adds the enhanced regions, along with any biases found among the regions, to an object location map."
  [eregions locations mat mexhats params]
  (let [[bias_centers bias_hats] (reduce (fn[x y] (mapv conj x y)) ['() '()]
                                         (map #(location->bias % mexhats params) locations))
        updated-mat
        (olm/make-object-location-map mat (map #(-> % :region reg/center) eregions)
                                  (map :hat eregions)
                                  (map :mask eregions) nil false)]

    (olm/make-object-location-map updated-mat (remove nil? bias_centers) (remove nil? bias_hats) nil nil true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions involved the scores assigned to each enhanced region/segment pairing.
;;
;; Scores comes in the form [overlap priority]
;; A higher priority is always better. Given equal priorities, a higher overlap is better.
;; Overlap is the width of overlap between the enhanced region and the segment.
;;
;; Possible priority values:
;; 2 => The segment overlaps an enhanced region's bias (from extrapolation)
;; 1 => The segment overlaps the enhanced region
;; 0 => The two do not overlap

(defn- score>
  "Checks whether the first score is higher than the second score."
  [[overlap1 priority1] [overlap2 priority2]]
  (or (> priority1 priority2)
      (and (= priority1 priority2)
           (> overlap1 overlap2))))

(defn- score-positive?
  "Checks whether a score indicates that an enhanced region/segment pairing is possible."
  [[overlap priority]]
  (> priority 0))

(defn- estimate-segment-score
  "Computes a score for the pairing between a segment and an enhanced region (eregion).
  location is the old location the enhanced region is based on."
  [segment location eregion randomizer sensor params]
  (if (not (reg/intersect? (:region segment) eregion))
    [0 0]
    (let [segment-center ((:center-fn params) segment)
          segment-radius ((:radius-fn params) segment)
          distance (reg/distance segment-center (reg/center eregion))
          max-distance (+ segment-radius (reg/radius eregion))
          score (gaze/pixels->degrees (* 1.0 (- max-distance distance)) sensor)

          cutoff (+ (:noise-center params) (* (.nextGaussian randomizer) (:noise-width params)))
          bias-distance (and (:bias location)
                             (reg/distance segment-center (-> location :bias reg/center)))
          max-bias-distance (and (:bias location)
                                 (+ segment-radius (-> location :bias reg/radius)))]
      (cond
        (and bias-distance (< bias-distance max-bias-distance))
        [(* 1.0 (- max-bias-distance bias-distance)) 2]

        (> score cutoff)
        [score 1]

        :else
        [score 0]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Segments can be assigned to VSTM slots and their associated regions from the
;; previous cycle in two ways:
;; 1) Basic nearest neighbor
;; 2) Using an enhanced region for each slot
;;
;; When enhanced regions are used, the process takes several steps.
;; 1) Assign a score to each enhanced region/segment pairing
;; 2) Greedily match enhanced regions to segments, enforcing 1-to-1
;; matching (each region can map to at most one segment, and vice versa).
;; 3) Greedily match any remaining enhanced regions to segments that have
;; already been assigned to another enhanced region.


(defn- assign-segments-to-regions
  "Given a list of [[slot segment] score] tuples, greedily matches VSTM slots to segments.
  If reuse-segs? is true, then multiple VSTM slots can match to the same segment."
  ([scores]
   (let [[results used-slots used-segs] (assign-segments-to-regions scores {} #{} #{} false)]
     (first (assign-segments-to-regions scores results used-slots used-segs true))))

  ([scores results used-slots used-segs reuse-segs?]
   (let [[slot segment] (first scores)]
     (cond
       (empty? scores)
       [results used-slots used-segs]

       (or (get used-slots slot) (and (not reuse-segs?) (get used-segs segment)))
       (assign-segments-to-regions (rest scores) results used-slots used-segs reuse-segs?)

       :else
       (assign-segments-to-regions
         (rest scores) (assoc results slot segment) (conj used-slots slot)
         (conj used-segs segment) reuse-segs?)))))



(defn- assign-segments-by-object-location-map
  "Assigns locations to segments using enhanced regions. slot->location maps VSTM slots to
  locations from the previous cycle. olmap is a matrix on which suppressed regions have
  been drawn. Each location is converted to an enhanced region, every enhanced region/
  segment pairing is scored, and segments are greedily matched to enhanced regions."
  [segments slot->location olmap mexhats randomizer sensor params]
  (let [slots (keys slot->location)
        eregions (map #(region->enhanced-region (-> % slot->location :region)
                                                mexhats olmap params)
                      slots)
        eregion-map (zipmap slots (map :region eregions))
        pairs (for [slot slots
                    seg segments]
                [slot seg])
        scores
        (doall (map #(vector % (estimate-segment-score (second %) (slot->location (first %))
                                                       (get eregion-map (first %)) randomizer sensor params))
                    pairs))]
    [(assign-segments-to-regions (map first (sort-by second score> (filter #(score-positive? (second %)) scores))))
     (add-eregions-to-object-location-map eregions (map slot->location slots) olmap mexhats params)]))


(defn- assign-segments-1-to-1
  "Assigns regions to segments without calculating center-surround suppression
  and tracked object enhancement. Instead, rely on nearest neighbor, but with a
  preference for a 1-to-1 matching between regions and segments. Return a
  map of VSTM slots to segments."
  [segments slot->location params]
  (if (and segments (seq slot->location))
    (let [center-fn (:center-fn params)
          sq-distance-fn (:sq-distance-fn params)
          max-sq-dist (some-> (:max-distance params) (Math/pow 2))
          max-normed-distance (:max-normed-distance params)
          slots (keys slot->location)
          pairs (for [slot slots
                      seg segments]
                  [slot seg])]
      (cond->>
       (doall (map (fn [[slot seg]]
                     (vector [slot seg]
                             (get-distance-to-segment
                              (slot->location slot) seg center-fn sq-distance-fn)))
                   pairs))
       max-sq-dist (remove (fn [[pair sq-dist]] (> sq-dist max-sq-dist)))
       max-normed-distance (remove (fn [[[slot _] sq-dist]]
                                     (-> slot slot->location :region reg/radius
                                         (->> (/ (Math/sqrt sq-dist)))
                                         (> max-normed-distance))))
       true (sort-by second <)
       true (map first)
       true assign-segments-to-regions
       true (into {})))
    {}))

(defn- assign-segments
  "Assigns regions to segments without calculating center-surround suppression
  and tracked object enhancement. Instead, rely on nearest neighbor. Return a
  map of VSTM slots to segments."
  [segments slot->location params]
  (if (and segments (seq slot->location))
    (let [center-fn (:center-fn params)
          sq-distance-fn (:sq-distance-fn params)
          max-distance (:max-distance params)
          max-normed-distance (:max-normed-distance params)]
      (g/update-all slot->location
                  #(get-nearest-segment % segments center-fn sq-distance-fn
                                        max-distance max-normed-distance)))
    {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for creating interlingua elements, and top-level code.

(defn- locate-object
  [slot segment source params]
  (when (and slot segment)
    {:name "object-location"
     :arguments {:slot slot
                 ;;Don't really want the whole segment here, but let's keep it
                 ;;around in case some other component needs it.
                 :segment segment
                 :region (:region segment)}
     :world "vstm"
     :source source
     :type "relation"}))

;; Construct an outdated object location.
;; During saccades, we aren't getting new location info, so we just
;; keep restoring the last known locations of our tracked objects. But
;; we don't want to keep the segment for this location because that's outdated
;; information.  We just want to keep the region, our memory of where it was last.
(defn- location->outdated-location [loc]
  {:name "object-location"
   :arguments (dissoc (:arguments loc) :segment)
   :world (:world loc)
   :source (:source loc)
   :type (:type loc)})

;; outputs the object location map
(defn- olmap-output [mat gaze source]
  {:name "object-location-map"
   :arguments {:data mat :gaze gaze}
   :world nil
   :source source
   :type "instance"})

(defrecord ObjectLocator [buffer parameters dims mexhats old-segments mat1 mat2
                          sensor randomizer]
  Component
  (receive-focus
   [component focus content]
   (let [segmentation (d/first-element content :name (-> component :parameters :segmentation-type))
         segments (:segments (:arguments segmentation))
         gaze (:gaze (:arguments segmentation))
         locations (obj/get-latest-locations content)

         ;;Map slots to old locations
         slot->location
         (update-location-with-bias
          (make-location-hash focus content locations parameters)
          (d/first-element content :name "fixation" :reason "maintenance")
          content parameters)

         unique-regions (->> slot->location vals (map :region) set seq)
         untracked-regions (get-untracked-regions (map :region @old-segments) unique-regions)

         ;;Update our hats
         new-mexhats
         (when (:use-hats? parameters)
           (olm/make-mexhats-for-regions (concat unique-regions untracked-regions)
                                         @mexhats dims parameters))

         ;;Make the object location map
         object-location-map
         (when (:use-hats? parameters)
           (draw-negative-object-location-map dims @mat1 unique-regions untracked-regions
                                              new-mexhats gaze parameters))
         ;;Maps slots to segments
         [slot->segment object-location-map]
         (cond
           (:use-hats? parameters)
           (assign-segments-by-object-location-map
            segments slot->location object-location-map new-mexhats randomizer sensor parameters)
           (:prefer-1-to-1? parameters)
           [(assign-segments-1-to-1 segments slot->location parameters) nil]
           :else
           [(assign-segments segments slot->location parameters) nil])]

     (reset! (:old-segments component) segments)
     (reset! (:mexhats component) new-mexhats)
     (reset! old-mexhats new-mexhats)

     (reset! (:buffer component)
             (if (:saccading? (:arguments gaze))
               (cons (olmap-output
                      (draw-negative-object-location-map dims @mat1 nil nil nil nil parameters)
                      gaze component)
                     (map location->outdated-location locations))
               (cons (olmap-output object-location-map gaze component)
                     (remove nil?
                             (map #(locate-object % (slot->segment %)
                                                  component parameters)
                                  (keys slot->segment))))))
     ;;Swap cached matrices
     (when (:use-hats? parameters)
       (let [mat @(:mat1 component)]
         (reset! (:mat1 component) @(:mat2 component))
         (reset! (:mat2 component) mat)))))


  (deliver-result
    [component]
    (set @(:buffer component))))

(defmethod print-method ObjectLocator [comp ^java.io.Writer w]
  (.write w (format "ObjectLocator{}")))


(defn start
  [& {:as args}]
  (let [p (merge-parameters args)
        w (sensor/camera-width (:sensor p))
        h (sensor/camera-height (:sensor p))]
    (reset! scores ())
    (->ObjectLocator
      (atom ())
      p
      (when (:sensor p) [w h])
      (if (:use-old-hats? p) (atom @old-mexhats) (atom {}))
      (atom ())
      (when (:sensor p) (atom (olm/initialize-object-location-matrix w h)))
      (when (:sensor p) (atom (olm/initialize-object-location-matrix w h)))
      (:sensor p)
      (Random.))))
