(ns frontend.db.model
  "Core db functions."
  (:require [frontend.db.conn :as conn]
            [frontend.db.utils :as db-utils]
            [frontend.db.react :as react]
            [datascript.core :as d]
            [frontend.date :as date]
            [medley.core :as medley]
            [frontend.format :as format]
            [frontend.state :as state]
            [clojure.string :as string]
            [clojure.set :as set]
            [frontend.utf8 :as utf8]
            [frontend.config :as config]
            [frontend.format.block :as block]
            [cljs.reader :as reader]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [frontend.util :as util :refer [react] :refer-macros [profile]]
            [frontend.db-schema :as db-schema]
            [frontend.db.base :as base]))

;; TODO: extract to specific models and move data transform logic to the
;; correponding handlers.

(defn transact-files-db!
  ([tx-data]
   (base/transact! (state/get-current-repo) tx-data))
  ([repo-url tx-data]
   (when-not config/publishing?
     (let [tx-data (->> (util/remove-nils tx-data)
                     (remove nil?)
                     (map #(dissoc % :file/handle :file/type)))]
       (when (seq tx-data)
         (when-let [conn (conn/get-files-conn repo-url)]
           (d/transact! conn (vec tx-data))))))))

(defn sub-key-value
  ([key]
   (sub-key-value (state/get-current-repo) key))
  ([repo-url key]
   (when (conn/get-conn repo-url)
     (-> (react/q repo-url [:kv key] {} key key)
         react
         key))))

(defn pull-block
  [id]
  (let [repo (state/get-current-repo)]
    (when (conn/get-conn repo)
      (->
       (react/q repo [:blocks id] {}
          '[:find (base/pull ?block [*])
            :in $ ?id
            :where
            [?block :block/uuid ?id]]
          id)
       react
       ffirst))))

(defn get-all-tags
  []
  (let [repo (state/get-current-repo)]
    (when (conn/get-conn repo)
      (some->>
       (react/q repo [:tags] {}
          '[:find ?name ?h ?p
            :where
            [?t :tag/name ?name]
            (or
             [?h :block/tags ?t]
             [?p :page/tags ?t])])
       react
       (seq)))))

(defn get-tag-pages
  [repo tag-name]
  (d/q '[:find ?original-name ?name
         :in $ ?tag
         :where
         [?e :tag/name ?tag]
         [?page :page/tags ?e]
         [?page :page/original-name ?original-name]
         [?page :page/name ?name]]
       (conn/get-conn repo)
       tag-name))

(defn get-all-tagged-pages
  [repo]
  (d/q '[:find ?page-name ?tag
         :where
         [?page :page/tags ?e]
         [?e :tag/name ?tag]
         [_ :page/name ?tag]
         [?page :page/name ?page-name]]
       (conn/get-conn repo)))

(defn get-pages
  [repo]
  (->> (d/q
        '[:find ?page-name
          :where
          [?page :page/original-name ?page-name]]
        (conn/get-conn repo))
       (map first)))

(defn get-pages-with-modified-at
  [repo]
  (let [now-long (tc/to-long (t/now))]
    (->> (d/q
          '[:find ?page-name ?modified-at
            :where
            [?page :page/original-name ?page-name]
            [(get-else $ ?page :page/last-modified-at 0) ?modified-at]]
          (conn/get-conn repo))
         (seq)
         (sort-by (fn [[page modified-at]]
                    [modified-at page]))
         (reverse)
         (remove (fn [[page modified-at]]
                   (or (util/file-page? page)
                       (and modified-at
                            (> modified-at now-long))))))))

(defn get-page-alias
  [repo page-name]
  (when-let [conn (and repo (conn/get-conn repo))]
    (some->> (d/q '[:find ?alias
                    :in $ ?page-name
                    :where
                    [?page :page/name ?page-name]
                    [?page :page/alias ?alias]]
                  conn
                  page-name)
             db-utils/seq-flatten
             distinct)))

(defn get-alias-page
  [repo alias]
  (when-let [conn (and repo (conn/get-conn repo))]
    (some->> (d/q '[:find ?page
                    :in $ ?alias
                    :where
                    [?page :page/alias ?alias]]
                  conn
                  alias)
             db-utils/seq-flatten
             distinct)))

(defn get-page-alias-names
  [repo page-name]
  (let [alias-ids (get-page-alias repo page-name)]
    (when (seq alias-ids)
      (->> (base/pull-many repo '[:page/name] alias-ids)
           (map :page/name)
           distinct))))

(defn get-files
  [repo]
  (when-let [conn (conn/get-conn repo)]
    (->> (d/q
          '[:find ?path ?modified-at
            :where
            [?file :file/path ?path]
            [(get-else $ ?file :file/last-modified-at 0) ?modified-at]]
          conn)
         (seq)
         (sort-by last)
         (reverse))))

