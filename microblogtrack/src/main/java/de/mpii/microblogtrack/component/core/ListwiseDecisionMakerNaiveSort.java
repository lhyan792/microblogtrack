package de.mpii.microblogtrack.component.core;

import de.mpii.microblogtrack.utility.CandidateTweet;
import de.mpii.microblogtrack.utility.Configuration;
import de.mpii.microblogtrack.utility.QueryTweetPair;
import de.mpii.microblogtrack.utility.io.printresult.ResultPrinter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import org.apache.log4j.Logger;

/**
 * collect tweets from lucene, thereafter return top-100 list at the end of day.
 * in particular, we keep track the first k documents with highest score since
 * today, at the end of each day we select top-100 tweets to generate output for
 * e-mail digest
 *
 * @author khui
 */
public class ListwiseDecisionMakerNaiveSort extends ListwiseDecisionMaker {

    static Logger logger = Logger.getLogger(ListwiseDecisionMakerNaiveSort.class.getName());

    public ListwiseDecisionMakerNaiveSort(Map<String, LuceneDMConnector> tracker, BlockingQueue<QueryTweetPair> tweetqueue, ResultPrinter resultprinter) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        super(tracker, tweetqueue, resultprinter);
        logger.info(ListwiseDecisionMakerNaiveSort.class.getName() + " is being used.");
    }

    /**
     * simply rank the tweets according to the prediction score and pick up the
     * top-k
     *
     * @param queue
     * @param qid
     * @return
     * @throws ClassNotFoundException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    @Override
    protected List<CandidateTweet> decisionMake(PriorityBlockingQueue<QueryTweetPair> queue, String qid) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        List<QueryTweetPair> candidateTweets = new ArrayList<>();
        List<CandidateTweet> selectedQTPs = new ArrayList<>();
        int tweetnum = queue.drainTo(candidateTweets);
        if (tweetnum <= 0) {
            logger.error("The candidate tweet list is empty");
            return null;
        }
        // decreasing order
        candidateTweets.sort((QueryTweetPair o1, QueryTweetPair o2) -> {
            if (o1.getAbsScore() > o2.getAbsScore()) {
                return -1;
            } else if (o1.getAbsScore() < o2.getAbsScore()) {
                return 1;
            } else if (o1.tweetid > o2.tweetid) {
                return -1;
            } else {
                return 1;
            }
        });

        int rank = 1;
        for (QueryTweetPair candidatetweet : candidateTweets) {
            CandidateTweet resultTweet = new CandidateTweet(candidatetweet, rank++);
            selectedQTPs.add(resultTweet);
            synchronized (qidSentTweetQueues) {
                updateSentTracker(resultTweet, qidSentTweetQueues, Configuration.LW_DM_SENT_QUEUETRACKER_LENLIMIT);
            }
            if (rank > Configuration.LW_DM_SELECTNUM) {
                break;
            }
        }
        return selectedQTPs;
    }

}
