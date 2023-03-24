(ns arcadia.component.minigrid.segmenter
  "
   Produces
   * image-segmentation
       includes a list of :segments extracted from the visual field, each of
       which has a corresponding
         * :category of the object or either \"unseen\" or \"empty\" if appropriate
         * :color of any present object
         * :state of a door object if it is open, closed, locked, or unknown
         * :area of the segment,
         * a rectangular bounding :region, and
       the image-segmentation also includes the original
         * :image that was processed,
         * the :sensor that delivered the image."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]
            [arcadia.sensor.core :as sensor]))


(def ^:parameter ^:required sensor "a sensor that provides minigrid input (required)" nil)

;; The egocentric view by default will be a 7 x 7 observation matrix with the agent at (3,0)
;; with cells on the left in rows 0 to 2 and on the right in rows 4 to 6
;; the mapping of the cells to the allocentric map will require the direction information on the 
;; agent, which is contained in (:direction obs)
;; 0: right, 1: down, 2: left, 3: up
;; technically, the egocentric view can be of arbitrary odd-integer size

;; if the map is bounded by a wall and the egocentric view records cells outside of the map,
;; then those cells may be listed as walls even though they would technically be blocked 
;; by the boundary wall and should be considered unseen. this complicates the creation of 
;; wall segments for some maps.

(def ^:private ego-grid-size [7 7]) ;; may make this a parameter
(def ^:private ego-column 0)
(def ^:private ego-row (quot (first ego-grid-size) 2))



(defn- global-location
  "Convert object's local location to a location in the global map."
  [agent-loc agent-dir eg-idx]
  (let [agent-x (first agent-loc)
        agent-y (second agent-loc)
        ego-x (first eg-idx)
        ego-y (second eg-idx)]
    ;; index rotation in a square matrix that takes the ego location (0,3) into account
    (case (mg/direction agent-dir)
      "right" [(+ agent-x ego-x) (+ agent-y ego-y (- ego-row))]
      "down" [(+ agent-x ego-row (- ego-y)) (+ agent-y ego-x)]
      "left" [(- agent-x ego-x) (+ agent-y ego-row (- ego-y))]
      "up" [(+ agent-x ego-y (- ego-row)) (- agent-y ego-x)])))

(defn- semantics [mgpoint]
  {:category  (mg/category mgpoint)
   :color (mg/color mgpoint)
   :state (mg/door mgpoint)})

(defn- object-segments [mtx dir loc]
  (for [y (range (cv/height mtx))
        x (range (cv/width mtx))
        :let [cell (cv/get-value mtx x y)
              global-loc (global-location loc dir [x y])]
        ;; only keep objects that can occupy only a single grid cell
        ;; (unseen, empty, wall, floor, and lava are out)
        ;; remove objects on the same cell as the agent 
        ;; (item in inventory or open doors as you're walking through)
        :when (and (not (#{"unseen" "empty" "wall" "floor" "lava"} (mg/category cell)))
                   (not= [x y] [ego-column ego-row]))]
    (merge (semantics cell) {:area 1 :region {:x (first global-loc) :y (second global-loc) :width 1 :height 1}})))

(defn- perceive [obs component]
  {:name "image-segmentation"
   :arguments {:image (:image obs)
               :segments (object-segments (:image obs) (:direction obs) (:location obs))
               :sensor (:sensor component)}
   :world nil
   :type "instance"})

(defrecord MinigridSegmenter [sensor buffer]
  Component
  (receive-focus
    [component focus content]
    (when-let [data (sensor/poll (:sensor component))]
      (reset! (:buffer component) (perceive (:agent-obs data)
                                            component))))
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridSegmenter [comp ^java.io.Writer w]
  (.write w (format "MinigridSegmenter{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->MinigridSegmenter (:sensor p) (atom nil))))