(defn get-files-blocks
  [repo-url paths]
  (let [paths (set paths)
        pred (fn [_db e]
               (contains? paths e))]
    (-> (d/q '[:find ?block
               :in $ ?pred
               :where
               [?file :file/path ?path]
               [(?pred $ ?path)]
               [?block :block/file ?file]]
             (conn/get-conn repo-url) pred)
        db-utils/seq-flatten)))

(defn get-file-blocks
  [repo-url path]
  (-> (d/q '[:find ?block
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?block :block/file ?file]]
           (conn/get-conn repo-url) path)
      db-utils/seq-flatten))

(defn get-file-after-blocks
  [repo-url file-id end-pos]
  (when end-pos
    (let [pred (fn [db meta]
                 (>= (:start-pos meta) end-pos))]
      (-> (d/q '[:find (base/pull ?block [*])
                 :in $ ?file-id ?pred
                 :where
                 [?block :block/file ?file-id]
                 [?block :block/meta ?meta]
                 [(?pred $ ?meta)]]
               (conn/get-conn repo-url) file-id pred)
          db-utils/seq-flatten
          db-utils/sort-by-pos))))

(defn get-file-after-blocks-meta
  ([repo-url file-id end-pos]
   (get-file-after-blocks-meta repo-url file-id end-pos false))
  ([repo-url file-id end-pos content-level?]
   (let [db (conn/get-conn repo-url)
         blocks (d/datoms db :avet :block/file file-id)
         eids (mapv :e blocks)
         ks (if content-level?
              '[:block/uuid :block/meta :block/content :block/level]
              '[:block/uuid :block/meta])
         blocks (base/pull-many repo-url ks eids)]
     (->> (filter (fn [{:block/keys [meta]}]
                    (>= (:start-pos meta) end-pos)) blocks)
          db-utils/sort-by-pos))))

(defn get-file-pages
  [repo-url path]
  (-> (d/q '[:find ?page
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?page :page/file ?file]]
           (conn/get-conn repo-url) path)
      db-utils/seq-flatten))

(defn set-file-last-modified-at!
  [repo path last-modified-at]
  (when (and repo path last-modified-at)
    (when-let [conn (conn/get-files-conn repo)]
      (d/transact! conn
                   [{:file/path path
                     :file/last-modified-at last-modified-at}]))))

(defn get-file-last-modified-at
  [repo path]
  (when (and repo path)
    (when-let [conn (conn/get-files-conn repo)]
      (-> (d/entity (d/db conn) [:file/path path])
          :file/last-modified-at))))

(defn get-file
  ([path]
   (get-file (state/get-current-repo) path))
  ([repo path]
   (when (and repo path)
     (->
      (react/q repo [:file/content path]
         {:files-db? true
          :use-cache? true}
         '[:find ?content
           :in $ ?path
           :where
           [?file :file/path ?path]
           [?file :file/content ?content]]
         path)
      react
      ffirst))))

(defn get-file-contents
  [repo]
  (when-let [conn (conn/get-files-conn repo)]
    (->>
     (d/q
      '[:find ?path ?content
        :where
        [?file :file/path ?path]
        [?file :file/content ?content]]
      @conn)
     (into {}))))

(defn get-files-full
  [repo]
  (when-let [conn (conn/get-files-conn repo)]
    (->>
     (d/q
      '[:find (base/pull ?file [*])
        :where
        [?file :file/path]]
      @conn)
     (flatten))))

(defn get-custom-css
  []
  (get-file "logseq/custom.css"))

(defn get-file-no-sub
  ([path]
   (get-file-no-sub (state/get-current-repo) path))
  ([repo path]
   (when (and repo path)
     (when-let [conn (conn/get-files-conn repo)]
       (->
        (d/q
         '[:find ?content
           :in $ ?path
           :where
           [?file :file/path ?path]
           [?file :file/content ?content]]
         @conn
         path)
        ffirst)))))

(defn get-block-by-uuid
  [uuid]
  (base/entity [:block/uuid uuid]))

(defn get-page-format
  [page-name]
  (when-let [file (:page/file (base/entity [:page/name page-name]))]
    (when-let [path (:file/path (base/entity (:db/id file)))]
      (format/get-format path))))

(defn page-alias-set
  [repo-url page]
  (when-let [page-id (:db/id (base/entity [:page/name page]))]
    (let [aliases (get-page-alias repo-url page)
          aliases (if (seq aliases)
                    (set
                     (concat
                      (mapcat #(get-alias-page repo-url %) aliases)
                      aliases))
                    aliases)]
      (set (conj aliases page-id)))))

(defn get-block-refs-count
  [repo]
  (->> (d/q
        '[:find ?id2 ?id1
          :where
          [?id1 :block/ref-blocks ?id2]]
        (conn/get-conn repo))
       (map first)
       (frequencies)))

