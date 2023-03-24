(ns arcadia.component.minigrid.infogain-enhancer
  "Focus Responsive
     No.
  Default Behavior
    Selects a location to move to based upon the potential for information gain.
    Evaluates available minigrid affordances, weighting them based on whether they 
    bring the agent closer to the goal location. Reports when its currently targeted
    location is reached.
  Produces
    * weighted-affordance 
       :type - instance
       :world - nil
       :action-name - string description of action
       :action-command - a minigrid action identifier (integer)
       :patient - an object that is the target of the action (if any)
       :weight - a number indicating the value of the potential action
       :reason - infogain (for tracking provenance and ensuring that 
                 weights from different components are not treated as 
                 equivalent elements in content)
   
     * location-reached
       :location - the location reached
       :reason - infogain"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]))

;; NOTE: consider this modification
;; As the agent gets nearer to that location, the weights increase to reflect increased commitment.

(defn- navigation-targets [mat]
  ;; take a map of all seen locations and get the outer boundaries
  ;; in-range creates a mask of all wall and lava items (fixed topography)
  ;; find-contours identifies the outer boundary of "seen" locations in the map
  ;; subtract removes the walls and lava from those boundaries as they are not
  ;; appropriate navigation targets. 
  ;; returns a vector of points in [x y] format.  
  (map #(vector (int (:x %)) (int (:y %)))
       (cv/non-zero-points
        (reduce cv/subtract!
                (cv/find-contours (cv/threshold (first (cv/split mat)) 0 cv/THRESH_BINARY :max-value 1))
                (map #(cv/in-range mat [% 0 0] [% 10 10]) (map mg/category-value ["wall" "lava"]))))))

(defn- weight-element [affordance weight]
  {:name "weighted-affordance"
   :arguments (assoc (:arguments affordance) :weight weight :reason "infogain")
   :type "instance"
   :world nil})

;; this component only cares about toggle, forward, left, and right
;; everything else is weighted 0
;; weight movement on path as 1
;; weight movement off path as -0.5
;; conditions for toggle and forward are mutually exclusive
;; conditions for right and left are mutually exclusive 
(defn- weight-affordances [path start-dir affordances
                           {:keys [front on left right]}]
  (let [[start first-step & shortest-path] path
        needed-dir (mg/needed-direction start first-step)
        needed-turn (mg/action-name (mg/orient start-dir needed-dir))
        ;; toggle is okay here because bad toggles (locked door, no key) will not be affordances
        on-path? {"toggle" (and (= start-dir needed-dir) (#{"closed" "locked"} (:state front)) (= (:category front) "door"))
                  "forward" (and (= start-dir needed-dir) (not= (:state front) "closed") (not= (:state front) "locked"))
                  "right" (= needed-turn "right")
                  "left" (= needed-turn "left")}]
    (map #(let [action (-> % :arguments :action-name)]
            (weight-element % (cond (not (contains? on-path? action)) 0
                                    (on-path? action) 1
                                    :else -0.5)))
         affordances)))

(defn- goal-reached [loc]
  {:name "location-reached"
   :arguments {:location loc
               :reason "infogain"}
   :type "instance"
   :world nil})

(defn- locked-doors
  "Given an episode, returns the location of the locked doors as a set of 
   coordinate (i.e., [x y]) vectors"
  [episode]
  (into #{}
        (for [[k v] (seq (-> episode :arguments :spatial :contents))
              :when (some #{"locked"} (map #(-> % :arguments :state) v))]
          [(:x k) (:y k)])))

(defn- traversable? 
  "Returns true when the cell at the given location is not an untraversable kind."
  [[x y] mat]
  (not (#{"wall" "lava"} (mg/category (first (cv/get-value mat x y))))))

;; for the purposes of path planning, pretend that locked doors are walls
;; to avoid paths that try to go through them.
(defn- block-locked-doors [layout locked]
  (if (seq locked)
    (let [new-layout (cv/copy layout)
          fake-wall [(mg/category-value "wall") 0 0]]
      (doseq [[x y] locked]
        (cv/set-value! new-layout x y fake-wall))
      new-layout)
    layout))

(defrecord MinigridInfogainEnhancer [buffer nav-goal path]
  Component
  (receive-focus
    [component focus content]
    (reset! buffer nil)
    (let [;; what can i do?
          affordances (d/filter-elements content :name "minigrid-affordance" :type "instance" :world nil)
          ;; topography
          allocentric (d/first-element content :name "spatial-map" :perspective "allocentric")
          ;; where am i and what direction am i facing?
          egocentric (d/first-element content :name "spatial-map" :perspective "egocentric")
          ;; what is immediately around me? (three directions and "on" for inventory)
          mg-perception (d/first-element content :name "minigrid-perception")
          ;; what have i seen recently?
          episode (d/first-element content :name "episode" :world nil :type "instance")
          locked (locked-doors episode)]
      ;; if there is not a navigation goal or if there is a navigation goal, but the goal 
      ;; location is now known to be a locked door or a wall, set a new navigation goal.
      ;; 
      ;; get the boundary points, filter out locked doors in the current episode, and 
      ;; select a remaining point at random.
      (when (and (-> allocentric :arguments :layout)
                 (or (not @nav-goal)
                     (and @nav-goal
                          (or
                           (not (traversable? @nav-goal (-> allocentric :arguments :layout)))
                           (locked @nav-goal)))))
        (reset! nav-goal
                (g/rand-if-any
                 (remove locked (navigation-targets (-> allocentric :arguments :layout))))))

      ;; when there is a navigation goal, find the path from the current location to that goal.
      ;; weight affordances based on their relationship to the current path to
      ;; the nav-goal. as you get closer to the goal, increase the weights for good 
      ;; moves and decrease the weights for bad moves.
      (when (and egocentric allocentric (seq affordances) mg-perception)
        (cond (and @nav-goal (= (-> egocentric :arguments :location) @nav-goal))
              (do (reset! buffer [(goal-reached @nav-goal)])
                  (reset! nav-goal nil))

              (and @nav-goal (not= (-> egocentric :arguments :location) @nav-goal))
              (do
               ;; update the path in case there is new information in the episode. 
                (reset! path (mg/BFS (-> egocentric :arguments :location)
                                     @nav-goal
                                     (block-locked-doors (-> allocentric :arguments :layout) locked)))
                (reset! buffer
                        (weight-affordances @path
                                            (-> egocentric :arguments :direction)
                                            affordances
                                            (-> mg-perception :arguments :adjacency-info))))

              :else ;; nowhere to go in particular, just pass along the affordances without penalty
              (reset! buffer (map #(weight-element % 0) affordances))))))

  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridInfogainEnhancer [comp ^java.io.Writer w]
  (.write w (format "MinigridInfogainEnhancer{}")))

(defn start []
  (->MinigridInfogainEnhancer (atom nil) (atom nil) (atom nil)))
