/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.distributedlog.logsegment;

import com.google.common.annotations.Beta;
import com.twitter.distributedlog.Entry;
import com.twitter.distributedlog.LogSegmentMetadata;
import com.twitter.distributedlog.io.AsyncCloseable;
import com.twitter.util.Future;

import java.util.List;

/**
 * An interface class to read the enveloped entry (serialized bytes of
 * {@link com.twitter.distributedlog.Entry}) from a log segment
 */
@Beta
public interface LogSegmentEntryReader extends AsyncCloseable {

    /**
     * Start the reader. The method to signal the implementation
     * to start preparing the data for consumption {@link #readNext(int)}
     */
    void start();

    /**
     * Update the log segment each time when the metadata has changed.
     *
     * @param segment new metadata of the log segment.
     */
    void onLogSegmentMetadataUpdated(LogSegmentMetadata segment);

    /**
     * Read next <i>numEntries</i> entries from current log segment.
     * <p>
     * <i>numEntries</i> will be best-effort.
     *
     * @param numEntries num entries to read from current log segment
     * @return A promise that when satisified will contain a non-empty list of entries with their content.
     * @throws {@link com.twitter.distributedlog.exceptions.EndOfLogSegmentException} when
     *          read entries beyond the end of a <i>closed</i> log segment.
     */
    Future<List<Entry.Reader>> readNext(int numEntries);

    /**
     * Return the last add confirmed entry id (LAC).
     *
     * @return the last add confirmed entry id.
     */
    long getLastAddConfirmed();

}