(defn with-block-refs-count
  [repo blocks]
  (let [db-ids (map :db/id blocks)
        refs (get-block-refs-count repo)]
    (map (fn [block]
           (assoc block :block/block-refs-count
                  (get refs (:db/id block))))
         blocks)))

(defn page-blocks-transform
  [repo-url result]
  (let [result (db-utils/seq-flatten result)
        sorted (db-utils/sort-by-pos result)]
    (->> (db-utils/with-repo repo-url sorted)
         (with-block-refs-count repo-url))))

(defn sort-blocks
  [blocks]
  (let [pages-ids (map (comp :db/id :block/page) blocks)
        pages (base/pull-many '[:db/id :page/last-modified-at :page/name :page/original-name] pages-ids)
        pages-map (reduce (fn [acc p] (assoc acc (:db/id p) p)) {} pages)
        blocks (map
                (fn [block]
                  (assoc block :block/page
                         (get pages-map (:db/id (:block/page block)))))
                blocks)]
    (db-utils/sort-by-pos blocks)))

(defn get-marker-blocks
  [repo-url marker]
  (let [marker (string/upper-case marker)]
    (some->>
     (react/q repo-url [:marker/blocks marker]
        {:use-cache? true}
        '[:find (base/pull ?h [*])
          :in $ ?marker
          :where
          [?h :block/marker ?m]
          [(= ?marker ?m)]]
        marker)
     react
     db-utils/seq-flatten
     db-utils/sort-by-pos
     (db-utils/with-repo repo-url)
     (with-block-refs-count repo-url)
     (sort-blocks)
     (db-utils/group-by-page))))

(defn get-page-properties
  [page]
  (when-let [page (base/entity [:page/name page])]
    (:page/properties page)))

