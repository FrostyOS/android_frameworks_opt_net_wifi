/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.util.ArraySet;
import android.util.Rational;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>This class takes a series of scan requests and formulates the best hardware level scanning
 * schedule it can to try and satisfy requests. The hardware level accepts a series of buckets,
 * where each bucket represents a set of channels and an interval to scan at. This
 * scheduler operates as follows:</p>
 *
 * <p>Each new request is placed in the best predefined bucket. Once all requests have been added
 * the last buckets (lower priority) are placed in the next best bucket until the number of buckets
 * is less than the number supported by the hardware.
 *
 * <p>Finally, the scheduler creates a WifiNative.ScanSettings from the list of buckets which may be
 * passed through the Wifi HAL.</p>
 *
 * <p>This class is not thread safe.</p>
 */
public class MultiClientScheduler extends WifiScanningScheduler {

    private static final String TAG = WifiScanningService.TAG;
    private static final boolean DBG = false;

    /**
     * Value that all scan periods must be an integer multiple of
     */
    private static final int PERIOD_MIN_GCD_MS = 10000;
    /**
     * Default period to use if no buckets are being scheduled
     */
    private static final int DEFAULT_PERIOD_MS = 60000;
    /**
     * Scan report threshold percentage to assign to the schedule by default
     * @see com.android.server.wifi.WifiNative.ScanSettings#report_threshold_percent
     */
    private static final int DEFAULT_REPORT_THRESHOLD_PERCENTAGE = 100;

    /**
     * List of predefined periods (in ms) that buckets can be scheduled at. Ordered by preference
     * for if there are not enough buckets for all periods. All periods MUST be multiples of
     * PERIOD_MIN_GCD_MS and SHOULD be an integer multiple of the next shortest period. These
     * requirements allow scans to be scheduled more efficiently because scan requests with
     * intersecting channels will result in those channels being scanned exactly once at the smaller
     * period. If this was not the case and two requests had channel 5 with periods of 15 seconds
     * and 25 seconds then channel 5 would be scanned 296  (3600/15 + 3600/25 - 3500/75) times an
     * hour instead of 240 times an hour (3600/15) if the 25s scan is rescheduled at 30s. This is
     * less important with higher periods as it has significantly less impact. Ranking could be done
     * by favoring shorter or longer; however, this would result in straying further from the
     * requested period and possibly power implications if the scan is scheduled at a significantly
     * lower period.
     *
     * For example if the hardware only supports 2 buckets and scans are requested with periods of
     * 1m, 30s and 10s then the two buckets shceduled with have periods 1m and 30s and the 10s scan
     * will be placed in the 30s bucket.
     *
     * If there are special scan requests such as exponential back off scan or context hub scan, we
     * always dedicate a bucket for each type. Regular scan requests will be packed into the
     * remaining buckets.
     */
    private static final int[] PREDEFINED_BUCKET_PERIODS = {
        6 * PERIOD_MIN_GCD_MS,   // 1m
        3 * PERIOD_MIN_GCD_MS,   // 30s
        30 * PERIOD_MIN_GCD_MS,  // 5m
        60 * PERIOD_MIN_GCD_MS,  // 10m
        1 * PERIOD_MIN_GCD_MS,   // 10s
        180 * PERIOD_MIN_GCD_MS, // 30m
        90 * PERIOD_MIN_GCD_MS,  // 15m
        360 * PERIOD_MIN_GCD_MS, // 1h
        -1,                      // place holder for exponential back off scan
    };

    private static final int EXPONENTIAL_BACK_OFF_BUCKET_IDX =
            (PREDEFINED_BUCKET_PERIODS.length - 1);
    // TODO: Add support for context hub scan request when it is enabled
    private static final int NUM_OF_REGULAR_BUCKETS =
            (PREDEFINED_BUCKET_PERIODS.length - 1);

    /**
     * this class is an intermediate representation for scheduling
     */
    private static class Bucket {
        public int period;
        public final List<ScanSettings> settings = new ArrayList<>();

        public Bucket(int period) {
            this.period = period;
        }

        /**
         * convert ChannelSpec to native representation
         */
        private static WifiNative.ChannelSettings createChannelSettings(int frequency) {
            WifiNative.ChannelSettings channelSettings = new WifiNative.ChannelSettings();
            channelSettings.frequency = frequency;
            return channelSettings;
        }

        /**
         * convert the setting for this bucket to HAL representation
         */
        public WifiNative.BucketSettings createBucketSettings(int bucketId,
                int maxChannels) {
            int reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
            int maxPeriodInMs = 0;
            int stepCount = 0;
            ArraySet<Integer> channels = new ArraySet<Integer>();
            int exactBands = 0;
            int allBands = 0;

            for (int i = 0; i < settings.size(); ++i) {
                WifiScanner.ScanSettings setting = settings.get(i);
                int requestedReportEvents = setting.reportEvents;
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                    reportEvents &= ~WifiScanner.REPORT_EVENT_NO_BATCH;
                }
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0) {
                    reportEvents |= WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                }
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                    reportEvents |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                }

