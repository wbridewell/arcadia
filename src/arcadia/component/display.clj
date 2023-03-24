(ns arcadia.component.display
  "This component displays information about the model's state. It is highly
   configurable, and it can display both text and images. For preconfigured display
   setups, see arcadia.component.parameter-configurations. Note that this component
   is a tool to support debugging and demos, and it should not affect the model's
   behavior.

  Focus Responsive
    Yes

  Displays elements of information describing the model's state.

  Default Behavior
    Update the display according to what was produced on the latest cycle.

  Produces
    Nothing

  Displays
    One or more panels, organized in a 2D grid. Each panel contains elements (either
    images or text) corresponding to items of information. Elements are built from
    the :element parameter, which takes a list of
    [function] vectors, where the function is defined using the
    utility.display/information-fn or i# macro, or optionally,
    [caption function :param-name1 param-value1 ...]
    tuples, where the caption is a string that appears to the left of the element,
    and the additional, optional items specify updates to the default
    parameters just for displaying that particular element."
  (:import [javax.swing Box JFrame JLabel JPanel JScrollPane]
           [javax.swing.border EmptyBorder]
           [java.awt Color Container Dimension
            GraphicsEnvironment
            GridBagConstraints GridBagLayout Insets])
  (:require [arcadia.architecture.registry :as reg]
            [arcadia.component.core :refer [Component Display Logger Registry-Accessor
                                            merge-parameters]]
            [arcadia.utility.colors :as colors]
            [arcadia.utility [display :as display] [swing :as swing]]
            [arcadia.display [formatting :as dfmt] [support :as dsup]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for setting up the top-level frame in which things will appear
(def ^:parameter display-name "name of display window" "Debug Display")
(def ^:parameter x "x position of display window" 100)
(def ^:parameter y "y position of display window" 100)
(def ^:parameter rows "number of rows in the display; if this is nil, then it
  will be calculated based on :cols and the number of :panels" nil)
(def ^:parameter cols "number of cols in the display" nil)

(def ^:parameter instant-updates? "If this is true, update the display frame
 immediately whenever a debug signal is sent to this component with the
 log-information! function." false)

(def ^:parameter number-updates? "If this is true, then caption each instant update
  with a number. The number starts at 0 (which will not be displayed) and increments
  whenever there is an update, provided :increment-update-number? is true." false)

(def ^:parameter increment-update-number? "If :number-updates? is true, then increment
  the number before displaying it for a new update. Note that the number starts
  at 0 and must increment at least once (to 1) before it will be displayed." false)

(def ^:parameter refresh-every-cycle? "If this is true, then every cycle we'll
  clear out whatever's in the debug display and update based on the current state
  of ARCADIA, and this will happen immediately before broadcast-focus. If this
  is false, then we'll only change the display in response to debug signals (so
  :instant-updates? should be set to true)." true)

(def ^:parameter monitor "If there are multiple monitors, this will be used to
  select which one to display on. This can be an index for the array of monitors,
  or it can be :largest, which indicates that the largest monitor should be used."
  :largest)

(def ^:parameter previous-state "If this value is non-nil, it should contain the
  data structure for the entire component, and it will be used instead of setting
  up a new instance of the component." nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for displaying information about the visual input
(def ^:parameter sensor "a sensor that provides visual input" nil)
(def ^:parameter sensor-hash "a hashmap of sensors that provide visual input" nil)
(def ^:parameter sensor-delay "Add a delay of this many cycles before images
  polled from the sensors are displayed by debug display components." 1)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters used to extract the information that will be displayed
(def ^:parameter panels "List of [<optional-header> panel
  <optional-param-name> <optional-param-value> ...] vectors describing the panels
  for information that will be displayed. There can be any number of param-name/
  param-value pairs." [[nil]])

(def ^:parameter elements "List of [<optional-caption> element
  <optional-param-name> <optional-param-value> ...] vectors describing the elements
  of information that will be displayed. There can be any number of param-name/
  param-value pairs." [[(display/i# (:panel %))]])

(def ^:parameter glyphs "List of [glyph
  <optional-param-name> <optional-param-value> ...] vectors describing the glyphs
  that will be draw on elements that are images. There can be any number of
  param-name/param-value pairs." nil)

(def ^:parameter flatten-panels? "If a panel resolves to a list, then
  flatten that list, treating each item in the list as a separate panel.
  This also means that if a panel resolves to nil, it should not be displayed
  at all." false)

(def ^:parameter flatten-elements? "If an element resolves to a list, then
  flatten that list, treating each item in the list as a separate element.
  This also means that if an element resolves to nil, it should not be displayed
  at all." false)

(def ^:parameter initial-panels "List of panels that will appear before
  the main list." nil)

(def ^:parameter runtime-panels "List of panels that will appear after
  the main list." nil)

(def ^:parameter initial-elements "List of elements that will appear before
  the main list." nil)

(def ^:parameter runtime-elements "List of elements that will appear after
  the main list." nil)

(def ^:parameter initial-glyphs "List of glyphs that will appear before
  the main list." nil)

(def ^:parameter runtime-glyphs "List of glyphs that will appear after
  the main list." nil)

(def ^:parameter element-value "Typically an information function that can
  be called on the element to return the actual thing that should be displayed."
  (display/i# (:element %)))

(def ^:parameter glyph-value "Typically an information function that can
  be called on the glyph to return the actual thing that should be displayed."
  (display/i# (:glyph %)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters used for setting up panels, the JPanels in which elements of
;;information will appear
(def ^:parameter panel-width "width of panel in the display" nil)
(def ^:parameter panel-height "height of each panel; if this is nil, then
  it will be calculated based on the dimensions of the :elements in each of the
  :panels" nil)
(def ^:parameter panel-rows "number of rows of elements in each panel;
  can be specified instead of panel-height" nil)
(def ^:parameter extra-panel-rows "Leave room for this many rows of elements
  after the elements indicated by the :elements parameter." 0)
(def ^:parameter panel-background "background color for panels"
  (Color. 230 230 230))
(def ^:parameter headers? "Indicates whether panels are expected to have
  headers. This is helpful for precomputing the expected height of panels.
  If this value is anything other than true or false, it is treated as unknown."
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters used for setting up elements of information
(def ^:parameter element-type "Indicates whether an element should be displayed
 as :text or as an :image. If this value is nil, then an element will be displayed
 as an image if possible, and otherwise it will be displayed as text." :text)
(def ^:parameter element-rows "For text elements, assume each element will be
 this many rows of text when determining the height of panels." 1)
(def ^:parameter caption-width "width of captions (nil = use the minimum width
  needed to fit the caption on one line)" nil)
(def ^:parameter show-sources? "Display the source file/line number from which
  each element originated?" false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for formatting image elements
(def ^:parameter image-width "Width of images. If this is nil, the the width
  may be inferred from :sensor + :image-scale or :sensor + :image-height. If
  those are not provided, images will be drawn at their original size." nil)
(def ^:parameter image-height "Height of images. If this is nil, the the height
  may be inferred from :sensor + :image-scale or :sensor + :image-width. If
  those are not provided, images will be drawn at their original size." nil)
(def ^:parameter image-scale "Image scale, compared to the size of the sensor
  image. Can be used to get image-width and image-height. If this is nil and
  :image-width and :image-height aren't supplied directly, images will be drawn
  at their original size." nil)
(def ^:parameter copy-image? "Make a copy of an image before formatting it?"
  true)
(def ^:parameter interpolation-method "If this is non-nil, it should be an
   interpolation method found in utility/opencv.clj, for example opencv/INTER_LINEAR.
   This specifies how pixels should be interpolated when an image is resized to the
   desired width/height. Note that this method will be used only for resizing
   opencv matrices, not for resizing java buffered images." nil)
(def ^:parameter image-format-fn "If this is non-nil, call this function on
  images before displaying them. The function should be non-desctructive, to
  avoid changing the underlying data. Note that this function will only be called
  on opencv Mats that haven't yet been converted to BufferedImages." nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for formatting text elements
;;These are are in triplets, with a parameter for the text in the element, and
;;corresponding parameters for the text in the caption and header. Note that
;;when caption or header parameters are nil, the corresponding text parameters
;;are used in their place.
(def word-wrap? "Should word wrapping be enabled? Note that text can
  wrap only at spaces between words. NOTE: Word wrap is not working currently." false)
(def caption-word-wrap? "Should word wrapping be enabled for captions?
  Note that text can wrap only at spaces between words. NOTE: word wrap is not working currently." nil)
(def header-word-wrap? "Should word wrapping be enabled for headers?
  Note that text can wrap only at spaces between words. NOTE: word wrap is not working currently." nil)
(def ^:parameter center? "Center text?" false)
(def ^:parameter caption-center? "Center captions?" nil)
(def ^:parameter header-center? "Center headers?" true)
(def ^:parameter color "Color of text (nil = default). Can be a keyword like :red or :blue or a 
                        [r g b] vector (byte format)."
  nil)
(def ^:parameter caption-color
  "Color of captions (nil = default). Can be a keyword like :red or :blue or a [r g b] vector 
   (byte format)." nil)
(def ^:parameter header-color
  "Color of headers (nil = default). Can be a keyword like :red or :blue or a  [r g b] vector 
   (byte format)." nil)
(def ^:parameter text-background
  "Color of text backgrounds (nil = none). Can be a keyword like :red or :blue or a [r g b] 
   vector (byte format)." nil)
(def ^:parameter caption-background
  "Color of caption backgrounds (nil = none). Can be a keyword like :red or :blue or a [r g b] 
   vector (byte format)." nil)
(def ^:parameter header-background
  "Color of caption backgrounds (nil = none). Can be a keyword like :red or :blue or a [r g b] 
   vector (byte format)." [180 180 180])
(def ^:parameter bold? "Should text be bold?" false)
(def ^:parameter caption-bold? "Should captions be bold?" nil)
(def ^:parameter header-bold? "Should headers be bold?" nil)
(def ^:parameter italic? "Should text be italic?" false)
(def ^:parameter caption-italic? "Should captions be italic?" nil)
(def ^:parameter header-italic? "Should headers be italic?" nil)
(def ^:parameter underline? "Should text be underlined?" false)
(def ^:parameter caption-underline? "Should captions be underlined?" nil)
(def ^:parameter header-underline? "Should headers be underlined?" nil)
(def ^:parameter strike-through? "Should text be struck through?" false)
(def ^:parameter caption-strike-through? "Should captions be struck through?" nil)
(def ^:parameter header-strike-through? "Should headers be struck through?" nil)
(def ^:parameter font-family "font family for text (nil = default)" nil)
(def ^:parameter caption-font-family "font family for captions (nil = default)" nil)
(def ^:parameter header-font-family "font family for headers (nil = default)" nil)
(def ^:parameter text-size "size for text (nil = 1.0 = default)" nil)
(def ^:parameter caption-size "size for captions (nil = 1.0 = default)" nil)
(def ^:parameter header-size "size for headers (nil = 1.0 = default)" nil)
(def ^:parameter indent "indentation for text" 4)
(def ^:parameter caption-indent "indentation for captions" nil)
(def ^:parameter header-indent "indentation for headers" nil)
(def ^:parameter element-spacing "spacing around each element" 6)
(def ^:parameter caption-spacing "spacing around each caption" nil)
(def ^:parameter header-spacing "spacing around each header" nil)
(def ^:parameter paragraph-spacing "spacing between paragraphs within a single
  text element" 0)
(def ^:parameter caption-paragraph-spacing "spacing between paragraphs within a
  caption" 0)
(def ^:parameter header-paragraph-spacing "spacing between paragraphs within a
  caption" 0)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for formatting text to better display data.
;;Note that with the exception of precision/pretty-points? these 
;;apply only to elements and not to headers/captions.
(def ^:parameter precision "If this is non-nil, then round any numbers to this
 many digits after the decimal point before displaying them." nil)
(def ^:parameter pretty-points? "If this is true, then display anything that looks
  like a 2D point as (x, y)." true)
(def ^:parameter display-fn "If this is non-nil, then it should be a function that 
  that can be applied to a piece of data. It will return a string that will be displayed
  in the place of that data." nil)
(def ^:parameter smart-indent? "If this is true, then data structures will
  be indented in a way that makes them easy to inspect. This works best if
  :font-family is \"monospace\", or you can just set :pretty-data? to true, to
  get both of these effects at once." false)
(def ^:parameter one-per-line "List of empty collections. Collections
  of these types will have their items displayed one-per-line if :smart-indent?
  is true. For example, '([] ()) would mean the items in vectors and sequences
  will be displayed one per line." '())
(def ^:parameter key-color "Color for keys in hash-maps. Applies only to simple
  literals, and can be overriden by type-specific color-coding." nil)
(def ^:parameter value-color "Color for values in hash-maps. Applies only to simple
  literals, and can be overriden by type-specific color-coding." nil)
(def ^:parameter link-color "Color for links used to browse through data. Only
   applicable if :browsable? is true." :red)
(def ^:parameter simple-literal-color "Print simple literals (short values, basically
  everything except non-empty collections, Java objects, and functions) in
  this color." nil)
(def ^:parameter keyword-color "Print keywords in this color. Overrides
  simple-literal-color if non-nil." nil)
(def ^:parameter number-color "Print numbers in this color. Overrides
  simple-literal-color if non-nil." nil)
(def ^:parameter string-color "Print strings in this color. Overrides
  simple-literal-color if non-nil." nil)
(def ^:parameter pretty-data? "If this is true, configure several other parameters
  to make it easier to inspect large Clojure data structures." false)
(def ^:parameter excluded-keys "Exclude these keys from any hashmaps that are
  displayed" nil)
(def ^:parameter browsable? "Is displayed textual data browsable, meaning you can
  click through it?" false)
(def ^:parameter visualizable? "Is displayed textual data visualizable, meaning you
  control-click on it to display a visualization?" true)
(def ^:parameter collapsed? "Should the data to be displayed be collapsed
  initially, requiring that the user click on it to expand it?" false)
(def ^:parameter max-depth "What is the maximum depth (in terms of nested
  collections) at which data will be displayed?" 1)
(def ^:parameter max-collection-size "Display at most this many items from a
  collection." 80)
(def ^:parameter visualization-scale "Scale at which the background image should
  be displayed when using it as a backdrop for visualizations." 0.5)
(def ^:parameter visualization-color "Color used when visualizing data."
  :red)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Parameters for formatting glyphs that get drawn onto images.

(def ^:parameter scale-glyphs? "Should glyphs scale with the image upon which
  they are drawn? For example, if the image is displayed at half scale, should
  any glyphs also be drawn at half scale?" true)
(def ^:parameter fill-color "fill color for glyphs, if any" nil)
(def ^:parameter alpha "alpha value for glyphs" 1.0)
(def ^:parameter line-width "line width for glyphs" 2)
(def ^:parameter shape-scale "Scale shapes by this factor before drawing them."
  1.0)
(def ^:parameter shape "Shape for those glyphs that can take a shape. Options
  are :rectangle, :oval, :cross, :x. By default, a shape will be selected based
  on the glyph." nil)
(def ^:parameter x-offset "Offset the x coordinate of a glyph by this amount."
  0)
(def ^:parameter y-offset "Offset the x coordinate of a glyph by this amount."
  0)
(def ^:parameter glyph-width "width to use when none is available" 30)
(def ^:parameter glyph-height "height to use when none is available" 30)
(def ^:parameter glyph-x "x value to use when none is available" 40)
(def ^:parameter glyph-y "y value to use when none is available" 40)
(def ^:parameter glyph-region "Region that can provide x, y, width, or
 height for the glyph. Will be used to overwrite :glyph-x, :glyph-y,
 :glyph-width, or :glyph-height if it is non-nil."
  nil)
(def ^:parameter glyph-mask "A mask for determining alpha values when converting
  a matrix to a BufferedImage." nil)

(def ^:parameter icon-width "Width of pause/play/step/stop icons in a control display"
  50)

(def ^:private spacing "spacing between panels, should be even" 6)
(def ^:private buffer "extra space added to the window's width and height so
 that there's room for the scrollpanes...might need to be adjusted if insets of
 panels are ever changed" 0);4)
(def default-frame-background "background of the display frame, but
  not the panels" (Color. 240 240 240))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Code for handling the display when there are breakpoints

(defn- scroll-down
  "This is called on a top-level container and it traces down through all the child
   components. If any components are JScrollPanes, then scroll down as far as
   possible."
  [comp]
  (when (= (type comp) JScrollPane)
    (let [bar (.getVerticalScrollBar comp)]
      (.setValue bar (.getMaximum bar))))

  (when (instance? Container comp)
    (doseq [child (seq (.getComponents comp))]
      (scroll-down child))))

(defn- update-display-for-breakpoints
  "Change the display name and scroll down if necessary"
  [frame params]
  (scroll-down frame)
  (.setTitle frame (str (:display-name params) " (HALTED)"))
  (.repaint frame))

(defn- update-display-after-breakpoints
  "After we've passed a breakpoint, change the display name back."
  [frame params]
  (.setTitle frame (:display-name params))
  (.repaint frame))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- monitor-loc
  "Returns the [min-x min-y] location of the monitor on which we will be displaying."
  [{ind :monitor :as parameters}]
  (let [monitors (.getScreenDevices (GraphicsEnvironment/getLocalGraphicsEnvironment))
        n (count monitors)]
    (cond
      (or (= n 1) (and (number? ind) (>= ind n)))
      [0 0]

      (number? ind)
      (-> (aget monitors ind) (.getDefaultConfiguration) (.getBounds)
          (#(vector (.getX %) (.getY %))))

      (= ind :largest)
      (-> (sort-by #(.. % getDefaultConfiguration getBounds getWidth) > monitors)
          first (.getDefaultConfiguration) (.getBounds)
          (#(vector (.getX %) (.getY %)))))))

(defn- prepare-frame
  "Creates the JFrame that will display debugging information."
  [f [monitor-x monitor-y] params]
  (let [main-p (JPanel.)]
    (.setVisible main-p true)

    (let [scroll-p (JScrollPane. main-p)]
      (.setPreferredSize
       (.getViewport scroll-p)
       (Dimension. (* (:cols params) (+ (:panel-width params) spacing buffer))
                   (* (:rows params) (+ (:panel-height params) spacing buffer))))
      (.add (.getContentPane f) scroll-p))

    ;;Make the frame invisible before positioning it, so that it can potentially
    ;;be placed outside the bounds of the screen. This is preferable for
    ;;recording videos because we don't want frames to be crammed together in
    ;;the video.
    ;;NOTE: Not sure this works anymore. --AML, 2/23/23
    (.setVisible f false)
    (.setLocation f (+ monitor-x (:x params)) (+ monitor-y (:y params)))
    (.pack f)
    (.setVisible f true)
    [f main-p]))

(defn- add-glue
  "Add glue to push everything to the left and to the top of the frame."
  [panel x y]
  (let [glue-c (GridBagConstraints.)]
    (set! (.gridx glue-c) x)
    (set! (.gridy glue-c) y)
    (set! (.weightx glue-c) 1)
    (set! (.weighty glue-c) 1)
    (set! (.anchor glue-c) GridBagConstraints/LAST_LINE_END)
    (.add panel (Box/createVerticalGlue) glue-c)))

(defn- element-comp
  "Computes a component to display an element of information."
  [element element-metadata debug-data params]
  (let [comp (dfmt/get-element-comp element element-metadata debug-data params)]
    (cond
      (seq? comp)
      (let [panel (JPanel.)
            c (GridBagConstraints.)]
        (.setLayout panel (GridBagLayout.))
        (.setBackground panel nil)

        (set! (.gridx c) GridBagConstraints/RELATIVE)
        (set! (.gridy c) 0)

        (doseq [single-comp comp]
          (.add panel single-comp c))
        (add-glue panel (count comp) 0)
        panel)

      (nil? comp)
      (JLabel.)

      :else comp)))

(defn- handle-numbers
  "Checks whether this update should be captioned with a number, and captions it if
   so."
  [{element :element params :params :as info} update-number]
  (when (and (:number-updates? params) (:increment-update-number? params))
    (swap! update-number inc))

  (if (and (:number-updates? params) (pos? @update-number))
    (assoc info :caption @update-number)
    info))

(defn- element-panel-or-comp
  "Computes a panel to display en element of information, if necessary (if there's
   a caption). Otherwise, just calls element-comp."
  [{element :element metadata :metadata params :params :as element-full}
   debug-data params]
  (if-let [caption (dsup/caption-string element-full)]
    (let [jpanel (JPanel.)
          c (GridBagConstraints.)
          caption (dfmt/get-text-comp caption true false metadata debug-data params)]
      (.setBackground jpanel nil)
      (.setLayout jpanel (GridBagLayout.))
      (set! (.anchor c) GridBagConstraints/FIRST_LINE_START)
      (set! (.gridx c) 0)
      (set! (.gridy c) 0)
      (.add jpanel caption c)
      (set! (.gridx c) 1)
      (.add jpanel
            (element-comp element metadata debug-data
                          (assoc params :caption-width
                                 (.width (.getPreferredSize caption))))
            c)
      (add-glue jpanel 2 0)
      jpanel)

    ;;If no caption
    (element-comp element metadata debug-data (assoc params :caption-width 0))))

(defn- setup-panel
  "Sets up a panel to display its elements."
  [{header :header params :params :as panel} debug-data update-number solo-panel?]
  (let [jpanel (JPanel.)
        c (GridBagConstraints.)]
    (.setLayout jpanel (GridBagLayout.))

    (set! (.fill c) GridBagConstraints/HORIZONTAL)
    (set! (.gridx c) 0)
    (set! (.gridy c) GridBagConstraints/RELATIVE)
    (set! (.weighty c) 0)
    (set! (.anchor c) GridBagConstraints/FIRST_LINE_START)
    (when (contains? panel :header)
      (.add jpanel (dfmt/get-text-comp header false true nil debug-data params) c))

    ;;Add all the elements to the panel
    (doseq [element (:elements params)]
      (.add jpanel
            (-> element (handle-numbers update-number)
                (element-panel-or-comp debug-data params))
            c))

    ;;Add some glue at the end to push everything to the top left position
    (add-glue jpanel 1 (+ (count (:elements params))
                          (if (contains? panel :header)
                            1 0)))
    (if solo-panel?
      (.setBackground jpanel nil)
      (.setBackground jpanel (some-> (:panel-background params) colors/->java)))

    (if solo-panel?
      jpanel
      (let [scroll-p (JScrollPane. jpanel)]
        (.setPreferredSize scroll-p;(.getViewport scroll-p)
                           (Dimension. (:panel-width params)
                                       (:panel-height params)))
        (.setBorder scroll-p (EmptyBorder. 0 0 0 0))
        scroll-p))))

(defn- update-frame
  "Updates frame and jpanel to display the panels found in params."
  [frame jpanel debug-data update-number params]
  (swing/invoke-now
   (let [c (GridBagConstraints.)
         cols (:cols params)
         panels (:panels params)
         n (count panels)
         solo-panel?
         (and (= n 1) (= (:rows params) 1) (= (:cols params) 1))]

     (when (:variable-display-name? params)
       (.setTitle frame (str (:variable-display-name params))))

     (.removeAll jpanel)
     (.setLayout jpanel (GridBagLayout.))
     (set! (.insets c) (Insets. (/ spacing 2)
                                (/ spacing 2)
                                (/ spacing 2)
                                (/ spacing 2)))

     (if solo-panel?
       (.setBackground jpanel (some-> panels first :params :panel-background colors/->java))
       (.setBackground jpanel default-frame-background))

     (dotimes [i n]
       (let [panel (nth panels i)]
         (set! (.gridx c) (mod i cols))
         (set! (.gridy c) (int (/ i cols)))
         (.add jpanel (setup-panel panel debug-data update-number solo-panel?)
               c)))

     (add-glue jpanel (min cols n) (Math/ceil (/ n cols)))
     (.pack frame)
     (.repaint frame))))

(defn- update-frame-with-new-element
  "If we've just gained a new element, and we have a simple, single-panel display,
   then it's more efficient to add the element to the existing panel."
  [frame jpanel debug-data new-info update-number params]
  (if (and (= (count (:panels params)) 1) (= (:rows params) 1) (= (:cols params) 1)
           (contains? new-info :element) (:refresh-every-cycle? params)
           (seq (.getComponents jpanel)))
    (swing/invoke-now
     (let [jpanel (first (.getComponents jpanel))
           {header :header params :params :as panel} (first (:panels params))
           elements (:elements params)
           c (GridBagConstraints.)
           old-components (.getComponents jpanel)
           old-glue-y (dec (count old-components))
           comp-base (if (contains? panel :header) 1 0)
           new-glue-y (+ (count elements) comp-base)]

       (when (> new-glue-y old-glue-y)
         ;;Remove the old glue
         (.remove jpanel (last old-components))
         (set! (.fill c) GridBagConstraints/HORIZONTAL)
         (set! (.anchor c) GridBagConstraints/FIRST_LINE_START)
         (set! (.weighty c) 0)
         (set! (.gridx c) 0)

         ;;Add in all the new elements (there's only one new item of information,
         ;;but it may have resolved to a list of multiple elements).
         (doseq [y (range old-glue-y new-glue-y)]
           (set! (.gridy c) y)
           (let [element (nth elements (- y comp-base))]
             (.add jpanel
                   (-> element (handle-numbers update-number)
                       (element-panel-or-comp debug-data params))
                   c)))

         ;;Add some glue at the end to push everything to the top left position
         (add-glue jpanel 1 new-glue-y)

         (.pack frame)
         (.repaint frame))))

    (update-frame frame jpanel debug-data update-number params)))

(defn- add-runtime-information
  "Updates the parameters with any items of information that have been added at
   runtime."
  [params info]
  (assoc params :runtime-panels (filter #(contains? % :panel) info)
         :runtime-elements (filter #(contains? % :element) info)
         :runtime-glyphs (filter #(contains? % :glyph) info)))

(defrecord DebugDisplay [frame jpanel debug-data registry runtime-info prev-runtime-info
                         focus content halted? update-number params]
  Display
  (frame [component] (:frame component))

  Logger
  (log-information!
    [component info]
    (swap! (:runtime-info component) conj info)

    (when (:instant-updates? params)
     ;;Update our display based on the information that was just added
      (update-frame-with-new-element
       frame jpanel debug-data info update-number
       (dsup/resolve-parameters
        (add-runtime-information params (reverse @(:runtime-info component)))
        @focus @content @registry debug-data))

     ;;If we just added a breakpoint and didn't step past it, we'll need to
     ;;halt until the user indicates we can continue.
      (when @halted?
        (swing/invoke-now (update-display-for-breakpoints frame params))

        (loop []
          (Thread/sleep 40)
          (when @halted?
            (recur)))
        (swing/invoke-now (update-display-after-breakpoints frame params)))))

  (reset-logger!
    [component focus content]
    (dsup/update-debug-data! (:debug-data component))
    (reset! (:focus component) focus)
    (reset! (:content component) content)
    (reset! (:prev-runtime-info component) (reverse @(:runtime-info component)))
    (reset! (:runtime-info component) nil))

  (update-logger!
    [component]
    (when (or (:refresh-every-cycle? params)
              (< (-> debug-data deref :cycle) 2))
      (update-frame
       frame jpanel debug-data update-number
       (dsup/resolve-parameters
        (if (:instant-updates? params)
          params
          (add-runtime-information params @(:prev-runtime-info component)))
        @focus @content @registry debug-data))))

  Registry-Accessor
  (update-registry!
    [component registry]
    (reset! (:registry component) registry))

  Component
  (receive-focus
    [component focus content])

  (deliver-result
    [component]))

(defmethod print-method DebugDisplay [comp ^java.io.Writer w]
  (.write w "DebugDisplay"))

(defn start [& {:as args}]
  (or (:previous-state args)
      (let [p (as-> (merge-parameters args) p
                (dsup/clone-parameters p) ;;clone any atoms, so they'll be distinct from the ones in the model file
                (dsup/initialize-parameters true p)
                (assoc p :panel-width (dfmt/get-max-panel-width p))
                (assoc p :panel-height (dfmt/get-max-panel-height p))
                (merge p (dfmt/get-cols-and-rows p))
                (assoc p
                       :variable-display-name (:display-name p)
                       :variable-display-name? (dsup/unresolved? (:display-name p))
                       :display-name (if (dsup/unresolved? (:display-name p))
                                       "" (:display-name p))))
            halted? (atom false)
            atoms (assoc (reg/control-atoms) :halted? halted? :break-steps (atom 0))
            [frame jpanel] (swing/invoke-now
                            (prepare-frame (JFrame. (:display-name p))
                                           (monitor-loc p) p))]
        (->DebugDisplay
         frame jpanel (dsup/initialize-debug-data p atoms) (atom nil) (atom nil)
         (atom nil) (atom nil) (atom nil) halted? (atom 0) p))))