(defn add-properties!
  [page-format properties-content properties]
  (let [properties (medley/map-keys name properties)
        lines (string/split-lines properties-content)
        front-matter-format? (contains? #{:markdown} page-format)
        lines (if front-matter-format?
                (remove (fn [line]
                          (contains? #{"---" ""} (string/trim line))) lines)
                lines)
        property-keys (keys properties)
        prefix-f (case page-format
                   :org (fn [k]
                          (str "#+" (string/upper-case k) ": "))
                   :markdown (fn [k]
                               (str (string/lower-case k) ": "))
                   identity)
        exists? (atom #{})
        lines (doall
               (mapv (fn [line]
                       (let [result (filter #(and % (util/starts-with? line (prefix-f %)))
                                            property-keys)]
                         (if (seq result)
                           (let [k (first result)]
                             (swap! exists? conj k)
                             (str (prefix-f k) (get properties k)))
                           line))) lines))
        lines (concat
               lines
               (let [not-exists (remove
                                 (fn [[k _]]
                                   (contains? @exists? k))
                                 properties)]
                 (when (seq not-exists)
                   (mapv
                    (fn [[k v]] (str (prefix-f k) v))
                    not-exists))))]
    (util/format
     (config/properties-wrapper-pattern page-format)
     (string/join "\n" lines))))

(defn get-page-blocks
  ([page]
   (get-page-blocks (state/get-current-repo) page nil))
  ([repo-url page]
   (get-page-blocks repo-url page nil))
  ([repo-url page {:keys [use-cache? pull-keys]
                   :or {use-cache? true
                        pull-keys '[*]}}]
   (let [page (string/lower-case page)
         page-id (or (:db/id (base/entity repo-url [:page/name page]))
                     (:db/id (base/entity repo-url [:page/original-name page])))
         db (conn/get-conn repo-url)]
     (when page-id
       (some->
        (react/q repo-url [:page/blocks page-id]
           {:use-cache? use-cache?
            :transform-fn #(page-blocks-transform repo-url %)
            :query-fn (fn [db]
                        (let [datoms (d/datoms db :avet :block/page page-id)
                              block-eids (mapv :e datoms)]
                          (base/pull-many repo-url pull-keys block-eids)))}
           nil)
        react)))))

(defn get-page-blocks-no-cache
  ([page]
   (get-page-blocks-no-cache (state/get-current-repo) page nil))
  ([repo-url page]
   (get-page-blocks-no-cache repo-url page nil))
  ([repo-url page {:keys [pull-keys]
                   :or {pull-keys '[*]}}]
   (let [page (string/lower-case page)
         page-id (or (:db/id (base/entity repo-url [:page/name page]))
                     (:db/id (base/entity repo-url [:page/original-name page])))
         db (conn/get-conn repo-url)]
     (when page-id
       (let [datoms (d/datoms db :avet :block/page page-id)
             block-eids (mapv :e datoms)]
         (some->> (base/pull-many repo-url pull-keys block-eids)
                  (page-blocks-transform repo-url)))))))

(defn get-page-blocks-count
  [repo page-id]
  (when-let [db (conn/get-conn repo)]
    (count (d/datoms db :avet :block/page page-id))))

(defn get-block-parent
  [repo block-id]
  (when-let [conn (conn/get-conn repo)]
    (d/entity conn [:block/children [:block/uuid block-id]])))

;; non recursive query
(defn get-block-parents
  [repo block-id depth]
  (when-let [conn (conn/get-conn repo)]
    (loop [block-id block-id
           parents (list)
           d 1]
      (if (> d depth)
        parents
        (if-let [parent (get-block-parent repo block-id)]
          (recur (:block/uuid parent) (conj parents parent) (inc d))
          parents)))))

(defn get-block-page
  [repo block-id]
  (when-let [block (base/entity repo [:block/uuid block-id])]
    (base/entity repo (:db/id (:block/page block)))))

(defn get-block-page-end-pos
  [repo page-name]
  (or
   (when-let [page-id (:db/id (base/entity repo [:page/name (string/lower-case page-name)]))]
     (when-let [db (conn/get-conn repo)]
       (let [block-eids (->> (d/datoms db :avet :block/page page-id)
                             (mapv :e))]
         (when (seq block-eids)
           (let [blocks (base/pull-many repo '[:block/meta] block-eids)]
             (-> (last (db-utils/sort-by-pos blocks))
                 (get-in [:block/meta :end-pos])))))))
   ;; TODO: need more thoughts
   0))

(defn get-blocks-by-priority
  [repo priority]
  (let [priority (string/capitalize priority)]
    (when (conn/get-conn repo)
      (->> (react/q repo [:priority/blocks priority] {}
              '[:find (base/pull ?h [*])
                :in $ ?priority
                :where
                [?h :block/priority ?priority]]
              priority)
           react
           db-utils/seq-flatten
           sort-blocks
           db-utils/group-by-page))))

(defn get-page-properties-content
  [page]
  (when-let [content (let [blocks (get-page-blocks page)]
                       (and (:block/pre-block? (first blocks))
                            (:block/content (first blocks))))]
    (let [format (get-page-format page)]
      (case format
        :org
        (->> (string/split-lines content)
             (take-while (fn [line]
                           (or (string/blank? line)
                               (string/starts-with? line "#+"))))
             (string/join "\n"))

        :markdown
        (str (subs content 0 (string/last-index-of content "---\n\n"))
             "---\n\n")

        content))))

(defn block-and-children-transform
  [result repo-url block-uuid level]
  (some->> result
           db-utils/seq-flatten
           db-utils/sort-by-pos
           (take-while (fn [h]
                         (or
                          (= (:block/uuid h)
                             block-uuid)
                          (> (:block/level h) level))))
           (db-utils/with-repo repo-url)
           (with-block-refs-count repo-url)))

(defn get-block-children-ids
  [repo block-uuid]
  (when-let [conn (conn/get-conn repo)]
    (let [eid (:db/id (base/entity repo [:block/uuid block-uuid]))]
      (->> (d/q
            '[:find ?e1
              :in $ ?e2 %
              :where (parent ?e2 ?e1)]
            conn
            eid
             ;; recursive rules
            '[[(parent ?e2 ?e1)
               [?e2 :block/children ?e1]]
              [(parent ?e2 ?e1)
               [?t :block/children ?e1]
               (parent ?e2 ?t)]])
           (apply concat)))))

(defn get-block-immediate-children
  [repo block-uuid]
  (when-let [conn (conn/get-conn repo)]
    (let [ids (->> (:block/children (base/entity repo [:block/uuid block-uuid]))
                   (map :db/id))]
      (when (seq ids)
        (base/pull-many repo '[*] ids)))))

(defn get-block-children
  [repo block-uuid]
  (when-let [conn (conn/get-conn repo)]
    (let [ids (get-block-children-ids repo block-uuid)]
      (when (seq ids)
        (base/pull-many repo '[*] ids)))))

(defn get-block-and-children
  ([repo block-uuid]
   (get-block-and-children repo block-uuid true))
  ([repo block-uuid use-cache?]
   (let [block (base/entity repo [:block/uuid block-uuid])
         page (:db/id (:block/page block))
         pos (:start-pos (:block/meta block))
         level (:block/level block)
         pred (fn []
                (let [block (base/entity repo [:block/uuid block-uuid])
                      pos (:start-pos (:block/meta block))]
                  (fn [data meta]
                    (>= (:start-pos meta) pos))))]
     (some-> (react/q repo [:block/block block-uuid]
                {:use-cache? use-cache?
                 :transform-fn #(block-and-children-transform % repo block-uuid level)
                 :inputs-fn (fn []
                              [page (pred)])}
                '[:find (base/pull ?block [*])
                  :in $ ?page ?pred
                  :where
                  [?block :block/page ?page]
                  [?block :block/meta ?meta]
                  [(?pred $ ?meta)]])
             react))))

;; TODO: performance
(defn get-block-and-children-no-cache
  [repo block-uuid]
  (let [block (base/entity repo [:block/uuid block-uuid])
        page (:db/id (:block/page block))
        pos (:start-pos (:block/meta block))
        level (:block/level block)
        pred (fn [data meta]
               (>= (:start-pos meta) pos))]
    (-> (d/q
         '[:find (base/pull ?block [*])
           :in $ ?page ?pred
           :where
           [?block :block/page ?page]
           [?block :block/meta ?meta]
           [(?pred $ ?meta)]]
         (conn/get-conn repo)
         page
         pred)
        (block-and-children-transform repo block-uuid level))))

(defn get-file-page
  ([file-path]
   (get-file-page file-path true))
  ([file-path original-name?]
   (when-let [repo (state/get-current-repo)]
     (when-let [conn (conn/get-conn repo)]
       (some->
        (d/q
         (if original-name?
           '[:find ?page-name
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?page :page/file ?file]
             [?page :page/original-name ?page-name]]
           '[:find ?page-name
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?page :page/file ?file]
             [?page :page/name ?page-name]])
         conn file-path)
        db-utils/seq-flatten
        first)))))

(defn get-page-file
  [page-name]
  (some-> (base/entity [:page/name page-name])
          :page/file))

(defn get-block-file
  [block-id]
  (let [page-id (some-> (base/entity [:block/uuid block-id])
                        :block/page
                        :db/id)]
    (:page/file (base/entity page-id))))

(defn get-file-page-id
  [file-path]
  (when-let [repo (state/get-current-repo)]
    (when-let [conn (conn/get-conn repo)]
      (some->
       (d/q
        '[:find ?page
          :in $ ?path
          :where
          [?file :file/path ?path]
          [?page :page/file ?file]]
        conn file-path)
       db-utils/seq-flatten
       first))))

(defn get-page
  [page-name]
  (if (util/uuid-string? page-name)
    (base/entity [:block/uuid (uuid page-name)])
    (base/entity [:page/name page-name])))

(defn get-page-name
  [file ast]
  ;; headline
  (let [ast (map first ast)]
    (if (util/starts-with? file "pages/contents.")
      "Contents"
      (let [first-block (last (first (filter block/heading-block? ast)))
            property-name (when (and (= "Properties" (ffirst ast))
                                     (not (string/blank? (:title (last (first ast))))))
                            (:title (last (first ast))))
            first-block-name (and first-block
                                  ;; FIXME:
                                  (str (last (first (:title first-block)))))
            file-name (when-let [file-name (last (string/split file #"/"))]
                        (when-let [file-name (first (util/split-last "." file-name))]
                          (-> file-name
                              (string/replace "-" " ")
                              (string/replace "_" " ")
                              (util/capitalize-all))))]
        (or property-name
            (if (= (state/page-name-order) "file")
              (or file-name first-block-name)
              (or first-block-name file-name)))))))

(defn get-block-content
  [utf8-content block]
  (let [meta (:block/meta block)]
    (if-let [end-pos (:end-pos meta)]
      (utf8/substring utf8-content
                      (:start-pos meta)
                      end-pos)
      (utf8/substring utf8-content
                      (:start-pos meta)))))

(defn get-journals-length
  []
  (let [today (db-utils/date->int (js/Date.))]
    (d/q '[:find (count ?page) .
           :in $ ?today
           :where
           [?page :page/journal? true]
           [?page :page/journal-day ?journal-day]
           [(<= ?journal-day ?today)]]
         (conn/get-conn (state/get-current-repo))
         today)))

(defn get-latest-journals
  ([n]
   (get-latest-journals (state/get-current-repo) n))
  ([repo-url n]
   (when (conn/get-conn repo-url)
     (let [date (js/Date.)
           _ (.setDate date (- (.getDate date) (dec n)))
           today (db-utils/date->int (js/Date.))
           pages (->>
                  (react/q repo-url [:journals] {:use-cache? false}
                     '[:find ?page-name ?journal-day
                       :in $ ?today
                       :where
                       [?page :page/name ?page-name]
                       [?page :page/journal? true]
                       [?page :page/journal-day ?journal-day]
                       [(<= ?journal-day ?today)]]
                     today)
                  (react)
                  (sort-by last)
                  (reverse)
                  (map first)
                  (take n))]
       (mapv
        (fn [page]
          [page
           (get-page-format page)])
        pages)))))

;; get pages that this page referenced
(defn get-page-referenced-pages
  [repo page]
  (when (conn/get-conn repo)
    (let [pages (page-alias-set repo page)
          page-id (:db/id (base/entity [:page/name page]))
          ref-pages (->> (react/q repo [:page/ref-pages page-id] {:use-cache? false}
                            '[:find ?ref-page-name
                              :in $ ?pages
                              :where
                              [?block :block/page ?p]
                              [(contains? ?pages ?p)]
                              [?block :block/ref-pages ?ref-page]
                              [?ref-page :page/name ?ref-page-name]]
                            pages)
                         react
                         db-utils/seq-flatten)]
      (mapv (fn [page] [page (get-page-alias repo page)]) ref-pages))))

;; Ignore files with empty blocks for now
(defn get-empty-pages
  [repo]
  (when-let [conn (conn/get-conn repo)]
    (->
     (d/q
      '[:find ?page
        :where
        [?p :page/name ?page]
        (not [?p :page/file])]
      conn)
     (db-utils/seq-flatten)
     (distinct))))

(defn get-pages-relation
  [repo with-journal?]
  (when-let [conn (conn/get-conn repo)]
    (let [q (if with-journal?
              '[:find ?page ?ref-page-name
                :where
                [?p :page/name ?page]
                [?block :block/page ?p]
                [?block :block/ref-pages ?ref-page]
                [?ref-page :page/name ?ref-page-name]]
              '[:find ?page ?ref-page-name
                :where
                [?p :page/journal? false]
                [?p :page/name ?page]
                [?block :block/page ?p]
                [?block :block/ref-pages ?ref-page]
                [?ref-page :page/name ?ref-page-name]])]
      (->>
       (d/q q conn)
       (map (fn [[page ref-page-name]]
              [page ref-page-name]))))))

;; get pages who mentioned this page
(defn get-pages-that-mentioned-page
  [repo page]
  (when (conn/get-conn repo)
    (let [page-id (:db/id (base/entity [:page/name page]))
          pages (page-alias-set repo page)
          mentioned-pages (->> (react/q repo [:page/mentioned-pages page-id] {:use-cache? false}
                                  '[:find ?mentioned-page-name
                                    :in $ ?pages ?page-name
                                    :where
                                    [?block :block/ref-pages ?p]
                                    [(contains? ?pages ?p)]
                                    [?block :block/page ?mentioned-page]
                                    [?mentioned-page :page/name ?mentioned-page-name]]
                                  pages
                                  page)
                               react
                               db-utils/seq-flatten)]
      (mapv (fn [page] [page (get-page-alias repo page)]) mentioned-pages))))

(defn get-page-referenced-blocks
  [page]
  (when-let [repo (state/get-current-repo)]
    (when (conn/get-conn repo)
      (let [page-id (:db/id (base/entity [:page/name page]))
            pages (page-alias-set repo page)]
        (->> (react/q repo [:page/refed-blocks page-id] {}
                '[:find (base/pull ?block [*])
                  :in $ ?pages
                  :where
                  [?block :block/ref-pages ?ref-page]
                  [(contains? ?pages ?ref-page)]]
                pages)
             react
             db-utils/seq-flatten
             (remove (fn [block]
                       (let [exclude-pages pages]
                         (contains? exclude-pages (:db/id (:block/page block))))))
             sort-blocks
             db-utils/group-by-page)))))

(defn get-date-scheduled-or-deadlines
  [journal-title]
  (when-let [date (date/journal-title->int journal-title)]
    (when-let [repo (state/get-current-repo)]
      (when-let [conn (conn/get-conn repo)]
        (->> (react/q repo [:custom :scheduled-deadline journal-title] {}
                '[:find (base/pull ?block [*])
                  :in $ ?day
                  :where
                  (or
                   [?block :block/scheduled ?day]
                   [?block :block/deadline ?day])]
                date)
             react
             db-utils/seq-flatten
             sort-blocks
             db-utils/group-by-page
             (remove (fn [[page _blocks]]
                       (= journal-title (:page/original-name page)))))))))

(defn get-files-that-referenced-page
  [page-id]
  (when-let [repo (state/get-current-repo)]
    (when-let [db (conn/get-conn repo)]
      (->> (d/q
            '[:find ?path
              :in $ ?page-id
              :where
              [?block :block/ref-pages ?page-id]
              [?block :block/page ?p]
              [?p :page/file ?f]
              [?f :file/path ?path]]
            db
            page-id)
           (db-utils/seq-flatten)))))

(defn get-page-unlinked-references
  [page]
  (when-let [repo (state/get-current-repo)]
    (when-let [conn (conn/get-conn repo)]
      (let [page-id (:db/id (base/entity [:page/name page]))
            pages (page-alias-set repo page)
            pattern (re-pattern (str "(?i)" page))]
        (->> (d/q
              '[:find (base/pull ?block [*])
                :in $ ?pattern
                :where
                [?block :block/content ?content]
                [(re-find ?pattern ?content)]]
              conn
              pattern)
             db-utils/seq-flatten
             (remove (fn [block]
                       (let [ref-pages (set (map :db/id (:block/ref-pages block)))]
                         (or
                          (= (get-in block [:block/page :db/id]) page-id)
                          (seq (set/intersection
                                ref-pages
                                pages))))))
             sort-blocks
             db-utils/group-by-page)))))

(defn get-block-referenced-blocks
  [block-uuid]
  (when-let [repo (state/get-current-repo)]
    (when (conn/get-conn repo)
      (->> (react/q repo [:block/refed-blocks block-uuid] {}
              '[:find (base/pull ?ref-block [*])
                :in $ ?block-uuid
                :where
                [?block :block/uuid ?block-uuid]
                [?ref-block :block/ref-blocks ?block]]
              block-uuid)
           react
           db-utils/seq-flatten
           sort-blocks
           db-utils/group-by-page))))

(defn get-matched-blocks
  [match-fn limit]
  (when-let [repo (state/get-current-repo)]
    (let [pred (fn [db content]
                 (match-fn content))]
      (->> (d/q
            '[:find ?block
              :in $ ?pred
              :where
              [?block :block/content ?content]
              [(?pred $ ?content)]]
            (conn/get-conn)
            pred)
           (take limit)
           db-utils/seq-flatten
           (base/pull-many '[:block/uuid
                        :block/content
                        :block/properties
                        :block/format
                        {:block/page [:page/name]}])))))

;; TODO: Does the result preserves the order of the arguments?
(defn get-blocks-contents
  [repo block-uuids]
  (let [db (conn/get-conn repo)]
    (base/pull-many repo '[:block/content]
               (mapv (fn [id] [:block/uuid id]) block-uuids))))

(defn journal-page?
  [page-name]
  (:page/journal? (base/entity [:page/name page-name])))

(defn mark-repo-as-cloned!
  [repo-url]
  (base/transact!
   [{:repo/url repo-url
     :repo/cloned? true}]))

(defn cloned?
  [repo-url]
  (when-let [conn (conn/get-conn repo-url)]
    (->
     (d/q '[:find ?cloned
            :in $ ?repo-url
            :where
            [?repo :repo/url ?repo-url]
            [?repo :repo/cloned? ?cloned]]
          conn
          repo-url)
     ffirst)))

