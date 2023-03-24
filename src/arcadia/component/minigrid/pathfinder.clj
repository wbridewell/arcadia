(ns arcadia.component.minigrid.pathfinder
  "TODO"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]))

(defn- make-action [id goal]
  (when id
    {:name "minigrid-action"
     :arguments {:action-command id
                 :goal goal
                 :path? true}
     :world nil
     :type "action"}))

(defn- generate-behavior [start start-dir goal layout {:keys [front on left right]}]
  (when (not= start goal)
    (let [[_ first-step & shortest-path] (mg/BFS start goal layout)
          needed-dir (mg/needed-direction start first-step)]
      (cond
        (or (= (:category on) "goal")
            (= (:category front) "goal"))
        (mg/action-value "done")

        (= (:category left) "goal")
        (mg/action-value "left")

        (= (:category right) "goal")
        (mg/action-value "right")

        (= (:category front) "key")
        (mg/action-value "pickup")

        (and (= needed-dir start-dir) (= (:state front) "closed") (= (:category front) "door"))
        (mg/action-value "toggle")

        (and (= (:category front) "door") (= (:state front) "locked")
             (= (:category on) "key") (= (:color on) (:color front)))
        (mg/action-value "toggle")

        (= start-dir needed-dir)
        (mg/action-value "forward")

        :else (mg/orient start-dir needed-dir)))))

(defrecord MinigridPathfinder [buffer]
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
        (reset! buffer nil)
        (if (and navpoint allocentric egocentric)
          (reset! buffer
                  (make-action (generate-behavior (-> egocentric :arguments :location)
                                                  (-> egocentric :arguments :direction)
                                                  (map (-> navpoint :arguments :goal :arguments :region) [:x :y])
                                                  (-> allocentric :arguments :layout)
                                                  (-> mg-perception :arguments :adjacency-info))
                               (-> navpoint :arguments :goal)))
          (reset! buffer nil)))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridPathfinder [comp ^java.io.Writer w]
  (.write w (format "MinigridPathfinder{}")))

(defn start []
  (->MinigridPathfinder (atom nil)))
