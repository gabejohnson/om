(ns om.next
  (:refer-clojure :exclude [var? key])
  (:require-macros [om.next :refer [defui]])
  (:require [goog.string :as gstring]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [goog.log :as glog]
            [clojure.walk :as walk]
            [om.next.protocols :as p]
            [om.next.impl.parser :as parser]
            [om.next.impl.refs :as refs]
            [om.next.cache :as c])
  (:import [goog.debug Console]))

(defonce *logger*
  (when ^boolean goog.DEBUG
    (.setCapturing (Console.) true)
    (glog/getLogger "om.next")))

;; =============================================================================
;; Globals & Dynamics

(def ^:private roots (atom {}))

(def ^{:dynamic true} *raf* nil)

(def ^{:dynamic true :private true} *reconciler* nil)

(def ^{:dynamic true :private true} *root-class* nil)

(def ^{:dynamic true :private true} *parent* nil)

(def ^{:dynamic true :private true} *shared* nil)

(def ^{:dynamic true :private true} *instrument* nil)

(def ^{:dynamic true :private true} *depth* 0)

;; =============================================================================
;; Utilities

(defn ^boolean nil-or-map? [x]
  (or (nil? x) (map? x)))

(defn focus-query [sel path]
  (if (empty? path)
    sel
    (let [[k & ks] path]
      (letfn [(match [x]
                (let [k' (if (map? x) (ffirst x) x)]
                  (= k k')))
              (value [x]
                (if (map? x)
                  {(ffirst x) (focus-query (-> x first second) ks)}
                  x))]
        (into [] (comp (filter match) (map value)) sel)))))

(defn focus->path
  ([focus] (focus->path focus []))
  ([focus path]
   {:pre [(vector? focus)]}
   (if (and (some map? focus)
            (== 1 (count focus)))
     (let [[k focus'] (ffirst focus)]
       (recur focus' (conj path k)))
     path)))

;; =============================================================================
;; Query Protocols & Helpers

(defprotocol IQueryParams
  (params [this] "Return the query parameters"))

(extend-type default
  IQueryParams
  (params [_]))

(defprotocol IQuery
  (query [this] "Return the component's unbound query"))

(defprotocol ILocalState
  (-set-state! [this new-state])
  (-get-state [this])
  (-get-rendered-state [this])
  (-merge-pending-state! [this]))

(defn var? [x]
  (and (symbol? x)
       (gstring/startsWith (name x) "?")))

(defn var->keyword [x]
  (keyword (.substring (name x) 1)))

(defn bind-query [query params]
  (letfn [(replace-var [node]
            (if (var? node)
              (get params (var->keyword node) node)
              node))]
    (walk/prewalk replace-var query)))

(defn get-query
  "Return a component's bound query."
  [class]
  (with-meta (bind-query (query class) (params class))
    {:component class}))

(defn iquery? [x]
  (satisfies? IQuery x))

;; =============================================================================
;; React Bridging

(defn- compute-react-key [cl props]
  (if-let [rk (:react-key props)]
    rk
    (if-let [idx (:om-index props)]
      (str (. cl -name) "_" idx)
      js/undefined)))

(defn create-factory
  "Create a factory constructor from a component class created with
   om.next/defui."
  [class]
  {:pre [(fn? class)]}
  (fn [props & children]
    (if *instrument*
      (apply *instrument* props children)
      (js/React.createElement class
        #js {:key (compute-react-key class props)
             :omcljs$value props
             :omcljs$index (:om-index props)
             :omcljs$reconciler *reconciler*
             :omcljs$rootClass *root-class*
             :omcljs$parent *parent*
             :omcljs$shared *shared*
             :omcljs$instrument *instrument*
             :omcljs$depth *depth*
             :omcljs$t (if *reconciler* (p/basis-t *reconciler*) 0)}
        children))))

(defn ^boolean component?
  "Returns true if the argument is an Om component."
  [x]
  (. x -om$isComponent))

(defn- state [c]
  {:pre [(component? c)]}
  (.-state c))

(defn get-prop
  "PRIVATE: Do not use"
  [c k]
  (gobj/get (.-props c) k))

(defn set-prop!
  "PRIVATE: Do not use"
  [c k v]
  (gobj/set (.-props c) k v))

(defn get-reconciler
  [c]
  {:pre [(component? c)]}
  (get-prop c "omcljs$reconciler"))

(defn t
  "Get basis t value for when the component last read its props from the
   global state."
  [c]
  (let [cst (.-state c)
        cps (.-props c)]
    (if (nil? cst)
      (gobj/get cps "omcljs$t")
      (let [t0 (gobj/get cst "omcljs$t")
            t1 (gobj/get cps "omcljs$t")]
        (max t0 t1)))))

(defn root-class
  [component]
  (get-prop component "omcljs$rootClass"))

(defn parent
  "Returns the parent Om component."
  [component]
  (get-prop component "omcljs$parent"))

(defn depth
  "Returns the render depth (a integer) of the component relative to the
  mount root."
  [component]
  (get-prop component "omcljs$depth"))

(defn react-key
  "Returns the components React key."
  [component]
  (.. component -props -key))

(defn react-type
  "Returns the component type, regardless of whether the component has been
   mounted"
  [x]
  (or (gobj/get x "type") (type x)))

(defn index
  "Returns the component's Om index."
  [c]
  (get-prop c "omcljs$index"))

(defn shared [component]
  {:pre [(component? component)]}
  (get-prop component "omcljs$shared"))

(defn instrument [component]
  {:pre [(component? component)]}
  (get-prop component "omcljs$instrument"))

(defn update-props! [c next-props]
  {:pre [(component? c)]}
  (gobj/set (.-state c) "omcljs$t" (p/basis-t (get-reconciler c)))
  (gobj/set (.-state c) "omcljs$value" next-props))

(defn props
  "Return a components props."
  [component]
  {:pre [(component? component)]}
  (let [cst (.-state component)
        cps (.-props component)]
    (if (nil? cst)
      (gobj/get cps "omcljs$value")
      (let [t0 (gobj/get cst "omcljs$t")
            t1 (gobj/get cps "omcljs$t")]
        (if (> t0 t1)
          (gobj/get cst "omcljs$value")
          (gobj/get cps "omcljs$value"))))))

(defn set-state!
  "Set the component local state of the component. Analogous to React's
   setState."
  [component new-state]
  {:pre [(component? component)]}
  (if (satisfies? ILocalState component)
    (-set-state! component new-state)
    (gobj/set (.-state component) "omcljs$pendingState" new-state))
  (if-let [r (get-reconciler component)]
    (p/queue! r [component])
    (.forceUpdate component)))

(defn get-state
  "Get a component's local state. May provide a single key or a sequential
   collection of keys for indexed access into the component's local state."
  ([component]
   (get-state component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [cst (if (satisfies? ILocalState component)
               (-get-state component)
               (when-let [state (. component -state)]
                 (or (gobj/get state "omcljs$pendingState")
                     (gobj/get state "omcljs$state"))))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(defn update-state!
  "Update a component's local state. Similar to Clojure(Script)'s update-in."
  ([component f]
   (set-state! component (f (get-state component))))
  ([component f arg0]
   (set-state! component (f (get-state component) arg0)))
  ([component f arg0 arg1]
   (set-state! component (f (get-state component) arg0 arg1)))
  ([component f arg0 arg1 arg2]
   (set-state! component (f (get-state component) arg0 arg1 arg2)))
  ([component f arg0 arg1 arg2 arg3]
   (set-state! component (f (get-state component) arg0 arg1 arg2 arg3)))
  ([component f arg0 arg1 arg2 arg3 & arg-rest]
   (set-state! component
     (apply f (get-state component) arg0 arg1 arg2 arg3 arg-rest))))

(defn get-rendered-state
  "Get the rendered state of component. om.next/get-state always returns the
   up-to-date state."
  [component]
  {:pre [(component? component)]}
  (if (satisfies? ILocalState component)
    (-get-rendered-state component)
    (some-> component .-state (gobj/get "omcljs$state"))))

(defn merge-pending-state! [c]
  (if (satisfies? ILocalState c)
    (-merge-pending-state! c)
    (when-let [pending (some-> c .-state (gobj/get "omcljs$pendingState"))]
      (let [state    (.-state c)
            previous (gobj/get state "omcljs$state")]
        (gobj/remove state "omcljs$pendingState")
        (gobj/set state "omcljs$previousState" previous)
        (gobj/set state "omcljs$state" pending)))))

(defn react-set-state!
  ([component new-state]
   (react-set-state! component new-state nil))
  ([component new-state cb]
   {:pre [(component? component)]}
   (.setState component #js {:omcljs$state new-state} nil)))

;; TODO: where to put query mutations so that time travel can be preserved?
;; TODO: will need to reindex

(defn update-query! [c bs]
  )

(defn mounted?
  "Returns true if the component is mounted."
  [component]
  {:pre [(component? component)]}
  (.isMounted component))

(defn dom-node
  "Returns the dom node associated with a component's React ref."
  ([component]
   (.getDOMNode component))
  ([component name]
   (some-> (.-refs component) (gobj/get name) (.getDOMNode))))

(defn react-ref
  "Returns the component associated with a component's React ref."
  [component name]
  (some-> (.-refs component) (gobj/get name)))

(defn children
  "Returns the component's children."
  [component]
  (.. component -props -children))

(defn update-component! [c next-props]
  {:pre [(component? c)]}
  (update-props! c next-props)
  (.forceUpdate c))

(defn should-update?
  ([c next-props]
   (should-update? c next-props nil))
  ([c next-props next-state]
   {:pre [(component? c)]}
   (.shouldComponentUpdate c
     #js {:omcljs$value next-props}
     #js {:omcljs$state next-state})))

(defn class-path [c]
  {:pre [(component? c)]}
  (loop [c c ret (list (type c))]
    (if-let [p (parent c)]
      (recur p (cons (type p) ret))
      ret)))

(defn data-path
  ([c]
   (let [f (fn [c] (and (iquery? c) (index c)))]
     (data-path c f)))
  ([c f]
   {:pre [(component? c) (fn? f)]}
   (loop [c c ret (list (or (f c) '*))]
     (if-let [p (parent c)]
       (recur p (cons (or (f p) '*) ret))
       ret))))

(defn focused? [x]
  (and (vector? x)
       (== 1 (count x))
       (map? (first x))))

(defn state-query [focus data-path]
  (letfn [(state-query* [focus data-path]
            (if (focused? focus)
              (let [[k v] (ffirst focus)
                    index (first data-path)]
                (if-not (= '* index)
                  [(list {k (state-query* v (rest data-path))} {:index index})]
                  [{k (state-query* v (rest data-path))}]))
              focus))]
    (state-query* focus (rest data-path))))

(defn state-path* [focus data-path]
  (loop [focus focus data-path (rest data-path) ret []]
    (if (focused? focus)
      (let [[k v] (ffirst focus)
            index (first data-path)]
        (recur v (rest data-path)
          (cond-> (conj ret k)
            (not= '* index) (conj index))))
      ret)))

(defn state-path [indexer c]
  (let [idxs @(:indexes indexer)
        fcs  (get-in idxs [:class-path->query (class-path c)])]
    (state-path* fcs (data-path c))))

;; =============================================================================
;; Reconciler API

(declare reconciler?)

(defn basis-t [reconciler]
  (p/basis-t reconciler))

(defn schedule-render! [reconciler]
  (when (p/schedule-render! reconciler)
    (let [f #(p/reconcile! reconciler)]
      (cond
        (fn? *raf*) (*raf* f)

        (not (exists? js/requestAnimationFrame))
        (js/setTimeout f 16)

        :else
        (js/requestAnimationFrame f)))))

(defn schedule-send! [reconciler]
  (when (p/schedule-send! reconciler)
    (js/setTimeout #(p/send! reconciler) 300)))

(declare remove-root!)

(defn add-root!
  "Given a target root DOM node and a root component class, instantiate and
   render the root class using the reconciler's :state property. The reconciler
   will continue to observe state changes to the :state and keep the components
   in sync."
  ([reconciler target root-class]
   (when-let [old-reconciler (get @roots target)]
     (remove-root! old-reconciler target))
   (swap! roots assoc target reconciler)
   (add-root! reconciler target root-class nil))
  ([reconciler target root-class options]
   {:pre [(reconciler? reconciler) (gdom/isElement target) (fn? root-class)]}
   (p/add-root! reconciler target root-class options)))

(defn remove-root!
  "Remove a root target (a DOM elment) from a reconciler. The reconciler will no
   longer attempt to reconcile application state with the specified root."
  [reconciler target]
  (p/remove-root! reconciler target))

;; =============================================================================

(defn ref [root id & more]
  (refs/Ref. (into [root id] more)))

(defn ^boolean ref? [x]
  (instance? refs/Ref x))

(defn refs [root & ids]
  (into [] (map #(ref root %)) ids))

;; =============================================================================
;; Call Support

(defprotocol ITxIntercept
  (tx-intercept [c tx]
    "An optional protocol that component may implement to intercept child
     transactions."))

(defn transact* [c tx]
  (let [r   (get-reconciler c)
        cfg (:config r)
        ref ((:ui->ref cfg) c)
        env (merge
              (select-keys cfg [:indexer :parser :state])
              {:reconciler r :component c}
              (when ref
                {:ref ref}))
        id  (random-uuid)
        _   (.add (:history cfg) id @(:state cfg))
        _   (when-not (nil? *logger*)
              (glog/info *logger*
                (str (pr-str ref) " transacted " tx ", " (pr-str id))))
        v   ((:parser cfg) env tx)
        v'  ((:parser cfg) env tx true)]
    (when-not (empty? v)
      (p/queue! r
        (into (if ref [ref] [])
          (remove symbol? (keys v)))))
    (when-not (empty? v')
      (p/queue-send! r v')
      (schedule-send! r))))

(defn transact
  "Given a component run a transaction. tx is a parse expression that should
   include mutations followed by any necessary read. The reads will be used
   to trigger component re-rendering.

   Example:

     (om/transact! widget
       '[(do/this!) (do/that!)
         :read/this :read/that])"
  [component tx]
  {:pre [(component? component) (vector? tx)]}
  (loop [p (parent component) tx tx]
    (if (nil? p)
      (transact* component tx)
      (let [tx (if (satisfies? ITxIntercept p)
                 (tx-intercept p tx)
                 tx)]
        (recur (parent p) tx)))))

(defn call
  "Given a component a symbol identifying a mutation run a transaction. May
   supply a parse expression of reads that will be used to trigger re-renders
   that depend on the provided keys. Does not return a meaningful value.

   Example:

     (om/call widget 'do/it! [:changed/key])"
  ([component name]
   (call component name nil))
  ([component name param-map]
    (call component name param-map []))
  ([component name param-map reads]
   {:pre [(component? component) (symbol? name)
          (nil-or-map? param-map) (vector? reads)]}
   (transact component (into `[(~name ~param-map)] reads))))

;; =============================================================================
;; Parser

(defn parser
  "Create a parser. The argument is a map of two keys, :read and :mutate. Both
   functions should have the signature (Env -> Key -> Params -> ParseResult)."
  [{:keys [read mutate] :as opts}]
  {:pre [(map? opts)]}
  (parser/parser opts))

(defn dispatch
  "Helper function for implementing :read and :mutate as multimethods. Use this
   as the dispatch-fn."
  [_ key _] key)

;; =============================================================================
;; Indexer

(defrecord Indexer [indexes ui->ref]
  IDeref
  (-deref [_] @indexes)

  p/IIndexer
  (index-root [_ klass]
    (let [class->paths      (atom {})
          prop->classes     (atom {})
          class-path->query (atom {})
          rootq             (get-query klass)]
      (letfn [(build-index* [klass selector path classpath]
                (swap! class->paths update-in [klass]
                  (fnil conj #{}) path)
                (swap! class-path->query assoc classpath
                  (focus-query rootq path))
                (let [{props true joins false} (group-by keyword? selector)]
                  (swap! prop->classes
                    #(merge-with into % (zipmap props (repeat #{klass}))))
                  (doseq [join joins]
                    (let [[prop selector'] (first join)]
                      (swap! prop->classes
                        #(merge-with into % {prop #{klass}}))
                      (let [klass' (-> selector' meta :component)]
                        (build-index* klass' selector'
                          (conj path prop) (conj classpath klass')))))))]
        (build-index* klass rootq [] [klass])
        (reset! indexes
          {:prop->classes @prop->classes
           :class->paths @class->paths
           :class->selectors
           (reduce-kv
             (fn [ret class paths]
               (assoc ret class (into #{} (map #(focus-query rootq %)) paths)))
             {} @class->paths)
           :class->components {}
           :ref->components {}
           :class-path->query @class-path->query
           :component->path {}}))))

  (index-component! [_ c]
    (swap! indexes
      (fn [indexes]
        (let [indexes (update-in indexes
                        [:class->components (type c)]
                        (fnil conj #{}) c)
              ref     (ui->ref c)]
          (if-not (component? ref)
            (cond-> indexes
              ref (update-in [:ref->components ref] (fnil conj #{}) c))
            indexes)))))

  (drop-component! [_ c]
    (swap! indexes
      (fn [indexes]
        (let [indexes (update-in indexes
                        [:class->components (type c)]
                        disj c)
              ref     (ui->ref c)]
          (if-not (component? ref)
            (cond-> indexes
              ref (update-in [:ref->components ref] disj c))
            indexes)))))

  (ref-for [_ component]
    (ui->ref component))

  (key->components [_ k]
    (let [indexes @indexes]
      (if (component? k)
        #{k}
        (let [cs (get-in indexes [:ref->components k] ::not-found)]
          (if-not (keyword-identical? ::not-found cs)
            cs
            (if (keyword? k)
              ;; TODO: more robust validation, might be bogus key
              (let [cs (get-in indexes [:prop->classes k])]
                (transduce (map #(get-in indexes [:class->components %]))
                  (completing into) #{} cs))
              (throw (js/Error. (str "Invalid key " k ", key must be ref or keyword"))))))))))

(defn indexer
  "Given a function (Component -> Ref), return an indexer."
  [ui->ref]
  (Indexer. (atom {}) ui->ref))

(defn ^boolean indexer?
  "Returns true if x is an indexer."
  [x]
  (instance? Indexer x))

(defn build-index
  ([class] (build-index class identity))
  ([class ui->ref]
    (let [idxr (indexer ui->ref)]
      (p/index-root idxr class))))

(defn ref->components
  "Return all components for a given ref."
  [indexer ref]
  (p/key->components indexer ref))

(defn ref->any
  "Get any component from the indexer that matches the ref."
  [indexer ref]
  (state-path indexer
    (first (p/key->components indexer ref))))

(defn ref->paths
  "Return all paths for a given ref."
  [indexer ref]
  (reduce #(conj %1 (state-path indexer %2))
    #{} (p/key->components indexer ref)))

(defn ref->any-path
  "Get any path from the indexer that matches the given ref. See also
   om.next/ref->paths."
  [indexer ref]
  (state-path indexer
    (first (p/key->components indexer ref))))

(defn subpath
  "Given a key path into the application state return the path after the
   given key"
  [path key]
  (rest (drop-while #(not= key %) path)))

(defn get-indexer
  "Get the indexer associated with the reconciler."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  (get-in reconciler [:config :indexer]))

(defn sift-refs [res]
  (let [{refs true rest false} (group-by #(vector? (first %)) res)]
    [(into {} refs) (into {} rest)]))

;; =============================================================================
;; Reconciler

(defn queue-calls! [r res]
  (p/queue! r (into [] (remove symbol?) (keys res))))

(defn- merge-refs [tree {:keys [merge-ref] :as config} refs]
  (letfn [(step [tree' [ref props]]
            (merge-ref config tree' ref props))]
    (reduce step tree refs)))

(defn- merge-novelty!
  [r res]
  (let [config      (:config r)
        [refs res'] (sift-refs res)]
    (swap! (:state config)
      #(-> %
        (merge-refs config refs)
        ((:merge-tree config) res')))))

(defn merge!
  "Merge a state delta into the application state. Affected components managed
   by the reconciler will re-render."
  [reconciler delta]
  (queue-calls! reconciler delta)
  (merge-novelty! reconciler delta))

(defrecord Reconciler [config state]
  p/IReconciler

  (basis-t [_] (:t @state))

  (add-root! [this target root-class options]
    (let [ret (atom nil)
          rctor (create-factory root-class)]
      (p/index-root (:indexer config) root-class)
      (let [renderf (fn [data]
                      (binding [*reconciler* this
                                *root-class* root-class]
                        (reset! ret
                          (js/React.render (rctor data) target))))
            sel     (get-query root-class)]
        (swap! state update-in [:roots] assoc target renderf)
        (let [env (assoc (select-keys config [:state :indexer :parser])
                    :reconciler this)
              v   ((:parser config) env sel)
              v'  ((:parser config) env sel true)]
          (when-not (empty? v)
            (renderf v))
          (when-not (empty? v')
            (when-let [send (:send config)]
              (send v'
                #(do
                   (merge-novelty! this %)
                   (renderf %))))))
        @ret)))

  (remove-root! [_ target]
    (swap! state update-in [:roots] dissoc target))

  (queue! [_ ks]
    (swap! state
      (fn [state]
        (-> state
          (update-in [:t] inc) ;; TODO: probably should revisit doing this here
          (update-in [:queue] into ks)))))

  (queue-send! [_ expr]
    (swap! state update-in [:queued-send]
      (:merge-send config) expr))

  (schedule-render! [_]
    (if-not (:queued @state)
      (swap! state update-in [:queued] not)
      false))

  (schedule-send! [_]
    (if-not (:send-queued @state)
      (do
        (swap! state assoc [:send-queued] true)
        true)
      false))

  ;; TODO: need to reindex roots after reconcilation
  (reconcile! [_]
    (let [st @state]
      (if (empty? (:queue st))
        (doseq [[_ renderf] (:roots st)]
          (renderf @(:state config)))
        (let [cs (transduce (map #(p/key->components (:indexer config) %))
                   (completing into) #{} (:queue st))
              {:keys [ui->props state]} config
              env (select-keys config [:state :parser :indexer :ui->ref])]
          (doseq [c ((:optimize config) cs)]
            (let [next-props (ui->props env c)]
              (when (and (should-update? c next-props (get-state c))
                         (mounted? c))
                (update-component! c next-props))))
          (swap! state assoc :queue [])))
      (swap! state update-in [:queued] not)))

  (send! [this]
    (let [expr (:queued-send @state)]
      (when expr
        (swap! state
          (fn [state]
            (-> state
              (assoc :queued-send [])
              (assoc :send-queued false))))
        ((:send config) expr
          #(do
             (queue-calls! this %)
             (merge-novelty! this %)))))))

(defn default-ui->props
  [{:keys [state indexer parser] :as env} c]
  (let [st   @state
        idxs @(:indexes indexer)
        fcs  (get-in idxs [:class-path->query (class-path c)])
        ps   (get-in (parser env fcs) (state-path* fcs (data-path c)))]
    (if (ref? ps)
      (let [{:keys [root id]} ps]
        (get-in st [root id]))
      ps)))

(defn default-merge-ref
  [{:keys [indexer] :as config} tree ref props]
  (letfn [(merge-ref-step [tree c]
            (update-in tree (state-path indexer c) merge props))]
    (reduce merge-ref-step tree
      (p/key->components indexer ref))))

(defn reconciler
  "Construct a reconciler from a configuration map, the following options
   are required:

   :state  - the application state, must be IAtom.
   :parser - the parser to be used
   :send   - required only if the parser will return a non-empty value when
             run in remote mode. send is a function of two arguments, the
             remote expression and a callback which should be invoked with
             the resolved expression."
  [{:keys [state parser indexer
           ui->ref ui->props
           send merge-send
           merge-tree merge-ref
           optimize
           history]
    :or {ui->ref     identity
         ui->props   default-ui->props
         indexer     om.next/indexer
         merge-send  into
         merge-tree  merge
         merge-ref   default-merge-ref
         optimize    (fn [cs] (sort-by depth cs))
         history     100}
    :as config}]
  {:pre [(map? config)]}
  (let [idxr (indexer ui->ref)
        ret  (Reconciler.
               {:state state :parser parser :indexer idxr
                :ui->ref ui->ref :ui->props ui->props
                :send send :merge-send merge-send
                :merge-tree merge-tree :merge-ref merge-ref
                :optimize optimize
                :history (c/cache history)}
               (atom {:queue [] :queued false :queued-send []
                      :send-queued false :roots {} :t 0}))]
    (when state
      (add-watch state :om/reconciler
        (fn [_ _ _ _] (schedule-render! ret))))
    ret))

(defn ^boolean reconciler?
  "Returns true if x is a reconciler."
  [x]
  (instance? Reconciler x))

(defn from-history [reconciler uuid]
  (.get (get-in reconciler [:config :history]) uuid))
