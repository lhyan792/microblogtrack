package de.mpii.microblogtrack.utility;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * suggested strategies: since we are supposed to return top-100 and top-10
 * tweets respectively for the notification task and email digest task, the
 * ultimate candidates tweets should be around 1000. Thus, each hour we'd better
 * return 40 tweets/user, it means we want 10 tweets/quarter. In total, we have
 * 96 quarters for each day, and the notification should start after half of
 * that, i.e, starting popping up in afternoon.
 *
 * most of the parameters should be set thru the property files, here is only
 * one example, we supposed the machine have 16 cores and more than 40 G main
 * memory
 *
 * @author khui
 */
public class Configuration {

    public static String RUN_ID = "MPII";
    // for debug, default is miniute
    public static TimeUnit TIMEUNIT = TimeUnit.MINUTES;

    /**
     * fields name used for retrieval of tweets
     */
    public final static String TWEET_ID = "tweetid";
    // count of tweets being downloaded
    public final static String TWEET_COUNT = "tweetcountid";

    public final static String TWEET_CONTENT = "tweetcontent";

    public final static String TWEET_URL_CONTENT = "langdingpagecontent";

    public final static String TWEET_URL_TITLE = "langdingpagetitle";

    /**
     * fields name for query
     */
    public final static String QUERY_ID = "queryId";

    public final static String QUERY_STR = "query";

    public final static String QUERY_TITLE = "title";

    public final static String QUERY_DESC = "description";

    public final static String QUERY_NARR = "narrative";

    public final static String QUERY_EXPAN = "expanded";
    // used to tune the system
    public final static String QUERY_QUERYTWEETTIME = "querytweettime";

    /**
     * additional field names for result printer
     */
    public final static String RES_RANK = "rank";

    // all these query will generate features
    public final static String[] QUERY_TYPES = new String[]{QUERY_STR, QUERY_EXPAN};

    public static int QUERY_EXPANSION_TERMNUM = 10;

    /**
     * parameters for lucene systems
     */
    // the size of the in-memory index, determining how often the writer dump the index to the disk
    public final static double LUCENE_MEM_SIZE = 1024.0 * 10;

    public final static String LUCENE_ANALYZER = "org.apache.lucene.analysis.en.EnglishAnalyzer";
    // lucene perform search per LUCENE_SEARCH_FREQUENCY seconds
    public final static int LUCENE_SEARCH_FREQUENCY = 15;
    // every time invertal, we retrieve top_n tweets for each query for further processin from lucene
    public static int LUCENE_TOP_N_SEARCH = 10;
    // number of threads we use to conduct multiquery search
    public static int LUCENE_SEARCH_THREADNUM = 2;
    // the setting timeout for the url download depends on how
    // many threads we have for that, in general, when 10 threads
    // are used, 3.5 seconds should be safe
    public static int LUCENE_DOWNLOAD_URL_TIMEOUT = 3500;

    public static int LUCENE_DOWNLOAD_URL_THREADNUM = 10;

    /**
     * pointwise decision maker parameters for mobile notification task
     */
    // adjust the threshold for pop-up tweets dynamically, governing the adjusting step
    public final static double PW_DM_THRESHOLD_ALPHA = 0.01;
    // filter out the tweet with less than threshold relative score
    public final static double PW_DM_SCORE_FILTER = 0.95;
    // if it is the first tweet to pop-up, we require a relative high threshold
    public final static double PW_DM_FIRSTPOPUP_SCORETHRESD = 0.999;
    // filter out the tweets that are too similar with at least one of the pop-up tweet
    // the parameter setting depends on which type of similarity we used
    public static double DM_SIMILARITY_FILTER = 0.6;
    // the number of sent tweets to be compared for duplication filter when a new tweet comes in
    // since this is expensive iteration for each incoming tweets, we set up a queue to track latest
    // most suspicious sent tweets
    public static int PW_DM_SENT_QUEUETRACKER_LENLIMIT = 30;
    // start delay for the decision maker in minutes 
    public static int PW_DM_START_DELAY = 60;
    // decision maker calling period in minutes, should be 1440 if one day is a period  
    public static int PW_DM_PERIOD = 1440;
    // top-k to report for one day, in notification tasks we at most report 10
    public static int PW_DM_SELECTNUM = 10;
    // make decision untill we have receive enough tweets
    public static int PW_DW_CUMULATECOUNT_DELAY = LUCENE_TOP_N_SEARCH * 10;
    /**
     * listwise decision maker for e-mail digest task
     */
    // the number of sent tweets to be compared for duplication filter when a new tweet comes in
    public static int LW_DM_SENT_QUEUETRACKER_LENLIMIT = 200;
    // decision maker calling period in minutes, should be 1440 if one day is a period  
    public static int LW_DM_PERIOD = 60 * 24;
    // the length of the priority queue: tracking at most n tweets with highest pointwise prediction score
    // governing on how many tweets we want to conduct rerank
    public static int LW_DM_QUEUE2PROCESS_LEN = 500;
    // top-k to return for email digest task, the upper bound is 100
    public static int LW_DM_SELECTNUM = 100;
    // start delay for listwise decision maker
    public static int LW_DM_START_DELAY = 60;

