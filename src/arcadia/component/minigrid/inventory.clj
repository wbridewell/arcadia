(ns arcadia.component.minigrid.inventory
  "Report the contents of an agent's inventory. In MiniGrid, agents
   can carry at most one item at a time. If there is anything in the 
   inventory, its details will be reported on each cycle. This is a
   form of working memory (or holding the item in the hand) and in 
   more complicated environments, inventory contents might be handled 
   through episodic memory retrieval. 
   
   Focus Responsive
     No.

   Default Behavior
     When there is an item in the agent's inventory an element will 
     be produced that contains information about that item. 
  
  Produces
    * inventory
        :contents - vector of {:category :color} maps"
  (:require [arcadia.utility.minigrid :as mg]
            [arcadia.component.core :refer [Component]]))

(defrecord MinigridInventory [buffer]
  Component
  (receive-focus
    [component focus content]
    (if-let [inventory (mg/inventory content)]
      (reset! buffer
              {:name "inventory"
               :arguments {:contents [(select-keys inventory [:category :color])]}
               :world nil
               :type "instance"})
      (reset! buffer nil)))

  (deliver-result
    [component]
    (list @buffer)))

(defn- item-string [inventory]
  (if inventory
    (str (-> inventory :arguments :color)
         " "
         (-> inventory :arguments :category))
    ""))

(defmethod print-method MinigridInventory [comp ^java.io.Writer w]
  (.write w (format "MinigridInventory{%s}" (item-string @(:buffer comp)))))

(defn start []
  (->MinigridInventory (atom nil)))