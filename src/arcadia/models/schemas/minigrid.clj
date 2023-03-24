(ns arcadia.models.schemas.minigrid
  (:require [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]
            [arcadia.utility.task-sets :as ts]))

;;;; Descriptors
;;
;; Define descriptors for elements important to moving around in the minigrid 
;; environment. 

;; ":world nil" is important here to avoid matching with VSTM elements
(def door-descriptor (d/object-descriptor "a door" :category "door" :world nil))
(def key-descriptor (d/object-descriptor "a key" :category "key" :world nil))
(def goal-descriptor (d/object-descriptor "a goal" :category "goal" :world nil))
(def ball-descriptor (d/object-descriptor "a ball" :category "ball" :world nil))

;; event descriptors are potential markers for episode boundaries
(def toggle-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                     :event-name "action" :action-command "toggle"))

(def pickup-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                     :event-name "action" :action-command "pickup"))

(def entry-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                    :event-name "enter-room"))

;;;; Attentional Strategy Tiers
;; Define some levels of attentional strategies. These can be reused across task-set schemas,
;; and giving them names can improve the interpretability of schema definitions.

(def done-action (att/strategy-tier
                  (d/descriptor :name "minigrid-action" :type "action" :world nil
                                :action-name "done")))

(def outcome-achieved (att/strategy-tier
                       (d/descriptor :name "outcome-achieved" :type "instance" :world nil)))

;; focus selector adopts strategies automatically.
(def task-management
  (att/strategy-tier (d/descriptor :name "task-configuration" :type "instance" :world nil)
                     (d/descriptor :name "outcome-achieved" :type "instance" :world nil)))

(def action-event (att/strategy-tier toggle-descriptor pickup-descriptor))

(def entry-event (att/strategy-tier entry-descriptor))

(def find-event (att/strategy-tier (d/descriptor :name "event" :type "instance" :world nil
                                                 :event-name "object-found")))

(def query-request (att/strategy-tier (d/descriptor :name "episode" :type "instance" :world "query")))

(def navigation-goal (att/strategy-tier (d/descriptor :name "navigation-affordance")))

(def recalled-episode 
  (att/strategy-tier (d/descriptor :name "episode" :type "instance" :world "episodic-memory")))

(def basic-action (att/strategy-tier (d/descriptor :type "action")))

(def best-action (att/strategy-tier (d/descriptor :type "action" :best? true)))

(def closed-door
  (att/strategy-tier (d/object-descriptor "closed-door" :category "door" :state "closed" :world nil)))

(def basic-strategy
  (att/strategy-tier
   (d/descriptor :name "object" :type "instance" :world nil)
   (d/descriptor :name "fixation")
   (d/descriptor :name d/ANY-VALUE)))

;;;; Task-Set Schemas (or Schemata, you choose)
;; Task-set schemas should be general purpose for the simulation environment. They can be 
;; customized during instantiation by passing in arguments. Any arguments must be 
;; descriptors.

(defn- make-object-query [object]
  (let [properties [:name "object"
                    :type "instance"
                    :category (:category object)
                    :color (:color object)] 
        x (apply d/descriptor
                 (if (:state object) (conj properties :state (:state object)) properties))]
    {:name "episode"
     :arguments {:conceptual [x]}
     :world "query"
     :type "instance"}))

(defn- episode-nav-goal [descriptor episode content]
  ;; there is a conceptual match
  (when-let [obj (d/first-match descriptor (-> episode :arguments :conceptual))]
    (d/first-element
     (for [[loc descs] (seq (-> episode :arguments :spatial :contents))
           :when (d/first-match descriptor descs)]
       {:name "adopt-navigation-target" :arguments 
        {:goal obj :location loc :reason "recall-location"
         ;; this lets us differentiate the results of successful recall queries
         :description descriptor}}))))

(defn explore-schema
  "Attempt to view every reachable cell in the grid."
  []
  (ts/task-set "explore"
               [[outcome-achieved 5.5]
                [task-management 6]
                [action-event 5.3]
                [done-action 5.2]
                [entry-event 5.1]
                [find-event 5]
                [best-action 3]
                [basic-action 2]
                [basic-strategy 1]]
               [(ts/sr-link [(d/descriptor :name "object" :world nil :category "ball")]
                            (fn [obj content]
                              {:name "minigrid-action"
                               :arguments {:action-command (mg/action-value "done")
                                           :action-name "done"
                                           ;; this is the final action.
                                           ;; force it to be high priority.
                                           :best? true
                                           :weight 10}}))]))

;; prompt for an episode and pay attention to it when it arrives.
(defn recall-location-schema
  "Recall the location of an object and set a goal to navigate to it."
  [descriptor]
  (ts/task-set "recall-location"
               [[outcome-achieved 4.5]
                [task-management 5] 
                [query-request 4]
                [recalled-episode 3]
                [action-event 2.7]
                [entry-event 2.5]
                [best-action 2]
                [basic-strategy 1]]
               [(ts/initial-response (make-object-query (:arguments descriptor)))
                (ts/initial-response (d/descriptor :name "query" :type "instance" :key "what" :world "query"))
                (ts/sr-link [(d/descriptor :name "episode" :world "episodic-memory" :query "what")] 
                            (fn [ep content]
                              (episode-nav-goal descriptor ep content)))]))

(defn find-schema 
  "When the object is found, set a goal to navigate to it."
  [descriptor]
  (ts/task-set "find-object"
               [[outcome-achieved 4.5]
                [task-management 5]
                [action-event 4]
                [entry-event 3]
                [find-event 2.5]
                [best-action 2]
                [basic-strategy 1]]
               [(ts/sr-link [descriptor] 
                            (fn [obj content]
                              {:name "adopt-navigation-target"
                               :arguments {:goal obj
                                           :description descriptor
                                           :location (-> obj :arguments :region)
                                           :reason "find-object"}}))]))

(defn navigate-schema 
  "Navigate to the current goal."
  [x]
  (ts/task-set "navigate-to-location" 
                [;[navigation-goal 8]
                 [outcome-achieved 7]
                 [task-management 6]
                 [(att/strategy-tier (d/descriptor :name "navigation-affordance"
                                                   :goal #(d/element-matches? % :name "object"
                                                                              :category (-> x :arguments :category))))
                  5]
                 [action-event 2.7]
                 [entry-event 4]
                 [best-action 3]
                 [basic-strategy 1]]
               [])) 


