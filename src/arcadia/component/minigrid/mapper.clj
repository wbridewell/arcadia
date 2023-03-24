(ns arcadia.component.minigrid.mapper
  "Focus Responsive:
    No.
   Default Behavior:
   Constructs an allocentric representation of the minigrid map that includes only topographical
   information (i.e., walls, lava). Unseen cells are marked as normal. Seen cells that do not
   contain topographical information are marked as empty. 
   "
  (:require [arcadia.sensor.core :as sensor]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required sensor "a sensor that provides minigrid input (required)" nil)

;; this may become a parameter at some point
(def ^:private topographical-categories ["wall" "lava"])

;; assumes egocentric grid is 7x7, which is the default for minigrid
(def ^:private grid-size {:width 7 :height 7})

;; minigrid directions are integers from 0 to 3 which map to [right down left up]
(def ^:private directions [{:name "right"
                            :value 0
                            :rotation nil
                            :agent [0 (quot (:height grid-size) 2)]}
                           {:name "down"
                            :value 1
                            :rotation cv/ROTATE_90_CLOCKWISE
                            :agent [(quot (:width grid-size) 2) 0]}
                           {:name "left"
                            :value 2
                            :rotation cv/ROTATE_180
                            :agent [(dec (:width grid-size)) (quot (:height grid-size) 2)]}
                           {:name "up"
                            :value 3
                            :rotation cv/ROTATE_90_COUNTERCLOCKWISE
                            :agent [(quot (:width grid-size) 2) (dec (:height grid-size))]}])

;; NOTE: the matrix size is taken from the full map, and the agent location will be provided 
;; by the full map, too. this way i can only 
;; worry about filling in the missing information.
;;
;; i am also interested in labeling rooms, creating a level of abstraction that sits between
;; the full map and a single cell. the room names could later be used for planning and would 
;; have value for episodic memory where the agent needs to remember where an object is, but 
;; not necessarily its exact location.

;; take the egocentric view, rotate it based on the agent's direction, mask to cover the 
;; unseen parts, and then insert the submatrix where the agent will be. this might not be
;; trivial because the agent could be in a place where the 0,0 cell of the egocentric view
;; is outside the bounds of the grid.

;; (defn- extract-seen [mat]
;;   ;; create a matrix with either [1 0 0] for visible locations or [0 0 0] 
;;   ;; for occluded locations
;;   (let [seen (cv/threshold (first (cv/split mat)) 0 cv/THRESH_BINARY :max-value 1)]
;;     (cv/merge seen (cv/zeros seen) (cv/zeros seen))))

;; (defn- extract-topography
;;   "Keeps only the walls and lava in the map."
;;   [mat]
;;   (let [wall-mask (cv/in-range mat [2 0 0] [2 10 10])
;;         lava-mask (cv/in-range mat [9 0 0] [9 10 10])]
;;     ;; add details about walls and lava to the visibility map
;;     (cv/copy mat :dst (extract-seen mat) :mask (cv/bitwise-or wall-mask lava-mask))))

(defn- extract-topography
  "Given a matrix and a list of categories, copies the matrix and then sets the values of
   all known locations that don't match the list of categories to [1 0 0]."
  ([mat]
   (extract-topography mat (map mg/category-value topographical-categories)))
  ([mat categories]
  ;;Mask represents known locations that don't match the list of categories
   (let [mask (reduce cv/bitwise-xor!
                      (cv/in-range mat [1 0 0] [10 10 10])
                      (map #(cv/in-range mat [% 0 0] [% 10 10]) categories))]
     (-> mat cv/copy (cv/set-to! [1 0 0] :mask mask)))))

(defn- rotate-map
  "Rotate the egocentric map so that the agent is facing the correct direction according 
   to the global map."
  [map agent-direction]
  (if (:rotation (get directions agent-direction))
    (cv/rotate map (:rotation (get directions agent-direction)))
    map))

(defn- ego-cell [agent-observations]
  (-> agent-observations :direction (#(get directions %)) :agent))


(defn- clip-dimensions
  "Returns a map containing the amount of rows and columns to cut from the different sides of the ego map. Values for 
   :left, :right, :top, and :bottom will be integers less than or equal to 0."
  [agent-observations explored-map]
  (zipmap [:left :top :right :bottom]
          (map #(min 0 %)
               [(- (-> agent-observations :location first)
                   (-> agent-observations ego-cell first))
                (- (-> agent-observations :location second)
                   (-> agent-observations ego-cell second))
                (- (cv/width explored-map) 1
                   (+ (-> agent-observations :location first)
                      (- (:width grid-size) 1 (-> agent-observations ego-cell first))))
                (- (cv/height explored-map) 1
                   (+ (-> agent-observations :location second)
                      (- (:height grid-size) 1 (-> agent-observations ego-cell second))))])))

(defn- clip-ego-map
  "Returns a version of the egocentric map that will fit within the boundaries of the full map."
  [map clip]
  (cv/submat map
             (- (:left clip)) (- (:top clip))
             (+ (:width grid-size) (:left clip) (:right clip))
             (+ (:height grid-size) (:top clip) (:bottom clip))))

;; since we are using 1 0 0 to track seen cells, this function is probably no longer 
;; needed. it used to apply inrange from [2 0 0] to [9 10 10] to avoid copying empty
;; cells, but that's no longer desirable. 
(defn- ego-mask
  "Returns a mask that prevents copying empty cells from the egocentric map to the full map."
  [map]
  (cv/in-range map [1 0 0] [9 10 10]))

(defn- explored-roi
  "Returns a submatrix of the explored map that will contain the new details from the egocentric map."
  [explored-map agent-observations clipped-ego]
  (cv/submat explored-map
             (max 0 (- (-> agent-observations :location first)
                       (-> agent-observations ego-cell first)))
             (max 0 (- (-> agent-observations :location second)
                       (-> agent-observations ego-cell second)))
             (cv/width clipped-ego)
             (cv/height clipped-ego)))

(defn- memorize-map
  "Updates the explored area of the map with the latest observations."
  [agent-observations explored-map]
  (let [ego-map (rotate-map (extract-topography (:image agent-observations)) (:direction agent-observations))
        clip-values (clip-dimensions agent-observations explored-map)
        ;; clip the ego-matrix down to a size that respects the boundaries of the full map
        ego-clip (clip-ego-map ego-map clip-values)
        dst (cv/copy explored-map)]
    ;; need to make the submat the same size as the region that is being copied into
    ;; so we will potentially need to submat both the ego matrix and the full map.
    ;; create a region of the full map to copy the egocentric view into
    (cv/copy ego-clip :dst (explored-roi dst agent-observations ego-clip) :mask (ego-mask ego-clip))
    dst))

;; ha! the agent isn't represented in the map, so we had to add it as part 
;; of the sensor output.
;; (defn- agent-location
;;   "Extracts the veridical agent location from a full map representation"
;;   [map]
;;   ;; value at agent location is [10 - -]
;;   (let [agent-mask (cv/in-range map [8 0 0] [8 10 10])]
;;     (cv/non-zero-bounds agent-mask)))

(defn map-element [component]
  {:name "map-grid"
   :arguments {:map @(:map-grid component)}
   :type "instance"
   :world nil})

;; grid level map is the only organizational level right now. 
;; eventually, i want to subdivide the grid into rooms
(defn allocentric-map [component]
  {:name "spatial-map"
   :arguments {:layout @(:map-grid component)
               :place "minigrid"
               :container nil
               :perspective "allocentric"}
   :world nil
   :type "instance"})


(defrecord MinigridMapper [sensor buffer map-grid]
  Component
  (receive-focus
    [component focus content]
    (when-let [data (sensor/poll (:sensor component))]
     ;; initialize the map and assume it won't change in size while the model is running
      (if (nil? @(:map-grid component))
        (reset! (:map-grid component) (cv/zeros (cv/size (:full-map data)) cv/CV_8UC3))
        (reset! (:map-grid component) (memorize-map (:agent-obs data) @(:map-grid component))))
      (reset! (:buffer component) [(map-element component) (allocentric-map component)])))

  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridMapper [comp ^java.io.Writer w]
  (.write w (format "MinigridMapper{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->MinigridMapper (:sensor p) (atom nil) (atom nil))))

