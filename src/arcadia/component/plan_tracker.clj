(ns arcadia.component.plan-tracker
  "Focus Responsive
     No.
   
   Default Behavior
     This component is meant for tracking and debugging plan execution in ARCADIA. It can be
     added to models to see historical data on any cycle.
   
     Tracks the adoption, activation, strategy adoption, and achieved outcomes associated with
     different prospective memory items. Will display these along with cycle information to
     the scratchpad when it is active.
   
   Produces
     N/A"
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.display :as display]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;;; Data Structures
;; storage
;; {:plans-adopted [{:cycle # :elements [X]}]
;;  :outcomes-achieved [{:cycle # :elements [X]}]
;;  :plans-activated [{:cycle # :elements [X]}]
;;  :strategies-adopted [{:cycle #:elements [X]}]
;;  }

(defrecord PlanTracker [buffer parameters storage cycle]
  Component
  (receive-focus
   [component focus content]
   (display/elem "Plan Adoption Request" (:plans-adopted @storage))
   (display/elem "Plan Activation Request" (:plans-activated @storage))
   (display/elem "Outcome Achieved Request" (:outcomes-achieved @storage))
   (display/elem "Strategy Change Request" (:strategies-adopted @storage)) 
   
   (let [plans (d/filter-elements content :name "adopt-plan" :world nil)
         outcomes (d/filter-elements content :name "outcome-achieved" :world nil)
         active (d/filter-elements content :name "task-configuration" :world nil)
         strategy-change (d/filter-elements content :name "adopt-attentional-strategy" :world nil)]

     (when (seq plans)
       (reset! storage (update @storage :plans-adopted conj
                               {:cycle @cycle
                                :elements plans})))
     (when (seq outcomes)
       (reset! storage (update @storage :outcomes-achieved conj
                               {:cycle @cycle
                                :elements outcomes})))
     (when (seq active)
       (reset! storage (update @storage :plans-activated conj
                               {:cycle @cycle
                                :elements active})))
     (when (seq strategy-change)
       (reset! storage (update @storage :strategies-adopted conj
                               {:cycle @cycle
                                :elements strategy-change})))
     (swap! cycle inc)))

  (deliver-result
   [component]))

(defmethod print-method PlanTracker [comp ^java.io.Writer w]
  (.write w (format "PlanTracker{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->PlanTracker (atom nil) p (atom {}) (atom 0))))