(defn get-config
  [repo-url]
  (get-file repo-url (str config/app-name "/" config/config-file)))

(defn reset-config!
  [repo-url content]
  (when-let [content (or content (get-config repo-url))]
    (let [config (try
                   (reader/read-string content)
                   (catch js/Error e
                     (println "Parsing config file failed: ")
                     (js/console.dir e)
                     {}))]
      (state/set-config! repo-url config)
      config)))

(defn get-db-type
  [repo]
  (base/get-key-value repo :db/type))

(defn local-native-fs?
  [repo]
  (= :local-native-fs (get-db-type repo)))

(defn get-collapsed-blocks
  []
  (d/q
   '[:find ?content
     :where
     [?h :block/collapsed? true]
     [?h :block/content ?content]]
   (conn/get-conn)))

(defn get-public-pages
  [db]
  (-> (d/q
       '[:find ?p
         :where
         [?p :page/properties ?d]
         [(get ?d :public) ?pub]
         [(= "true" ?pub)]]
       db)
      (db-utils/seq-flatten)))

(defn rebuild-page-blocks-children
  "For performance reason, we can update the :block/children value after every operation,
  but it's hard to make sure that it's correct, also it needs more time to implement it.
  We can improve it if the performance is really an issue."
  [repo page]
  (let [blocks (->>
                (get-page-blocks-no-cache repo page {:pull-keys '[:db/id :block/uuid :block/level :block/pre-block? :block/meta]})
                (remove :block/pre-block?)
                (map #(select-keys % [:db/id :block/uuid :block/level]))
                (reverse))
        original-blocks blocks]
    (loop [blocks blocks
           tx []
           children {}
           last-level 10000]
      (if (seq blocks)
        (let [[{:block/keys [uuid level] :as block} & others] blocks
              [tx children] (cond
                              (< level last-level)        ; parent
                              (let [cur-children (get children last-level)
                                    tx (if (seq cur-children)
                                         (vec
                                          (concat
                                           tx
                                           (map
                                            (fn [child]
                                              [:db/add (:db/id block) :block/children [:block/uuid child]])
                                            cur-children)))
                                         tx)
                                    children (-> children
                                                 (dissoc last-level)
                                                 (update level conj uuid))]
                                [tx children])

                              (> level last-level)        ; child of sibling
                              (let [children (update children level conj uuid)]
                                [tx children])

                              :else                       ; sibling
                              (let [children (update children last-level conj uuid)]
                                [tx children]))]
          (recur others tx children level))
        ;; TODO: add top-level children to the "Page" block (we might remove the Page from db schema)
        (when (seq tx)
          (let [delete-tx (map (fn [block]
                                 [:db/retract (:db/id block) :block/children])
                               original-blocks)]
            (->> (concat delete-tx tx)
                 (remove nil?))))))))

(defn get-all-templates
  []
  (let [pred (fn [db properties]
               (some? (get properties "template")))]
    (->> (d/q
          '[:find ?b ?p
            :in $ ?pred
            :where
            [?b :block/properties ?p]
            [(?pred $ ?p)]]
          (conn/get-conn)
          pred)
         (map (fn [[e m]]
                [(get m "template") e]))
         (into {}))))

(defn template-exists?
  [title]
  (when title
    (let [templates (keys (get-all-templates))]
      (when (seq templates)
        (let [templates (map string/lower-case templates)]
          (contains? (set templates) (string/lower-case title)))))))

(defonce blocks-count-cache (atom nil))

(defn blocks-count
  ([]
   (blocks-count true))
  ([cache?]
   (if (and cache? @blocks-count-cache)
     @blocks-count-cache
     (let [n (count (d/datoms (conn/get-conn) :avet :block/uuid))]
       (reset! blocks-count-cache n)
       n))))

;; block/uuid and block/content
(defn get-all-block-contents
  []
  (when-let [conn (conn/get-conn)]
    (->> (d/datoms conn :avet :block/uuid)
         (map :v)
         (map (fn [id]
                (let [e (base/entity [:block/uuid id])]
                  {:db/id (:db/id e)
                   :block/uuid id
                   :block/content (:block/content e)
                   :block/format (:block/format e)}))))))

(defn clean-export!
  [db]
  (let [remove? #(contains? #{"me" "recent" "file"} %)
        filtered-db (d/filter db
                              (fn [db datom]
                                (let [ns (namespace (:a datom))]
                                  (not (remove? ns)))))
        datoms (d/datoms filtered-db :eavt)]
    @(d/conn-from-datoms datoms db-schema/schema)))

(defn filter-only-public-pages-and-blocks
  [db]
  (let [public-pages (get-public-pages db)
        contents-id (:db/id (base/entity [:page/name "contents"]))]
    (when (seq public-pages)
      (let [public-pages (set (conj public-pages contents-id))
            page-or-block? #(contains? #{"page" "block" "me" "recent" "file"} %)
            filtered-db (d/filter db
                                  (fn [db datom]
                                    (let [ns (namespace (:a datom))]
                                      (or
                                       (not (page-or-block? ns))
                                       (and (= ns "page")
                                            (contains? public-pages (:e datom)))
                                       (and (= ns "block")
                                            (contains? public-pages (:db/id (:block/page (d/entity db (:e datom))))))))))
            datoms (d/datoms filtered-db :eavt)]
        @(d/conn-from-datoms datoms db-schema/schema)))))

(defn delete-blocks
  [repo-url files]
  (when (seq files)
    (let [blocks (get-files-blocks repo-url files)]
      (mapv (fn [eid] [:db.fn/retractEntity eid]) blocks))))

(defn delete-files
  [files]
  (mapv (fn [path] [:db.fn/retractEntity [:file/path path]]) files))

(defn delete-file-blocks!
  [repo-url path]
  (let [blocks (get-file-blocks repo-url path)]
    (mapv (fn [eid] [:db.fn/retractEntity eid]) blocks)))

(defn delete-file-pages!
  [repo-url path]
  (let [pages (get-file-pages repo-url path)]
    (mapv (fn [eid] [:db.fn/retractEntity eid]) pages)))

(defn delete-file-tx
  [repo-url file-path]
  (->>
   (concat
    (delete-file-blocks! repo-url file-path)
    (delete-file-pages! repo-url file-path)
    [[:db.fn/retractEntity [:file/path file-path]]])
   (remove nil?)))

(defn delete-file!
  [repo-url file-path]
  (base/transact! repo-url (delete-file-tx repo-url file-path)))

(defn delete-pages-by-files
  [files]
  (let [pages (->> (mapv get-file-page files)
                   (remove nil?))]
    (when (seq pages)
      (mapv (fn [page] [:db.fn/retractEntity [:page/name page]]) (map string/lower-case pages)))))
