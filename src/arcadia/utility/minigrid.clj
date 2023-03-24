(ns
 ^{:doc "Functions for working with the minigrid environment."}
 arcadia.utility.minigrid
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.general :as g]))

(def ^:private actions ["left" "right" "forward" "pickup" "drop" "toggle" "done"])
(def ^:private object-categories ["unseen" "empty" "wall" "floor" "door" "key" "ball"
                                  "box" "goal" "lava" "agent"])
(def ^:private color-names ["red" "green" "blue" "purple" "yellow" "grey"])
(def ^:private door-state ["open" "closed" "locked"])
(def ^:private directions ["right" "down" "left" "up"])

(def ^:private object-channel 0)
(def ^:private color-channel 1)
(def ^:private door-channel 2)

(defn action-name [mga]
  (get actions mga "unknown"))

(defn action-value [name]
  (.indexOf actions name))

(defn category [mgc] 
  (if (sequential? mgc)
    (get object-categories (get mgc object-channel) "unknown")
    (get object-categories mgc "unknown")))

(defn category-value [name]
  (.indexOf object-categories name))

(defn color [mgc]
  (if (sequential? mgc)
    (get color-names (get mgc color-channel) "unknown")
    (get color-names mgc "unknown")))

(defn door [mgs]
  (if (sequential? mgs)
    (get door-state (get mgs door-channel) "unknown")
    (get door-state mgs "unknown")))

(defn direction [mgd]
  (get directions mgd "unknown"))

(defn direction-value [name]
  (.indexOf directions name))

(defn inventory [content]
  (-> (d/first-element content :name "minigrid-perception")
      :arguments :adjacency-info :on))

;; right, down, left, up
(defn orient 
  "Given current and goal minigrid directions, return a minigrid action that
   will turn in the direction of the goal if needed."
  [current goal]
  (cond (= goal (inc current))
        (action-value "right")

        (= goal (dec current))
        (action-value "left")

        (and (= goal 0) (= current 3))
        (action-value "right")

        (and (= goal 3) (= current 0))
        (action-value "left")

        (>= (Math/abs (- goal current)) 2)
        (action-value "right")))

(defn needed-direction
  "Given the agent's current location in the grid and the next location that it should 
   be to follow a path, determine which direction the agent must be facing to move 
   forward to the next cell."
  [[current-x current-y] [next-x next-y]]
  (let [deltaX (- next-x current-x)
        deltaY (- next-y current-y)]
    (cond
      (neg? deltaX) (direction-value "left")
      (pos? deltaX) (direction-value "right")
      (neg? deltaY) (direction-value "up")
      (pos? deltaY) (direction-value "down"))))

;;;;; Breadth First Search to support navigation to a target cell in Minigrid
;; NOTE: ordinarily, the map being used here is an allocentric spatial representation 
;; that only includes empty cells and walls. to restrict moves based on anything other
;; than walls will require access to the current episode.
(defn is-valid-move? 
  "Evaluates whether a proposed movement will run into a wall, leave the boundary
   of the map, or lead to cycles in the path."
  [[x y] path map]
  (let [{x-max :width y-max :height} (cv/size map)]
    (and
     (> x-max x -1)
     (> y-max y -1)
     (not= "wall" (category (first (cv/get-value map x y))))
     (not-any? #{[x y]} path))))

(defn expand 
  "Identify all legal path expansions from the current location."
  [location path map] 
  (let [[x y] location]
    (filter #(is-valid-move? % path map) [[(dec x) y] [(inc x) y] [x (dec y)] [x (inc y)]])))

(defn BFS 
  "Breadth first search implementation to genereate a path from the current start-location to
   a goal-location using the information available in the map."
  [start-location goal-location map]
  (loop [search-queue (g/queue [{:location start-location :path [start-location]}])]
    (when-let [{:keys [location path]} (peek search-queue)]
      (let [possible-moves (expand location path map)]
        (cond
          ;; goal state found
          (and (= (first location) (first goal-location))
               (= (second location) (second goal-location)))
          path
          ;; dead-end
          (empty? possible-moves)
          (recur (pop search-queue))
          ;; keep searching!
          :else
          (let [new-search-nodes (mapv #(array-map :location % :path (conj path %)) possible-moves)]
            (recur (apply merge (pop search-queue) new-search-nodes))))))))
;;;;; End BFS