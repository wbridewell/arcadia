(ns arcadia.component.minigrid.object-path-enhancer
  "Focus Responsive
     Yes. When there is a navigation-affordance as the focus and there is no 
     current navigation target, adopt a new navigation target.
  Default Behavior
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
       :reason - object-path (for tracking provenance and ensuring that 
                 weights from different components are not treated as 
                 equivalent elements in content)
   
     * location-reached
       :location - the location reached
       :reason - object-path" 
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]))

(defn- weight-element [affordance weight]
  {:name "weighted-affordance"
   :arguments (assoc (:arguments affordance) :weight weight :reason "object-path")
   :type "instance"
   :world nil})

;; this component cares about toggle, forward, left, right, and pickup
;; everything else is weighted 0
;; weight movement on path as 1
;; weight movement off path as -0.5
;; if a key is in front of you, pick it up. this is likely because your target 
;; location is the key (although we could certainly check more strictly for this).
(defn- weight-affordances [start start-dir goal layout affordances
                           {:keys [front on left right]}]
  (let [[_ first-step & shortest-path] (mg/BFS start goal layout)
        needed-dir (mg/needed-direction start first-step)
        needed-turn (mg/action-name (mg/orient start-dir needed-dir))
        ;; toggle is okay here because bad toggles (locked door, no key) will not be affordances
        on-path? {"toggle" (and (= start-dir needed-dir) (#{"closed" "locked"} (:state front)) (= (:category front) "door"))
                  "forward" (and (= start-dir needed-dir) (not= (:state front) "closed") (not= (:state front) "locked"))
                  "right" (= needed-turn "right")
                  "left" (= needed-turn "left")
                  "pickup" (and (= start-dir needed-dir) (= (:category front) "key"))}]
    (map #(let [action (-> % :arguments :action-name)]
            (weight-element % (cond (not (contains? on-path? action)) 0
                                    (on-path? action) 2 ;; NOTE: more important than exploration
                                    :else -0.5)))
         affordances)))

(defn- goal-reached [loc]
  {:name "location-reached"
   :arguments {:location loc
               :reason "object-path"}
   :type "instance"
   :world nil})

(defrecord MinigridObjectPathEnhancer [buffer nav-goal]
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
          episode (d/first-element content :name "episode" :world nil :type "instance")]

      ;; adopt a new target location whenever there is no current goal and there is a 
      ;; new one in focus 
      (when (and (nil? @nav-goal)
                 (d/element-matches? focus :name "navigation-affordance" :type "instance"))
        (reset! nav-goal (map (-> focus :arguments :location) [:x :y])))

      ;; this is basically the same idea as in infogain-enhancer, but an earlier version.
      ;; when there is a navigation goal, find the path from the current location to that goal.
      ;; weight affordances based on their relationship to the current path to
      ;; the nav-goal. as you get closer to the goal, increase the weights for good 
      ;; moves and decrease the weights for bad moves.
      (when (and @nav-goal egocentric allocentric (seq affordances) mg-perception)
        (if (= (-> egocentric :arguments :location) @nav-goal)
          (do (reset! buffer [(goal-reached @nav-goal)])
              (reset! nav-goal nil))
          (reset! buffer
                  (weight-affordances (-> egocentric :arguments :location)
                                      (-> egocentric :arguments :direction)
                                      @nav-goal
                                      (-> allocentric :arguments :layout)
                                      affordances
                                      (-> mg-perception :arguments :adjacency-info)))))))

  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridObjectPathEnhancer [comp ^java.io.Writer w]
  (.write w (format "MinigridObjectPathEnhancer{}")))

(defn start []
  (->MinigridObjectPathEnhancer (atom nil) (atom nil)))
