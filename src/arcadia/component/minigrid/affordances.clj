(ns arcadia.component.minigrid.affordances
  "Focus Responsive
     No.
  Default Behavior
    Outputs available movement related actions based on current location and 
    visible cells
  Produces
    minigrid-affordance
      :type - instance
      :action-name - string description of action
      :action-command - a minigrid action identifier (integer)
      :patient - an object that is the target of the action (if any)"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]))

(defn- make-affordance [action {:keys [front]}]
  (when action
    {:name "minigrid-affordance"
     :arguments {:action-command (mg/action-value action)
                 :action-name action
                 :patient (when (= action "pickup") {:name "object"
                                                     :arguments {:category (:category front)
                                                                 :color (:color front)
                                                                 :state (:state front)}})}
     :world nil
     :type "instance"}))

(defn- generate-behaviors [{:keys [front on left right]}]
  ;; you can always turn left or right
  ;; you can go forward if the space is empty or an open door
  ;; if the space in front is a closed door, you can toggle it
  ;; if the space in front is a key, you can pick it up [no inventory check]
  ;; if the space in front or that you are on is a goal, you can report success
  (into #{} (remove nil? ["left" "right"
                          (when (#{"empty"} (:category front)) "forward")
                          (when (and (= (:state front) "open") (= (:category front) "door"))
                            "forward")
                          (when (and (= (:state front) "closed") (= (:category front) "door"))
                            "toggle")
                          ;; XXX: is this a hack? sort of. if you have a key, you 
                          ;; "see" the action of unlocking the door. we should 
                          ;; investigate how to handle relational affordances and 
                          ;; higher-level actions at a later date.
                          (when (and (= (:state front) "locked") (= (:category front) "door")
                                     (= (:category on) "key")
                                     (= (:color on) (:color front)))
                            "toggle")
                          (when (= (:category front) "key") "pickup")
                          (when (some #{"goal"} [(:category on) (:category front)])
                            "done")])))

(defn- delay-pipeline? [focus content]
  ;; conditions that slow down the pipeline because it takes several steps for actions to 
  ;; be selected and executed.
  ;; this is what counts as durative action in minigrid environments. all actions still
  ;; take the same number of cycles.
  (or (d/first-element content :type "action" :name "minigrid-action" :world nil)
      (d/first-element content :type "instance" :name "weighted-affordance")
      (d/first-element content :type "environment-action" :name "minigrid-env-action")
      (d/first-element content :type "instance" :name "minigrid-affordance")))

(defrecord MinigridAffordances [buffer]
  Component
  (receive-focus
    [component focus content]
   ;; if an action is being selected and/or executed, do not provide any affordances. 
    (if (delay-pipeline? focus content)
      (reset! buffer nil)
      (when-let [mg-perception (d/first-element content :name "minigrid-perception")]
        (reset! buffer
                (map #(make-affordance % (-> mg-perception :arguments :adjacency-info))
                     (generate-behaviors (-> mg-perception :arguments :adjacency-info)))))))

  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridAffordances [comp ^java.io.Writer w]
  (.write w (format "MinigridAffordances{}")))

(defn start []
  (->MinigridAffordances (atom nil)))