                WifiScanner.ChannelSpec[] settingChannels =
                        WifiChannelHelper.getChannelsForScanSettings(setting);
                for (int j = 0; j < settingChannels.length; ++j) {
                    channels.add(settingChannels[j].frequency);
                    allBands |=
                            WifiChannelHelper.getBandFromChannel(settingChannels[j].frequency);
                }
                if (setting.band != WifiScanner.WIFI_BAND_UNSPECIFIED) {
                    exactBands |= setting.band;
                }

                // For the bucket allocated to exponential back off scan, the values of
                // the exponential back off scan related parameters from the very first
                // setting in the settings list will be used as the settings for this bucket.
                if (i == 0 && setting.maxPeriodInMs != 0
                        && setting.maxPeriodInMs != setting.periodInMs) {
                    period = setting.periodInMs;
                    maxPeriodInMs = setting.maxPeriodInMs;
                    stepCount = setting.stepCount;
                }

            }

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            bucketSettings.bucket = bucketId;
            bucketSettings.report_events = reportEvents;
            bucketSettings.period_ms = period;
            bucketSettings.max_period_ms = maxPeriodInMs;
            bucketSettings.step_count = stepCount;
            if (channels.size() > maxChannels || allBands == exactBands) {
                bucketSettings.band = allBands;
                bucketSettings.num_channels = 0;
                bucketSettings.channels = null;
            } else {
                bucketSettings.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
                bucketSettings.num_channels = channels.size();
                bucketSettings.channels = new WifiNative.ChannelSettings[channels.size()];
                for (int i = 0; i < channels.size(); ++i) {
                    bucketSettings.channels[i] = createChannelSettings(channels.valueAt(i));
                }
            }

            return bucketSettings;
        }
    }

    /**
     * Maintains a list of buckets and the number that are active (non-null)
     */
    private static class BucketList {
        private final Bucket[] mBuckets;
        private int mActiveBucketCount = 0;

        public BucketList() {
            mBuckets = new Bucket[PREDEFINED_BUCKET_PERIODS.length];
        }

        public void clearAll() {
            Arrays.fill(mBuckets, null);
            mActiveBucketCount = 0;
        }

        public void clear(int index) {
            if (mBuckets[index] != null) {
                --mActiveBucketCount;
                mBuckets[index] = null;
            }
        }

        public Bucket getOrCreate(int index) {
            Bucket bucket = mBuckets[index];
            if (bucket == null) {
                ++mActiveBucketCount;
                bucket = mBuckets[index] = new Bucket(PREDEFINED_BUCKET_PERIODS[index]);
            }
            return bucket;
        }

        public boolean isActive(int index) {
            return mBuckets[index] != null;
        }

        public Bucket get(int index) {
            return mBuckets[index];
        }

        public int size() {
            return mBuckets.length;
        }

        public int getActiveCount() {
            return mActiveBucketCount;
        }

        public int getActiveRegularBucketCount() {
            if (isActive(EXPONENTIAL_BACK_OFF_BUCKET_IDX)) {
                return mActiveBucketCount - 1;
            } else {
                return mActiveBucketCount;
            }
        }
    }

    private final BucketList mBuckets = new BucketList();
    private WifiNative.ScanSettings mSchedule;

    public MultiClientScheduler() {
        mSchedule = createSchedule(Collections.<ScanSettings>emptyList());
    }

    @Override
    public void updateSchedule(@NonNull Collection<ScanSettings> requests) {
        // create initial schedule
        mBuckets.clearAll();
        for (ScanSettings request : requests) {
            addScanToBuckets(request);
        }

        compactBuckets(getMaxBuckets());

        mSchedule = createSchedule(requests);
    }

    @Override
    public @NonNull WifiNative.ScanSettings getSchedule() {
        return mSchedule;
    }

    @Override
    public boolean shouldReportFullScanResultForSettings(@NonNull ScanResult result,
            @NonNull ScanSettings settings) {
        ChannelSpec desiredChannels[] = WifiChannelHelper.getChannelsForScanSettings(settings);
        for (ChannelSpec channelSpec : desiredChannels) {
            if (channelSpec.frequency == result.frequency) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable ScanData[] filterResultsForSettings(@NonNull ScanData[] scanDatas,
            @NonNull ScanSettings settings) {
        ChannelSpec desiredChannels[] = WifiChannelHelper.getChannelsForScanSettings(settings);

        ArrayList<ScanData> filteredScanDatas = new ArrayList<>(scanDatas.length);
        ArrayList<ScanResult> filteredResults = new ArrayList<>();
        for (ScanData scanData : scanDatas) {
            filteredResults.clear();
            for (ScanResult scanResult : scanData.getResults()) {
                for (ChannelSpec channelSpec : desiredChannels) {
                    if (channelSpec.frequency == scanResult.frequency) {
                        filteredResults.add(scanResult);
                        break;
                    }
                }
                if (settings.numBssidsPerScan > 0
                        && filteredResults.size() >= settings.numBssidsPerScan) {
                    break;
                }
            }
            // TODO correctly note if scan results may be incomplete
            if (filteredResults.size() == scanData.getResults().length) {
                filteredScanDatas.add(scanData);
            } else if (filteredResults.size() > 0) {
                filteredScanDatas.add(new WifiScanner.ScanData(scanData.getId(),
                                scanData.getFlags(),
                                filteredResults.toArray(
                                        new ScanResult[filteredResults.size()])));
            }
        }
        if (filteredScanDatas.size() == 0) {
            return null;
        } else {
            return filteredScanDatas.toArray(new ScanData[filteredScanDatas.size()]);
        }
    }

    // creates a schedule for the given buckets and requests
    private WifiNative.ScanSettings createSchedule(Collection<ScanSettings> requests) {
        WifiNative.ScanSettings schedule = new WifiNative.ScanSettings();
        schedule.num_buckets = mBuckets.getActiveCount();
        schedule.buckets = new WifiNative.BucketSettings[mBuckets.getActiveCount()];

        // set all buckets in schedule
        int bucketId = 0;
        for (int i = 0; i < mBuckets.size(); ++i) {
            if (mBuckets.isActive(i)) {
                schedule.buckets[bucketId++] =
                        mBuckets.get(i).createBucketSettings(bucketId, getMaxChannels());
            }
        }

        schedule.report_threshold_percent = DEFAULT_REPORT_THRESHOLD_PERCENTAGE;

        // update batching settings
        schedule.max_ap_per_scan = 0;
        schedule.report_threshold_num_scans = getMaxBatch();
        for (ScanSettings settings : requests) {
            // set APs per scan
            if (settings.numBssidsPerScan > schedule.max_ap_per_scan) {
                schedule.max_ap_per_scan = settings.numBssidsPerScan;
            }

            // set batching
            if (settings.maxScansToCache != 0
                    && settings.maxScansToCache < schedule.report_threshold_num_scans) {
                schedule.report_threshold_num_scans = settings.maxScansToCache;
            }
        }
        if (schedule.max_ap_per_scan == 0 || schedule.max_ap_per_scan > getMaxApPerScan()) {
            schedule.max_ap_per_scan = getMaxApPerScan();
        }


        // update base period as gcd of periods
        if (schedule.num_buckets > 0) {
            int gcd = schedule.buckets[0].period_ms;
            for (int b = 1; b < schedule.num_buckets; b++) {
                gcd = Rational.gcd(schedule.buckets[b].period_ms, gcd);
            }

            if (gcd < PERIOD_MIN_GCD_MS) {
                Slog.wtf(TAG, "found gcd less than min gcd");
                gcd = PERIOD_MIN_GCD_MS;
            }

            schedule.base_period_ms = gcd;
        } else {
            schedule.base_period_ms = DEFAULT_PERIOD_MS;
        }

        return schedule;
    }

    public void addScanToBuckets(ScanSettings settings) {
        int bucketIndex;

        if (settings.maxPeriodInMs != 0
                && settings.maxPeriodInMs != settings.periodInMs) {
            // exponential back off scan has a dedicated bucket
            bucketIndex = EXPONENTIAL_BACK_OFF_BUCKET_IDX;
        } else {
            bucketIndex = findBestRegularBucketIndex(settings.periodInMs,
                                                     NUM_OF_REGULAR_BUCKETS);
        }

        mBuckets.getOrCreate(bucketIndex).settings.add(settings);
    }

    /**
     * find closest bucket period to the requested period in all predefined buckets
     */
    private static int findBestRegularBucketIndex(int requestedPeriod, int maxNumBuckets) {
        maxNumBuckets = Math.min(maxNumBuckets, NUM_OF_REGULAR_BUCKETS);
        int index = -1;
        int minDiff = Integer.MAX_VALUE;
        for (int i = 0; i < maxNumBuckets; ++i) {
            int diff = Math.abs(PREDEFINED_BUCKET_PERIODS[i] - requestedPeriod);
            if (diff < minDiff) {
                minDiff = diff;
                index = i;
            }
        }
        if (index == -1) {
            Slog.wtf(TAG, "Could not find best bucket for period " + requestedPeriod + " in "
                     + maxNumBuckets + " buckets");
        }
        return index;
    }

    /**
     * Reduce the number of required buckets by reassigning lower priorty buckets to the next
     * closest period bucket.
     */
    private void compactBuckets(int maxBuckets) {
        int maxRegularBuckets = maxBuckets;

        // reserve one bucket for exponential back off scan if there is
        // such request(s)
        if (mBuckets.isActive(EXPONENTIAL_BACK_OFF_BUCKET_IDX)) {
            maxRegularBuckets--;
        }
        // TODO: reserve another bucket for context hub scan request
        for (int i = NUM_OF_REGULAR_BUCKETS - 1;
                i >= 0 && mBuckets.getActiveRegularBucketCount() > maxRegularBuckets; --i) {
            if (mBuckets.isActive(i)) {
                for (ScanSettings scanRequest : mBuckets.get(i).settings) {
                    int newBucketIndex = findBestRegularBucketIndex(scanRequest.periodInMs, i);
                    mBuckets.getOrCreate(newBucketIndex).settings.add(scanRequest);
                }
                mBuckets.clear(i);
            }
        }
    }
}