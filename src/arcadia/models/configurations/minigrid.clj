(ns arcadia.models.configurations.minigrid 
  (:require [arcadia.utility.descriptors :as d] 
            [arcadia.models.schemas.minigrid :as mgt]))

;;;; General Plan
;; see a locked door
;; key in current episode
;;   yes: navigate to location
;;   no: recall location of key
;;     found: navigate to location
;;     not-found: wander until you see the key
;; pick up key
;; recall location of locked door
;;   found: navigate to location

;;;; Schema Configurations
;; (rough sketch)
;; O: outcome / condition
;; A-D: configurations
;; X * when you see a locked door (O), try to remember a key (A)
;; X * when you see a locked door (O), start looking for a key (B)
;; * when you recall a key (O), navigate to it (C)
;; * when you see a key (O), navigate to it (C)
;; X * when you have a key (O), recall a locked door (A)
;; X * when you have a key (O), start looking for a locked door (B)
;; * when you see a locked door (O), navigate to it (C)
;; * when you're at the locked door with a key, open it (D?)

(defn in-inventory
  ([category]
   (in-inventory category nil))
  ([category color]
   (d/descriptor :name "inventory"
                 :contents #(some (fn [x] (and (= (:category x) category)
                                               (= (:color x) color))) %))))

;; task set is a function that instantiates a schema 
;; outcome is a function that produces a sequence of descriptors to match for success conditions
;;
;; outcome memory stores handle and outcome
;; prospective memory stores handle, conditions, task set
;; when prospective memory matches, it produces matches that get sent along to outcome memory via
;; the task-configuration. outcome memory finds the memory with the matching handle and creates
;; a new active outcome memory using the matches.

(def plan-find-key
  "if you see a locked door, find a key that unlocks it."
  {:handle :find-key
   :conditions [(d/descriptor :name "object" :category "door" :state "locked")]
   :task-set (fn [x] (mgt/find-schema
                      (d/extend-descriptor mgt/key-descriptor :color (-> x :arguments :color))))
   :outcome (fn [x] [(d/descriptor :name "event"
                                   :event-name "object-found"
                                   :object #(d/element-matches? % :category "key")
                                   :world nil)])})

(defn- recall-key-descriptor [x]
  (d/extend-descriptor mgt/key-descriptor :color (-> x :arguments :color)))

(def plan-recall-key
  "if you see a locked door, remember where the key was if possible."
  {:handle :recall-key
   :conditions [(d/descriptor :name "object" :category "door" :state "locked")]
   :task-set (fn [x] (mgt/recall-location-schema (recall-key-descriptor x)))
   :outcome (fn [x] [(d/descriptor :name "adopt-navigation-target" :type "action"
                                   :description (recall-key-descriptor x))])})

(def plan-find-goal
  "find the goal"
  {:handle :find-goal
   :conditions [(d/descriptor :name "object" :category "door" :state "locked")]
   :task-set (fn [x] (mgt/find-schema (d/descriptor :name "object" :world nil :category "ball")))
   :outcome (fn [x] [(d/descriptor :name "object" :world nil :category "ball")])})

(def plan-recall-goal
  "recall the goal"
  {:handle :recall-goal
   :conditions [(d/descriptor :name "object" :category "door" :state "locked")]
   :task-set (fn [x] (mgt/recall-location-schema (d/descriptor :name "object" :world nil :category "ball")))
   :outcome (fn [x] [(d/descriptor :name "object" :world nil :category "ball")])})

;; NOTE: inventory is not encoded in episodes, but maybe it should be. this would let us change
;; how you recognize that you have a key. right now it's dependent on a pickup event, but we 
;; should be able to know or check what's in our pockets and have that trigger activity.

(defn- object-property
  [obj property]
  (-> obj :arguments property))

(def plan-find-door
  "if you pickup a key, find a door that unlocks it."
  {:handle :find-door
   :conditions [mgt/pickup-descriptor]
   :task-set (fn [x]
               (mgt/find-schema
                (d/extend-descriptor mgt/door-descriptor
                                     :color (object-property (-> x :arguments :patient) :color)
                                     :state "locked")))
   :outcome (fn [x] [(d/descriptor :name "event"
                                   :event-name "object-found"
                                   :object #(d/element-matches? % :category "door")
                                   :world nil)])})

(defn- recall-door-descriptor [x]
  (d/extend-descriptor mgt/door-descriptor
                       :color (object-property (-> x :arguments :patient) :color)
                       :state "locked"))

(def plan-recall-door
  "if you pickup a key, remember where the door is that the key unlocks."
  {:handle :recall-door
   :conditions [mgt/pickup-descriptor]
   :task-set (fn [x] (mgt/recall-location-schema (recall-door-descriptor x)))
   :outcome (fn [x]
              [(d/descriptor :name "adopt-navigation-target" :type "action"
                             :description (recall-door-descriptor x))])})

;;; this is unused. see note.
(def plan-navigate
  "pay attention to navigation opportunities"
  {:handle :navigate
   :conditions [(d/descriptor :name "event" :type "instance" :event-name "object-found")]
   :task-set (fn [x] (mgt/navigate-schema (-> x :arguments :object)))
   ;; NOTE: adapt this to the specific object
   ;; NOTE: the problem is that the event that matched the conditions does not have the 
   ;; location stored because it is an event from episodic memory, which only stores string
   ;; information from events (probably too limited). to get this to work, we also have the 
   ;; event store maps, so that we can extract region information. 
   :outcome (fn [x] [(d/descriptor :name "location-reached" :reason "object-path"
                                   :location [(get-in x [:arguments :location :x])
                                              (get-in x [:arguments :location :y])])])})

(def plan-navigate-key
  "navigate to a goal that was being searched for"
  {:handle :navigate-key
   :conditions [(d/descriptor :name "event" :type "instance" :event-name "object-found"
                              :object  #(d/element-matches? % :category "key"))]
   :task-set (fn [x] (mgt/navigate-schema (-> x :arguments :object)))
   :outcome (fn [x] [(d/descriptor :name "location-reached" :reason "object-path"
                                   :location [(get-in x [:arguments :location :x])
                                              (get-in x [:arguments :location :y])])])})

(def plan-navigate-door
  "navigate to a door that was being searched for"
  {:handle :navigate-door
   :conditions [(d/descriptor :name "event" :type "instance" :event-name "object-found"
                              :object #(d/element-matches? % :category "door"))]
   :task-set (fn [x]
               (mgt/navigate-schema (-> x :arguments :object)))
   :outcome (fn [x] [(d/descriptor :name "location-reached" :reason "object-path"
                                   :location [(get-in x [:arguments :location :x])
                                              (get-in x [:arguments :location :y])])])})

(def plan-navigate-goal
  "navigate to a ball that was being searched for"
  {:handle :navigate-goal
   :conditions [(d/descriptor :name "event" :type "instance" :event-name "object-found"
                              :object #(d/element-matches? % :category "ball"))]
   :task-set (fn [x] (mgt/navigate-schema (-> x :arguments :object)))
   :outcome (fn [x] [(d/descriptor :name "location-reached" :reason "object-path"
                                   :location [(get-in x [:arguments :location :x])
                                              (get-in x [:arguments :location :y])])])})

(def plan-explore
  "generic exploration activity"
  {:handle :explore
   :conditions [(d/descriptor :name "object")]
   :task-set (fn [_] (mgt/explore-schema))
   ;; never terminate for now...
   :outcome (fn [_] [(d/descriptor :name false)])})