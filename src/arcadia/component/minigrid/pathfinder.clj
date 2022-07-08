(ns arcadia.component.minigrid.pathfinder
  "TODO"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.minigrid :as mg]))

(defn- make-action [id goal source]
  (when id
    {:name "minigrid-action"
     :arguments {:action-command id 
                 :goal goal
                 :path? true}
     :world nil
     :source source
     :type "action"}))

(defn queue [& vals]
     (apply merge (clojure.lang.PersistentQueue/EMPTY) vals))

;; NOTE: restricting moves based on anything other than walls will require 
;; access to the current episode.
(defn- is-valid-move? [location path map]
    ;; check to see if location is a wall or out of bounds or already visited
    (let [dims (cv/size map)
          x (first location)
          y (second location)]
        (and
         (>= x 0)
         (>= y 0)
         (< x (first dims))
         (< y (second dims))
         (not= (first (cv/get-value map x y)) 2) ;; wall
         (not (some #(and (= x (first %)) (= y (second %))) path)))))

(defn- expand [location path map]
  (let [x (first location)
        y (second location)]
    (filter #(is-valid-move? % path map) [[(dec x) y] [(inc x) y] [x (dec y)] [x (inc y)]])))

(defn- BFS [start-location goal-location map]
  (loop [search-queue (queue {:location start-location :path [start-location]})]
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

;; [right down left up] -> [0 1 2 3]
(defn- needed-direction [current next]
  (let [deltaX (- (first next) (first current))
        deltaY (- (second next) (second current))]
    (cond
      (< deltaX 0) 2
      (> deltaX 0) 0
      (< deltaY 0) 3
      (> deltaY 0) 1
      :else nil)))

(defn- at-goal? [agent-position goal-position]
  (and (= (first agent-position) (:x goal-position))
       (= (second agent-position) (:y goal-position))))

(defn- generate-behavior [start start-dir goal map adjacency-info]
  (when (not (at-goal? start goal)) 
    (let [start-location [(first start) (second start)]
          goal-location [(:x goal) (:y goal)]
          shortest-path (rest (BFS start-location goal-location map))
          first-step (first shortest-path)
          needed-dir (needed-direction start-location first-step)
          forward (:front adjacency-info)
          ontop (:on adjacency-info)
          left (:left adjacency-info)
          right (:right adjacency-info)]
      (cond
        (= (:category ontop) "goal")
        (mg/action-value "done")

        (= (:category forward) "goal")
        (mg/action-value "done")

        (= (:category left) "goal")
        (mg/action-value "left")

        (= (:category right) "goal")
        (mg/action-value "right")

        (= (:category forward) "key")
        (mg/action-value "pickup")

        (and (= needed-dir start-dir) (= (:state forward) "closed") (= (:category forward) "door"))
        (mg/action-value "toggle")

        (and (= (:category forward) "door") (= (:state forward) "locked")
             (= (:category ontop) "key") (= (:color ontop) (:color forward)))
        (mg/action-value "toggle")

        (= needed-dir start-dir)
        (mg/action-value "forward")

        (= needed-dir (inc start-dir))
        (mg/action-value "right")

        (= needed-dir (dec start-dir))
        (mg/action-value "left")

        (and (= needed-dir 0) (= start-dir 3))
        (mg/action-value "right")

        (and (= needed-dir 3) (= start-dir 0))
        (mg/action-value "left")

        (>= (Math/abs (- needed-dir start-dir)) 2)
        (mg/action-value "right")

        :else
        nil))))

(defrecord MinigridPathfinder [buffer forward?]
  Component
  (receive-focus
   [component focus content]
   ;; action in process, so wait for it to complete before requesting a new action
   (let [navpoint (d/first-element content :name "minigrid-navigation")
         allocentric (d/first-element content :name "spatial-map" :perspective "allocentric")
         egocentric (d/first-element content :name "spatial-map" :perspective "egocentric")
         mg-perception (d/first-element content :name "minigrid-perception")]
     (if (or (d/element-matches? focus :type "action" :name "minigrid-action" :world nil)
             (d/first-element content :type "environment-action" :name "minigrid-env-action"))
       (reset! (:buffer component) nil)
       (if (and navpoint allocentric egocentric)
         (reset! (:buffer component)
                 (make-action (generate-behavior (-> egocentric :arguments :location)
                                                 (-> egocentric :arguments :direction)
                                                 (-> navpoint :arguments :goal :arguments :region)
                                                 (-> allocentric :arguments :layout)
                                                 (-> mg-perception :arguments :adjacency-info))
                              (-> navpoint :arguments :goal)
                              component))
         (reset! (:buffer component) nil)))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridPathfinder [comp ^java.io.Writer w]
  (.write w (format "MinigridPathfinder{}")))

(defn start []
  (->MinigridPathfinder (atom nil) (atom false)))
