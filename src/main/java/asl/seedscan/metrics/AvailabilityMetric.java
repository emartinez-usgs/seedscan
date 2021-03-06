package asl.seedscan.metrics;

import asl.metadata.Channel;
import asl.metadata.meta_new.ChannelMeta;
import asl.seedsplitter.DataSet;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Availability Metric produces a percentage representing actual data samples compared to expected
 * samples.
 *
 * @author Joel Edwards - USGS
 * @author James Holland -USGS
 */
public class AvailabilityMetric extends Metric {

  private static final Logger logger = LoggerFactory
      .getLogger(asl.seedscan.metrics.AvailabilityMetric.class);

  @Override
  public long getVersion() {
    return 1;
  }

  @Override
  public String getName() {
    return "AvailabilityMetric";
  }

  public void process() {
    logger.info("-Enter- [ Station {} ] [ Day {} ]", getStation(), getDay());

    List<Channel> channels = stationMeta.getContinuousChannels();
    for (Channel channel : channels) {
      ByteBuffer digest = metricData.valueDigestChanged(channel,
          createIdentifier(channel), getForceUpdate());

      if (digest == null) {
        // means oldDigest == newDigest and we don't need to recompute the metric
        logger.info(
            "Digest unchanged station:[{}] channel:[{}] day:[{}] --> Skip metric",
            getStation(), channel, getDay());
        continue;
      }
      metricResult.addResult(channel, computeMetric(channel), digest);
    }
  }

  @Override
  public String getSimpleDescription() {
    return "Returns a percentage of expected samples in the trace";
  }

  @Override
  public String getLongDescription() {
    return "For a sensor's sample rate and the length of a full day, this metric compares the "
        + "number of points gotten from the data archive with the expected number of samples it "
        + "should have. This is returned as a percentage; if we have 1 point when we expected 2, "
        + "this would be a 50% availability result.";
  }

  private double computeMetric(Channel channel) {

    // AvailabilityMetric still returns a result (availability=0) even when
    // there is NO data for this channel
    if (!metricData.hasChannelData(channel)) {
      return 0.;
    }

    // The expected (=from metadata) number of samples:
    ChannelMeta chanMeta = stationMeta.getChannelMetadata(channel);
    final BigDecimal SECONDS_PER_DAY = new BigDecimal(86400);
    BigDecimal metaSR = BigDecimal.valueOf(chanMeta.getSampleRate());

    int expectedPoints = metaSR.multiply(SECONDS_PER_DAY).intValue();

    // The actual (=from data) number of samples:
    List<DataSet> datasets = metricData.getChannelData(channel);

    int ndata = 0;

    for (DataSet dataset : datasets) {
      // Check sample rates of metadata and station channel data
      BigDecimal dataSR = BigDecimal.valueOf(dataset.getSampleRate());

      /*
      rdseed produces sample rates with only a scale of 6 decimal places regardless of precision
      SEED data can have about 6 precision places
      Create same math context for comparison so precision over 5 isn't a problem.

      This could have problems if a sample rate uses more than 5 precision places.
      EG 100001 hz vs 100000 hz
      */
      MathContext mathContext = new MathContext(5, RoundingMode.HALF_UP);

      if (!dataSR.round(mathContext).equals(metaSR.round(mathContext))) {
        logger.error(
            "SampleRate Mismatch: station:[{}] channel:[{}] day:[{}] "
                + "metaSR:[{}] dataSR:[{}]", getStation(),
            channel, getDay(), metaSR, dataSR);
        continue;
      }
      ndata += dataset.getLength();
    }

    double availability;
    if (expectedPoints > 0) {
      availability = 100. * ndata / expectedPoints;
    } else {
      logger.info("Expected points for channel={} = 0!", channel);
      return NO_RESULT;
    }
    if (availability >= 101.00) {
      logger.warn(
          "Availability={} > 101%% for channel={} sRate={} day={}",
          availability, channel, chanMeta.getSampleRate(), getDay());
    }

    return availability;
  }
}
