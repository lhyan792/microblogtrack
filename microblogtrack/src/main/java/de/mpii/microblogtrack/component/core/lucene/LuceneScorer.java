package de.mpii.microblogtrack.component.core.lucene;

import de.mpii.microblogtrack.component.ExtractTweetText;
import de.mpii.microblogtrack.component.IndexTracker;
import de.mpii.microblogtrack.component.core.LuceneDMConnector;
import de.mpii.microblogtrack.component.filter.Filter;
import de.mpii.microblogtrack.component.filter.LangFilterTW;
import de.mpii.microblogtrack.component.predictor.PointwiseScorer;
import de.mpii.microblogtrack.task.offline.qe.ExpandQueryWithWiki;
import de.mpii.microblogtrack.userprofiles.TrecQuery;
import de.mpii.microblogtrack.utility.QueryTweetPair;
import de.mpii.microblogtrack.utility.Configuration;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import twitter4j.Status;

/**
 * core class: index the incoming tweet, and periodically retrieve top tweets
 * for each query for further processing.
 *
 * usage: pass status for indexing, the output is query, tweet pairs stored in a
 * blocking queue
 *
 * TODO: better lang detector, tokenizer
 *
 * @author khui
 */
public class LuceneScorer {
    
    static Logger logger = Logger.getLogger(LuceneScorer.class.getName());
    
    static final FieldType TEXT_OPTIONS = new FieldType();
    
    static {
        TEXT_OPTIONS.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        TEXT_OPTIONS.setStored(true);
        TEXT_OPTIONS.setTokenized(true);
    }
    
    private final IndexWriter writer;
    
    private DirectoryReader directoryReader;
    
    private final Analyzer analyzer;

    // track duplicate tweet and allocate unique tweetCountId to each received tweet
    private final IndexTracker indexTracker;
    
    private final ExtractTweetText textextractor;
    // language filter, retaining english tweets
    private final Filter langfilter;
    
    private final Map<String, LuceneDMConnector> relativeScoreTracker;
    
    private final PointwiseScorer pwScorer;
    
