package de.mpii.microblogtrack.utility;

import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.math3.random.EmpiricalDistribution;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.mahout.clustering.streaming.cluster.BallKMeans;
import org.apache.mahout.clustering.streaming.cluster.StreamingKMeans;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.math.Centroid;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.neighborhood.UpdatableSearcher;
import org.apache.mahout.math.neighborhood.ProjectionSearch;
import org.apache.mahout.math.random.WeightedThing;

/**
 *
 * @author khui
 */
public class QueryTweets {

    static Logger logger = Logger.getLogger(QueryTweets.class);

    public final String queryid;

    // contain feature vector for latest RECORD_MINIUTES minutes tweets
    private final TIntObjectMap<CandidateTweet> tidFeatures;

    private final int numberOfTweetsToKeep = MYConstants.TOP_N_FROM_LUCENE * MYConstants.RECORD_MINIUTES;

    private final TLongObjectMap<double[]> tidPointwiseScores;

    private EmpiricalDistribution edistPredictScore = null;
    // summarized pointwise prediction results for the computation of empirical dist
    private final TDoubleList pointwiseScore;

    private final String[] scorenames = new String[]{MYConstants.PREDICTSCORE};

    private UpdatableSearcher streamKMCentroids;

    private StreamingKMeans clusterer;

    private final DistanceMeasure distanceMeasure = new CosineDistanceMeasure();

    private final int numProjections = 20;

    private final int searchSize = 10;

    private int tweetcount = 0;

    public QueryTweets(String queryid) {
        this.queryid = queryid;
        this.tidFeatures = new TIntObjectHashMap<>(numberOfTweetsToKeep);
        this.tidPointwiseScores = new TLongObjectHashMap<>();
        this.pointwiseScore = new TDoubleArrayList();
        initialStreamingKmean();
    }

    private void initialStreamingKmean() {

        // the computation for the upper bound of cluster numbers is computed as #desired cluster * log(#data number)
        // we estimate the expected tweet number for 10 hours
        int streamkmeanClusterNum = 400;
        // initialize an empty streamKMCentroids set
        this.streamKMCentroids = new ProjectionSearch(distanceMeasure, numProjections, searchSize);
        this.clusterer = new StreamingKMeans(streamKMCentroids, streamkmeanClusterNum);
    }

    /**
     * add a tweet to this query profile: add features, add scores from the
     * pointwise prediction procedure. note that both the features and the
     * prediction scores can be multi dimensions. The example for the latter is,
     * the outcome of the svm prediction, normally including confidence, the
     * distance to the hyperplane etc..
     *
     * @param qtp
     */
    public void addTweet(QueryTweetPair qtp) {
        long tweetid = qtp.tweetid;
        Vector v = qtp.vectorize();
        double score = summarizePointwisePrediction(tweetid, qtp.getPredictRes());
        // the unique predicting score for one tweet, and the corresponding cumulative prob is used as vector weight
        double prob = getCumulativeProb(score);
        // keep tracking the latest numberOfTweetsToKeep tweets
        tidFeatures.put(tweetcount % numberOfTweetsToKeep, new CandidateTweet(tweetid, prob, this.queryid, v));
        // use the tweet count as the centroid key
        clusterer.cluster(new Centroid(++tweetcount, v.clone(), prob));
    }