    /**
     * parameter for maxrep
     */
    // depends on what type of similarity we used
    public static double MAXREP_SIMI_THRESHOLD = 0.1;

    public final static String MAXREP_DISTANT_MEASURE = "";
    /**
     * tweet tracker parameters: track both relative score and tweet clustering
     * centroids
     */

    // when we convert the absolute pointwise predict score to relative score,
    // we only convert the top-p% for effiency reason
    public final static double TRACKER_CUMULATIVE_TOPPERC = 0.5;
    // how accurate we compute the cumulative probability in converting the absolute predicting score
    // by governing how many digits we want to retain, i.e., the number of bins we have
    public static int TRACKER_CUMULATIVE_GRANULARITY = 10000;

    /**
     * pointwise predictor outcome: confidence, score, etc..
     */
    public final static String PRED_ABSOLUTESCORE = "absolutePrimaryScore";

    public final static String PRED_RELATIVESCORE = "relativePrimaryScore";
    /**
     * feature names and parameters
     */
    public final static String FEATURE_S_BM25 = "bm25";

    public final static float FEATURE_S_BM25_b = 0f;

    public final static float FEATURE_S_BM25_k1 = 1.2f;

    public final static String FEATURE_S_TFIDF = "tfidf";

    public final static String FEATURE_S_LMD = "lmDirichlet";

    public final static int FEATURE_S_LMD_mu = 140;

    public final static String FEATURE_S_LMJM = "lmJelinekMercer";

    public final static float FEATURE_S_LMJM_Lambda = 0.1f;

    public final static String FEATURE_S_DFR_BE_B = "dfr_ibe_b";

    public final static String FEATURE_S_DFR_IF_L = "dfr_if_l";

    public final static String FEATURE_T_RETWEETNUM = "tweet_retweet_count";

    public final static String FEATURE_T_FAVORITENUM = "tweet_favorite_count";

    public final static String FEATURE_T_HASHTAGNUM = "tweet_hashtag_count";

    public final static String FEATURE_T_URLNUM = "tweet_url_count";

    public final static String FEATURE_T_USERMENTIONNUM = "tweet_usermention_count";

    public final static String FEATURE_T_MEDIANUM = "tweet_media_count";

    public final static String FEATURE_U_FOLLOWERNUM = "user_follower_count";

    public final static String FEATURE_U_DESC_LEN = "user_description_length";

    public final static String FEATURE_U_DESC_URLNUM = "user_descriptionurl_count";

    public final static String FEATURE_U_FAVORITENUM = "user_favorite_count";

    public final static String FEATURE_U_FRIENDNUM = "user_friend_count";

    public final static String FEATURE_U_LISTNUM = "user_inlist_count";

    public final static String FEATURE_U_STATUSNUM = "user_status_count";

    public final static String FEATURE_U_ISCELEBRITY = "user_iscelebrity";

    public final static String FEATURE_U_ISDEFAULT_ICON = "user_isdefault_profileimage";
    // feature name array to categorize above features into four classes
    public final static String[] FEATURES_RETRIVEMODELS = new String[]{FEATURE_S_TFIDF, FEATURE_S_BM25, FEATURE_S_LMD,
        FEATURE_S_LMJM, FEATURE_S_DFR_BE_B, FEATURE_S_DFR_IF_L};

    public final static String[] FEATURES_TWEETQUALITY = new String[]{FEATURE_T_RETWEETNUM, FEATURE_T_FAVORITENUM, FEATURE_T_HASHTAGNUM, FEATURE_T_URLNUM, FEATURE_T_USERMENTIONNUM, FEATURE_T_MEDIANUM};

    public final static String[] FEATURES_USERAUTHORITY = new String[]{FEATURE_U_FOLLOWERNUM, FEATURE_U_DESC_LEN, FEATURE_U_DESC_URLNUM, FEATURE_U_FAVORITENUM, FEATURE_U_FRIENDNUM, FEATURE_U_LISTNUM, FEATURE_U_STATUSNUM, FEATURE_U_ISCELEBRITY, FEATURE_U_ISDEFAULT_ICON};
    // for features that only have binary value, should not be scaled in feature normalization
    public final static List<String> FEATURES_NO_SCALE = Arrays.asList(new String[]{FEATURE_U_ISCELEBRITY, FEATURE_U_ISDEFAULT_ICON});
    /**
     * other parameters
     */
    // number of thread we use to listen the api, 1 is enough
    public static int LISTENER_THREADNUM = 1;
}