    public LuceneScorer(String indexdir, Map<String, LuceneDMConnector> queryTweetList, PointwiseScorer pwScorer) throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        Directory dir = FSDirectory.open(Paths.get(indexdir));
        this.analyzer = (Analyzer) Class.forName(Configuration.LUCENE_ANALYZER).newInstance();
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        iwc.setRAMBufferSizeMB(Configuration.LUCENE_MEM_SIZE);
        this.writer = new IndexWriter(dir, iwc);
        this.directoryReader = DirectoryReader.open(writer, false);
        this.textextractor = new ExtractTweetText(Configuration.LUCENE_DOWNLOAD_URL_TIMEOUT);
        this.indexTracker = new IndexTracker();
        this.langfilter = new LangFilterTW();
        this.relativeScoreTracker = queryTweetList;
        this.pwScorer = pwScorer;
    }
    
    public void multiQuerySearch(String queryfile, String expandqueryfile, BlockingQueue<QueryTweetPair> queue2offer4PW, BlockingQueue<QueryTweetPair> queue2offer4LW) throws IOException, InterruptedException, ExecutionException, ParseException {
        Map<String, Map<String, Query>> qidFieldQuery = prepareQuery(queryfile);
        Map<String, Map<String, Query>> eqidFieldQuery = prepareExpandedQuery(expandqueryfile);
        logger.info("read in query and expanded query sizes/types: " + qidFieldQuery.size());
        // initialize trackers: track the centroids, the relative score
        for (String queryid : qidFieldQuery.keySet()) {
            if (!relativeScoreTracker.containsKey(queryid)) {
                relativeScoreTracker.put(queryid, new LuceneDMConnector(queryid));
            }
        }
        Executor downloadURLExcutor = Executors.newFixedThreadPool(Configuration.LUCENE_DOWNLOAD_URL_THREADNUM);
        Executor uniqquerysearchExecutor = Executors.newFixedThreadPool(Configuration.LUCENE_SEARCH_THREADNUM);
        
        ScheduledExecutorService multiqueryScheduler = Executors.newScheduledThreadPool(1);
        MultiQuerySearcher mqs = new MultiQuerySearcher(qidFieldQuery, eqidFieldQuery, queue2offer4PW, queue2offer4LW, downloadURLExcutor, uniqquerysearchExecutor);
        final ScheduledFuture<?> sercherHandler = multiqueryScheduler.scheduleAtFixedRate(mqs, Configuration.LUCENE_SEARCH_FREQUENCY, Configuration.LUCENE_SEARCH_FREQUENCY, Configuration.TIMEUNIT);

//        // the task will be canceled after running certain days automatically
//        multiqueryScheduler.schedule(
//                () -> {
//                    sercherHandler.cancel(true);
//                }, 12, TimeUnit.DAYS
//        );
    }
    
    private Map<String, Map<String, Query>> prepareQuery(String queryfile) throws IOException, ParseException {
        TrecQuery tq = new TrecQuery();
//        Map<String, Map<String, Query>> qidFieldQuery = tq.readFieldQueries(queryfile, analyzer);
        Map<String, Map<String, Query>> qidFieldQuery = tq.readFieldQueries15(queryfile);
        return qidFieldQuery;
    }
    
    private Map<String, Map<String, Query>> prepareExpandedQuery(String expandfile) throws IOException {
        Map<String, Map<String, Query>> qidFieldQuery = ExpandQueryWithWiki.readExpandedFieldQueries(expandfile, analyzer, Configuration.QUERY_EXPANSION_TERMNUM);
        return qidFieldQuery;
    }

    /**
     * for debug
     *
     * @param qtp
     * @param resultcount
     * @return
     */
    public static String printQueryTweet(QueryTweetPair qtp, int resultcount) {
        DecimalFormat df = new DecimalFormat("#.000");
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(resultcount).append(" ");
        sb.append(qtp.queryid).append("--").append(qtp.tweetid).append(" ");
        for (String name : Configuration.FEATURES_RETRIVEMODELS) {
            sb.append(name).append(":").append(df.format(qtp.getFeature(name))).append(",");
        }
        sb.append(" ").append(qtp.getTweetText());
        return sb.toString();
    }
    
    public void closeWriter() throws IOException {
        writer.close();
    }
    
    public long write2Index(Status tweet) throws IOException {
        boolean isEng = langfilter.isRetain(null, null, tweet);
        if (isEng) {
            long tweetcountId = indexTracker.isDuplicate(null, null, tweet);
            if (tweetcountId > 0) {
                HashMap<String, String> fieldContent = status2Fields(tweet);
                Document doc = new Document();
                doc.add(new LongField(Configuration.TWEET_COUNT, tweetcountId, Field.Store.YES));
                doc.add(new LongField(Configuration.TWEET_ID, tweet.getId(), Field.Store.YES));
                for (String fieldname : fieldContent.keySet()) {
                    doc.add(new Field(fieldname, fieldContent.get(fieldname), TEXT_OPTIONS));
                }
                writer.addDocument(doc);
                return tweetcountId;
            }
        }
        return -1;
    }
    
    private HashMap<String, String> status2Fields(Status status) throws IOException {
        HashMap<String, String> fieldnameStr = new HashMap<>();
        String tweetcontent = textextractor.getTweet(status);
        // String tweeturltitle = textextractor.getUrlTitle(status);
        fieldnameStr.put(Configuration.TWEET_CONTENT, tweetcontent);
        //fieldnameStr.put(Configuration.TWEET_URL_TITLE, tweeturltitle);
        return fieldnameStr;
        
    }

    /**
     * periodically called, each time we only have one thread for
     * multiquerysearcher. It further splits the task and great thread for each
     * <query, search field> combination
     *
     */
    private class MultiQuerySearcher implements Runnable {
        
        private final Map<String, Map<String, Query>> qidFieldQuery;
        
        private final Map<String, Map<String, Query>> eqidFieldQuery;
        
        private final BlockingQueue<QueryTweetPair> queue2offer4PW;
        
        private final BlockingQueue<QueryTweetPair> queue2offer4LW;
        
        private final Executor downloadURLExcutor;
        
        private final Executor uniqquerysearchExecutor;
        /**
         * to report how many tweets we received in last period
         */
        private int count_runningtime = 0;
        private int count_tweets = 0;
        
        public MultiQuerySearcher(final Map<String, Map<String, Query>> qidFieldQuery,
                final Map<String, Map<String, Query>> expandQueries,
                BlockingQueue<QueryTweetPair> queue2offer4PW,
                BlockingQueue<QueryTweetPair> queue2offer4LW, Executor downloadURLExcutor,
                Executor uniqquerysearchExecutor) {
            this.qidFieldQuery = qidFieldQuery;
            this.eqidFieldQuery = expandQueries;
            this.queue2offer4PW = queue2offer4PW;
            this.queue2offer4LW = queue2offer4LW;
            this.downloadURLExcutor = downloadURLExcutor;
            this.uniqquerysearchExecutor = uniqquerysearchExecutor;
        }
        
        private Map<String, Query> generateQuery(long[] minmax, String queryId) {
            Map<String, Query> querytypeQuery = new HashMap<>();
            NumericRangeQuery rangeQuery = NumericRangeQuery.newLongRange(Configuration.TWEET_COUNT, minmax[0], minmax[1], true, false);
            BooleanQuery combinedQuery;
            for (String querytype : Configuration.QUERY_TYPES) {
                // each query type ultimately corresponds to several features: 
                // like bm25 on tweet_content with expanded query
                combinedQuery = new BooleanQuery();
                combinedQuery.add(rangeQuery, Occur.MUST);
                switch (querytype) {
                    case Configuration.QUERY_TITLE:
                        combinedQuery.add(qidFieldQuery.get(queryId).get(Configuration.QUERY_TITLE), Occur.SHOULD);
                        break;
                    case Configuration.QUERY_DESC:
                        combinedQuery.add(qidFieldQuery.get(queryId).get(Configuration.QUERY_DESC), Occur.SHOULD);
                        break;
                    case Configuration.QUERY_NARR:
                        combinedQuery.add(qidFieldQuery.get(queryId).get(Configuration.QUERY_NARR), Occur.SHOULD);
                        break;
                    case Configuration.QUERY_EXPAN:
                        combinedQuery.add(eqidFieldQuery.get(queryId).get(Configuration.QUERY_EXPAN), Occur.SHOULD);
                        break;
                    default:
                        logger.error(querytype + " is not available");
                }
                querytypeQuery.put(querytype, combinedQuery.clone());
            }
            return querytypeQuery;
        }
        
        @Override
        public void run() {
            logger.info("lucene search started");
            if (Thread.interrupted()) {
                logger.info("lucene scorer get interrupted and will stop");
                try {
                    closeWriter();
                } catch (IOException ex) {
                    logger.error("", ex);
                }
                System.exit(0);
            }
            
            Map<String, Query> querytypeQuery;
            CompletionService<UniqQuerySearchResult> completeservice = new ExecutorCompletionService<>(uniqquerysearchExecutor);
            long[] minmax = indexTracker.minMaxTweetCountInTimeInterval();
            ////////////////////////////
            /// report number of tweets received in the latest period
            count_runningtime++;
            count_tweets += (minmax[1] - minmax[0]);
            if (count_runningtime >= Configuration.LW_DM_PERIOD / Configuration.LUCENE_SEARCH_FREQUENCY) {
                logger.info(count_tweets + " tweets are written to Lucene index in past " + Configuration.LW_DM_PERIOD + " miniutes.");
                count_runningtime = 0;
                count_tweets = 0;
            }
            ////////////////////////////
            //NumericRangeQuery rangeQuery = NumericRangeQuery.newLongRange(Configuration.TWEET_COUNT, minmax[0], minmax[1], true, false);
            DirectoryReader reopenedReader = null;
            try {
                reopenedReader = DirectoryReader.openIfChanged(directoryReader);
            } catch (Exception ex) {
                logger.error("", ex);
            }
            if (reopenedReader != null) {
                try {
                    directoryReader.close();
                } catch (Exception ex) {
                    logger.error("", ex);
                }
                directoryReader = reopenedReader;
                /**
                 * submit each query (query or expanded query, on tweetcontent)
                 * as a job to completion service
                 */
                int resultnum = 0;
                try {
                    
                    for (String queryid : qidFieldQuery.keySet()) {
                        querytypeQuery = generateQuery(minmax, queryid);
                        completeservice.submit(new UniqQuerySearcher(querytypeQuery, queryid, directoryReader, downloadURLExcutor, indexTracker, textextractor, pwScorer));
                        resultnum++;
                    }
                } catch (Exception ex) {
                    logger.error("", ex);
                }
                /**
                 * pick up the returned results as UniqQuerySearchResult
                 */
                UniqQuerySearchResult queryranking;
                for (int i = 0; i < resultnum; ++i) {
                    try {
                        final Future<UniqQuerySearchResult> futureQtpCollection = completeservice.take();
                        queryranking = futureQtpCollection.get();
                        if (queryranking != null) {
                            // update the result tracker
                            relativeScoreTracker.get(queryranking.queryid).addTweets(queryranking.getSearchResults());
                            // after the pointwise decision maker start, we start to send 
                            // tweets to pw decision maker, before that, we only send to
                            // the listwise decision maker
                            if (relativeScoreTracker.get(queryranking.queryid).whetherOffer2LWQueue()
                                    && relativeScoreTracker.get(queryranking.queryid).whetherOffer2PWQueue()) {
                                queryranking.offer2queue(queue2offer4PW, queue2offer4LW);
                            } else if (relativeScoreTracker.get(queryranking.queryid).whetherOffer2LWQueue()) {
                                queryranking.offer2queue(queue2offer4LW);
                            }
                        } else {
                            logger.error("queryranking is null.");
                        }
                        
                    } catch (ExecutionException | InterruptedException ex) {
                        logger.error("Write into the queue for DM", ex);
                    }
                }
            } else {
                logger.warn("Nothing added to the index since last open of reader.");
            }
        }
        
    }
    
}