    /**
     * core function for decision making: 1) get updated centroidnum centroids;
     * 2) search among the latest tweets, to get the closest searchSize tweets
     * and record their score cumulative probability (the higher the better),
     * distance information in CandidateTweet object; 3) the final decision is
     * based on both the relative probability and the distance to the centroid.
     * Note that, the centroids are changed with time, thus no absolute
     * centroids are available
     *
     * @param centroidnum
     * @param topk
     * @return
     */
    public List<CandidateTweet> getToptweetsEachCentroid(int centroidnum, int topk) {
        // get the updated centroids, each one represents a potential subtopic
        Iterable<Vector> centroids = getCentroids(centroidnum);
        // latest tweets being tracked
        TIntObjectMap<CandidateTweet> latestTweets = new TIntObjectHashMap<>(tidFeatures);
        // construct a searcher
        UpdatableSearcher latestTweetSearcher = new ProjectionSearch(distanceMeasure, numProjections, searchSize);
        // result list
        List<CandidateTweet> candidateTweets = new ArrayList<>();
        // use all latest results to generate the searcher, against which to conduct search
        for (int tcount : latestTweets.keys()) {
            latestTweetSearcher.add(new Centroid(tcount, latestTweets.get(tcount).getFeature()));
        }
        // relative id, only to distinguish centroid in current round
        int centroidid = 1;
        CandidateTweet retrivedtweet;
        // for each centroid, pick up the topk closest tweets 
        for (Vector centroid : centroids) {
            List<WeightedThing<Vector>> closestTweets = latestTweetSearcher.search(centroid, topk);
            for (WeightedThing<Vector> wt : closestTweets) {
                Centroid tweetcentroid = (Centroid) wt.getValue();
                retrivedtweet = latestTweets.get(tweetcentroid.getIndex());
                retrivedtweet.setCentroidId(centroidid++);
                // the distance
                retrivedtweet.setDist(wt.getWeight());
                candidateTweets.add(new CandidateTweet(retrivedtweet));
            }
        }
        return candidateTweets;
    }

    /**
     * get exact centroidnum streamKMCentroids
     *
     * @param centroidnum
     * @param maxNumIterations
     * @return
     */
    private Iterable<Vector> getCentroids(int centroidnum) {
        int maxNumIterations = MYConstants.MAX_ITERATE_BALLKMEAN;
        UpdatableSearcher emptySercher = new ProjectionSearch(distanceMeasure, numProjections, searchSize);
        BallKMeans exactCentroids = new BallKMeans(emptySercher, centroidnum, maxNumIterations);
        List<Centroid> centroidList = new ArrayList<>();
        clusterer.reindexCentroids();
        Iterator<Centroid> it = clusterer.iterator();
        while (it.hasNext()) {
            centroidList.add(it.next().clone());
        }
        return exactCentroids.cluster(centroidList);
    }

    /**
     * get upto streamkmeanClusterNum centroids from stream kmean
     *
     * @return
     */
    public UpdatableSearcher getStreamKMeansCentroids() {
        return streamKMCentroids;
    }

    public double getCumulativeProb(double score) {
        double prob = 0.5;
        if (edistPredictScore != null) {
            prob = edistPredictScore.cumulativeProbability(score);
        }
        return prob;
    }

    private double summarizePointwisePrediction(long tweetid, TObjectDoubleMap<String> predictScores) {
        double[] scores = new double[scorenames.length];
        double presentativescore = 0;
        for (int i = 0; i < scorenames.length; i++) {
            if (predictScores.containsKey(scorenames[i])) {
                scores[i] = predictScores.get(scorenames[i]);
                if (scorenames[i].equals(MYConstants.PREDICTSCORE)) {
                    presentativescore = scores[i];
                    pointwiseScore.add(presentativescore);
                    // reload the estimate of probility per 10000 entries
                    if (pointwiseScore.size() % 10000 == 0 && edistPredictScore != null) {
                        this.edistPredictScore = new EmpiricalDistribution();
                        edistPredictScore.load(pointwiseScore.toArray());
                    }
                }
            }
        }
        tidPointwiseScores.put(tweetid, scores);
        return presentativescore;
    }

    public static void main(String[] args) throws FileNotFoundException, IOException {
        org.apache.log4j.PropertyConfigurator.configure("src/main/java/log4j.xml");
        //("src/main/java/log4j.xml");
        LogManager.getRootLogger().setLevel(org.apache.log4j.Level.WARN);
        // DenseVector testV = new DenseVector(new double[]{});
        // JavaSparkContext sc = new JavaSparkContext(conf);
        String file = "/home/khui/workspace/result/data/testCluster/R15.txt";
        BufferedReader br = new BufferedReader(new FileReader(new File(file)));

        int key = 1;
        TIntIntMap labels = new TIntIntHashMap();
        while (br.ready()) {
            String line = br.readLine();
            String[] cols = line.split("\t");
            Vector v = new DenseVector(new double[]{Double.parseDouble(cols[0]), Double.parseDouble(cols[1])});
            Centroid c = new Centroid(key, v);

            // System.out.println("Num:" + key + "   cluster" + clusterer.getNumClusters());
            key++;
        }

    }

}